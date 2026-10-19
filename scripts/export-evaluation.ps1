param(
    [string]$BaseUrl = 'http://127.0.0.1:8081',
    [string]$DatasetId,
    [string]$OutputPath = (Join-Path $PSScriptRoot '../evals/stoa-reviewed.json'),
    [string]$ApiKey = $env:AI_AGENT_API_KEY
)
$ErrorActionPreference = 'Stop'
$headers = @{}
if ($ApiKey) { $headers['X-API-Key'] = $ApiKey }
function Get-EvaluationJson([string]$Uri) {
    # Windows PowerShell 5.1은 charset 없는 JSON을 잘못 디코딩하므로 원본 바이트를 UTF-8로 읽는다.
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -Headers $headers
    $text = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    return ($text | ConvertFrom-Json)
}
$root = $BaseUrl.TrimEnd('/') + '/api/v1/evaluation/datasets'
if (!$DatasetId) {
    $datasets = Get-EvaluationJson $root
    $datasets = @($datasets)
    if (!$datasets.Count) { throw '평가 데이터 세트가 없습니다.' }
    $DatasetId = $datasets[0].id
}
if ($DatasetId -notmatch '^[a-f0-9-]{36}$') { throw '데이터 세트 ID를 확인하세요.' }
$snapshot = Get-EvaluationJson "$root/$DatasetId/git-snapshot"
$path = [IO.Path]::GetFullPath($OutputPath)
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path)) | Out-Null
$json = $snapshot | ConvertTo-Json -Depth 100
[IO.File]::WriteAllText($path, $json + "`n", [Text.UTF8Encoding]::new($false))
Write-Output "검토한 $($snapshot.cases.Count)상품 저장: $path"
