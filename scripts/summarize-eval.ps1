# 한 평가 실행을 케이스별로 요약한다. 검증 통과율과 자동적용 비율, 그리고 산출값 분포를 함께 본다.
# 통과율만으로는 값의 정확성을 알 수 없으므로 값 분포를 같이 봐야 한다.
param(
    [string]$Label = 'stability',
    [string]$Dir = (Join-Path $PSScriptRoot '..\logs\prompt-eval')
)
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }

$rows = Get-Content (Join-Path $Dir "$Label.jsonl") -Encoding utf8 | ForEach-Object { $_ | ConvertFrom-Json }

foreach ($grp in ($rows | Group-Object case | Sort-Object Name)) {
    $n = $grp.Count
    $pass = @($grp.Group | Where-Object { $_.validatorPassed }).Count
    $auto = @($grp.Group | Where-Object { $_.autoApply }).Count
    Write-Host ("`n{0}  n={1}  통과={2}  자동적용={3}" -f $grp.Name, $n, $pass, $auto)

    foreach ($row in ($grp.Group | Where-Object { -not $_.validatorPassed } | Select-Object -First 1)) {
        Write-Host ("   검증실패 예시: {0}" -f (@($row.validationErrors) -join '; '))
    }
    # 단품이 여럿일 수 있으므로 구매옵션 단위로 값 빈도를 센다.
    $values = @{}
    foreach ($row in $grp.Group) {
        foreach ($item in @($row.items)) {
            foreach ($prop in $item.purchaseOptions.PSObject.Properties) {
                $key = "$($item.optionId) / $($prop.Name)"
                if (-not $values.ContainsKey($key)) { $values[$key] = @() }
                $values[$key] += $prop.Value
            }
        }
    }
    foreach ($key in ($values.Keys | Sort-Object)) {
        $dist = @($values[$key] | Group-Object | Sort-Object Count -Descending)
        $text = ($dist | ForEach-Object { "$($_.Name) x$($_.Count)" }) -join '  |  '
        $flag = if ($dist.Count -gt 1) { '*' } else { ' ' }
        Write-Host ("  {0} {1,-28} {2}" -f $flag, $key, $text)
    }
}
