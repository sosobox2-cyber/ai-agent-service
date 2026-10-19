# 특정 상태의 사례를 골라 기준값과 추론 결과를 행 단위로 비교해 보여준다.
param(
    [string]$BaseUrl = 'http://127.0.0.1:8081',
    [string]$DatasetId = '',
    [string]$State = 'REGRESSION'
)
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }
$utf8 = [System.Text.UTF8Encoding]::new($false)

function Get-Json($url) {
    $tmp = [System.IO.Path]::GetTempFileName()
    & curl.exe --silent --max-time 20 $url -o $tmp | Out-Null
    $text = [System.IO.File]::ReadAllText($tmp, $utf8)
    Remove-Item $tmp -ErrorAction SilentlyContinue
    return $text | ConvertFrom-Json
}

if (-not $DatasetId) { $DatasetId = (Get-Json "$BaseUrl/api/v1/evaluation/datasets")[0].id }
$base = "$BaseUrl/api/v1/evaluation/datasets/$DatasetId"

foreach ($row in (Get-Json "$base/cases?page=1&size=50&state=$State").rows) {
    $detail = Get-Json "$base/cases/$($row.id)"
    Write-Host ''
    Write-Host ("=== {0} · {1}" -f $row.goodsId, $row.goodsName)
    Write-Host ("    {0}" -f $detail.testCase.request.goodsName)
    $run = $detail.latest
    if ($run) { Write-Host ("    최근 실행 {0} · {1}" -f $run.mode, $run.startedAt) }
    $results = @{}
    foreach ($comparison in @($run.comparisons)) { $results[[string]$comparison.row] = $comparison }
    foreach ($reference in $detail.testCase.references) {
        $comparison = $results[[string]$reference.row]
        $review = $detail.reviews.($reference.row)
        Write-Host ("    [{0}행] {1}" -f $reference.row, $reference.target)
        Write-Host ("        등록 기준값 : {0}" -f $reference.baseline)
        if ($review -and $review.confirmedValue) { Write-Host ("        확정 기준값 : {0}" -f $review.confirmedValue) }
        Write-Host ("        이번 결과   : {0}  [{1}]" -f $comparison.actual, $comparison.status)
        if ($comparison.evidence) { Write-Host ("        근거        : {0}" -f $comparison.evidence) }
    }
}
