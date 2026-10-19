# 운영상품 테스트(평가) 데이터셋의 진행 상황과 상태별 사례를 요약한다.
# 응답은 파일로 받는다. 파이프로 받으면 콘솔 코드페이지로 디코딩되어 한글이 깨지고 JSON 파싱이 실패한다.
param(
    [string]$BaseUrl = 'http://127.0.0.1:8081',
    [string]$DatasetId = ''
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
$head = Get-Json "$base/cases?page=1&size=1"
$counts = $head.counts

Write-Host ("데이터셋 {0} · 전체 {1:N0}상품" -f $DatasetId, $counts.TOTAL)
$tested = $counts.TOTAL - $counts.UNTESTED - $counts.INPUT_ISSUE
Write-Host ("실행함 {0}상품 · 미실행 {1:N0} · 입력확인필요 {2}" -f $tested, $counts.UNTESTED, $counts.INPUT_ISSUE)

foreach ($state in 'CONFIRMED','MATCH','MISMATCH','REGRESSION','REVIEW_REQUIRED','ERROR') {
    $page = Get-Json "$base/cases?page=1&size=50&state=$state"
    if (-not $page.total) { continue }
    Write-Host ''
    Write-Host ("=== {0} · {1}건 ===" -f $state, $page.total)
    foreach ($case in $page.rows) {
        $name = $case.goodsName
        if ($name.Length -gt 40) { $name = $name.Substring(0, 40) + '…' }
        Write-Host ("  {0}  {1}" -f $case.goodsId, $name)
        Write-Host ("      기준 {0}행 · 일치 {1} · 차이 {2} · 성공확인 {3} · 회귀 {4}" -f
            $case.references, $case.matched, $case.differences, $case.confirmed, $case.regressions)
    }
}
