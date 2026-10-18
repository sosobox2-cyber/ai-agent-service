# 두 평가 실행의 케이스별 산출값을 비교하고 실행 간 변동(불안정성)을 집계한다.
# 검증 통과율은 형식만 보므로, 값이 실제로 흔들리는지는 이 비교로만 확인할 수 있다.
param(
    [string[]]$Labels = @('values', 'v28b'),
    [string]$Dir = (Join-Path $PSScriptRoot '..\logs\prompt-eval')
)
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }

function Get-Signature($row) {
    # 단품별 구매옵션을 이름순으로 정렬해 실행 간 비교 가능한 문자열로 만든다.
    $parts = foreach ($item in @($row.items)) {
        $pairs = $item.purchaseOptions.PSObject.Properties | Sort-Object Name |
            ForEach-Object { "$($_.Name)=$($_.Value)" }
        "$($item.optionId):" + ($pairs -join ',')
    }
    ($parts -join ' | ')
}

# -File로 호출하면 인자가 문자열 하나로 들어오므로 쉼표를 직접 분리한다.
$Labels = $Labels -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }

foreach ($label in $Labels) {
    $path = Join-Path $Dir "$label.jsonl"
    if (-not (Test-Path $path)) { Write-Host "건너뜀(없음): $path"; continue }

    $byCase = @{}
    foreach ($line in (Get-Content $path -Encoding utf8)) {
        $row = $line | ConvertFrom-Json
        if (-not $byCase.ContainsKey($row.case)) { $byCase[$row.case] = @() }
        $byCase[$row.case] += (Get-Signature $row)
    }

    $runs = ($byCase.Values | ForEach-Object { $_.Count } | Measure-Object -Maximum).Maximum
    Write-Host ''
    Write-Host "=== $label ($runs회) ==="
    $unstable = 0
    foreach ($case in ($byCase.Keys | Sort-Object)) {
        # Group-Object가 그룹 하나만 돌려주면 스칼라라서 .Count가 그룹 수가 아닌 원소 수를 준다.
        $groups = @($byCase[$case] | Group-Object | Sort-Object Count -Descending)
        if ($groups.Count -le 1) { continue }
        $unstable++
        Write-Host ("{0,-14} 서로 다른 값 {1}종" -f $case, $groups.Count)
        foreach ($g in $groups) { Write-Host ("    {0}회  {1}" -f $g.Count, $g.Name) }
    }
    Write-Host ("불안정 케이스: {0}/{1}" -f $unstable, $byCase.Keys.Count)
}
