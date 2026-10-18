# examples의 모든 요청을 실제 추론 API에 보내고 ResultValidator 통과율을 집계한다.
# 프롬프트를 고친 뒤 -Label을 바꿔 다시 실행하면 변형 간 비교가 가능하다.
param(
    [string]$BaseUrl = 'http://127.0.0.1:8081',
    [string]$Label = 'baseline',
    [int]$Repeat = 1,
    [string]$OutDir = (Join-Path $PSScriptRoot '..\logs\prompt-eval'),
    [string[]]$Only = @(),
    # 모의 추출로 돌려 과금 없이 하네스 자체를 점검할 때 쓴다.
    [switch]$TestMode,
    # 공급자 호출 한도(429)를 피하기 위한 호출 간 대기와 재시도 설정이다.
    [int]$DelayMs = 2000,
    [int]$MaxRetry = 4
)
$ErrorActionPreference = 'Stop'
$PSDefaultParameterValues['Out-File:Encoding'] = 'utf8'
# 상품 원문과 검증 메시지가 한국어이므로 콘솔·파이프 출력을 UTF-8로 고정한다.
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }

$examplesDir = Join-Path $PSScriptRoot '..\examples'
$url = "$BaseUrl/api/v1/coupang/purchase-options/infer"
if ($TestMode) { $url += '?testMode=true' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$resultFile = Join-Path $OutDir "$Label.jsonl"
Remove-Item $resultFile -ErrorAction SilentlyContinue

$files = Get-ChildItem (Join-Path $examplesDir '*-request.json') | Sort-Object Name
if ($Only.Count -gt 0) {
    # -File로 호출하면 인자가 문자열 하나로 들어오므로 쉼표를 직접 분리한다.
    $wanted = $Only -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    $files = $files | Where-Object { $wanted -contains ($_.BaseName -replace '-request$', '') }
}
if ($files.Count -eq 0) { throw "실행할 예제가 없습니다: $examplesDir" }

$rows = @()
foreach ($run in 1..$Repeat) {
    foreach ($file in $files) {
        $case = $file.BaseName -replace '-request$', ''
        $tmp = [System.IO.Path]::GetTempFileName()
        $raw = ''; $status = 0; $attempt = 0; $elapsed = 0
        while ($true) {
            $attempt++
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            # 상품 원문의 줄바꿈과 인코딩을 유지하려면 파일을 바이트 그대로 보내야 한다.
            # --fail 계열을 쓰지 않아야 429 본문도 받고 curl이 0으로 끝나 실행이 중단되지 않는다.
            $status = (& curl.exe --silent --show-error -w '%{http_code}' $url `
                -H 'Content-Type: application/json' --data-binary "@$($file.FullName)" -o $tmp) 2>$null
            $sw.Stop()
            $elapsed = $sw.ElapsedMilliseconds
            # 응답은 파일로 받는다. 파이프로 받으면 콘솔 코드페이지로 디코딩되어 한글이 깨진다.
            $raw = [System.IO.File]::ReadAllText($tmp, [System.Text.UTF8Encoding]::new($false))
            # 호출 한도는 일시적이므로 지수 backoff로 재시도한다. 측정값이 아니라 수집 실패를 막는 장치다.
            if ("$status" -ne '429' -or $attempt -gt $MaxRetry) { break }
            $wait = [math]::Min(30000, $DelayMs * [math]::Pow(2, $attempt))
            Write-Host ("  429 한도 초과, {0:N0}ms 대기 후 재시도 ({1}/{2}) - {3}" -f $wait, $attempt, $MaxRetry, $case)
            Start-Sleep -Milliseconds $wait
        }
        Remove-Item $tmp -ErrorAction SilentlyContinue

        $row = [ordered]@{
            label = $Label; run = $run; case = $case; elapsedMs = $elapsed
            httpStatus = "$status"; attempts = $attempt
        }
        try {
            $res = $raw | ConvertFrom-Json
            $errors = @($res.validationErrors)
            $row.httpOk            = $true
            $row.success           = [bool]$res.success
            $row.autoApply         = [bool]$res.autoApplyCandidate
            $row.confidence        = $res.confidence
            $row.validatorPassed   = ($errors.Count -eq 0)
            $row.validationErrors  = $errors
            $row.errorCode         = $res.errorCode
            $row.promptVersion     = $res.promptVersion
            $row.inferenceSource   = $res.inferenceSource
            $row.mappingCount      = @($res.optionMappings).Count
            $row.reason            = $res.reason
            # 실제 산출값을 남겨야 통과 여부와 별개로 값의 정확성을 검토할 수 있다.
            $row.items             = $res.items
            # 비용과 토큰은 호출별 사용량의 합으로 본다.
            # testMode나 호출 실패 시 aiUsage가 비므로 합산 전에 비어 있는지 확인한다.
            $usage = @($res.aiUsage)
            # AiCallUsage의 JSON 필드명은 snake_case다.
            $row.inputTokens  = if ($usage.Count) { ($usage | Measure-Object -Property input_tokens -Sum).Sum } else { 0 }
            $row.outputTokens = if ($usage.Count) { ($usage | Measure-Object -Property output_tokens -Sum).Sum } else { 0 }
            $row.costUsd      = if ($usage.Count) { ($usage | Measure-Object -Property estimated_cost_usd -Sum).Sum } else { 0 }
        } catch {
            # 응답을 해석하지 못하면 예외와 원문을 함께 남겨 원인을 추적할 수 있게 한다.
            $row.httpOk = $false; $row.validatorPassed = $false
            $row.parseError = $_.Exception.Message
            $row.rawResponse = "$raw"
        }
        $obj = [pscustomobject]$row
        $rows += $obj
        $obj | ConvertTo-Json -Depth 6 -Compress | Add-Content -Path $resultFile -Encoding utf8

        $mark = if ($row.validatorPassed) { 'PASS' } else { 'FAIL' }
        $detail = ''
        if (-not $row.validatorPassed) {
            $joined = @($row.validationErrors) -join '; '
            $http = if ("$status" -ne '200') { "HTTP $status" } else { $null }
            $parts = @($http, $joined, $row.errorCode, $row.parseError) | Where-Object { $_ }
            $detail = ' | ' + ($parts -join ' / ')
        }
        Write-Host ("[{0}] {1,-14} {2,6}ms conf={3}{4}" -f $mark, $case, $row.elapsedMs, $row.confidence, $detail)
        # 호출 한도에 걸리지 않도록 테스트 모드가 아닐 때는 호출 사이를 띄운다.
        if (-not $TestMode) { Start-Sleep -Milliseconds $DelayMs }
    }
}

$passed = @($rows | Where-Object { $_.validatorPassed }).Count
$total  = $rows.Count
Write-Host ''
Write-Host "=== $Label ==="
Write-Host ("검증 통과율 : {0}/{1} ({2:P1})" -f $passed, $total, ($passed / $total))
Write-Host ("자동적용 후보: {0}/{1}" -f @($rows | Where-Object { $_.autoApply }).Count, $total)
Write-Host ("평균 confidence: {0:N3}" -f (($rows | Measure-Object -Property confidence -Average).Average))
Write-Host ("평균 지연      : {0:N0}ms" -f (($rows | Measure-Object -Property elapsedMs -Average).Average))
Write-Host ("총 비용        : `$ {0:N4}" -f (($rows | Measure-Object -Property costUsd -Sum).Sum))
Write-Host "케이스별 결과: $resultFile"

# 실패한 케이스는 프롬프트 수정의 출발점이므로 따로 모아 보여준다.
$failed = $rows | Where-Object { -not $_.validatorPassed }
if ($failed) {
    Write-Host ''
    Write-Host '--- 실패 케이스 ---'
    $failed | Group-Object case | ForEach-Object {
        Write-Host ("{0} ({1}회): {2}" -f $_.Name, $_.Count, (@($_.Group[0].validationErrors) -join '; '))
    }
}
