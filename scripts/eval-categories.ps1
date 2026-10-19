# 데이터셋의 카테고리 분포를 센다. 카테고리별 프롬프트 분리가 현실적인지 판단하는 근거로 쓴다.
# 한 페이지 100건씩 끝까지 읽으며 1단계·전체 경로 기준으로 집계한다.
param(
    [string]$BaseUrl = 'http://127.0.0.1:8081',
    [string]$DatasetId = '',
    [int]$MaxPages = 120
)
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }
$utf8 = [System.Text.UTF8Encoding]::new($false)

function Get-Json($url) {
    $tmp = [System.IO.Path]::GetTempFileName()
    & curl.exe --silent --max-time 30 $url -o $tmp | Out-Null
    $text = [System.IO.File]::ReadAllText($tmp, $utf8)
    Remove-Item $tmp -ErrorAction SilentlyContinue
    return $text | ConvertFrom-Json
}

if (-not $DatasetId) { $DatasetId = (Get-Json "$BaseUrl/api/v1/evaluation/datasets")[0].id }
$base = "$BaseUrl/api/v1/evaluation/datasets/$DatasetId"

$full = @{}; $top = @{}; $total = 0
for ($page = 1; $page -le $MaxPages; $page++) {
    $result = Get-Json "$base/cases?page=$page&size=100"
    if (-not $result.rows.Count) { break }
    foreach ($row in $result.rows) {
        $total++
        $name = if ($row.categoryName) { $row.categoryName } else { '(없음)' }
        # PowerShell 5.1에는 ?? 연산자가 없다.
        if (-not $full.ContainsKey($name)) { $full[$name] = 0 }
        $full[$name]++
        $first = ($name -split '>')[0].Trim()
        if (-not $top.ContainsKey($first)) { $top[$first] = 0 }
        $top[$first]++
    }
    if ($total -ge $result.total) { break }
}

Write-Host ("집계한 상품 {0:N0}개" -f $total)
Write-Host ("전체 경로 기준 카테고리 {0:N0}종" -f $full.Keys.Count)
Write-Host ("1단계 기준 카테고리 {0}종" -f $top.Keys.Count)
Write-Host ''
Write-Host '--- 1단계 분포 ---'
foreach ($entry in ($top.GetEnumerator() | Sort-Object Value -Descending)) {
    Write-Host ("  {0,-22} {1,6:N0}  ({2,5:P1})" -f $entry.Key, $entry.Value, ($entry.Value / $total))
}
Write-Host ''
Write-Host '--- 전체 경로 상위 15 ---'
foreach ($entry in ($full.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 15)) {
    Write-Host ("  {0,6:N0}  {1}" -f $entry.Value, $entry.Key)
}
