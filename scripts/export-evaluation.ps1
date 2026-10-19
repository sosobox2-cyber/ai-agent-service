param(
    [string]$BaseUrl = 'http://127.0.0.1:8081',
    [string]$DatasetId,
    [string]$OutputPath = (Join-Path $PSScriptRoot '../evals/stoa-reviewed.json'),
    [string]$ApiKey = $env:AI_AGENT_API_KEY
)
$ErrorActionPreference = 'Stop'
$headers = @{}
if ($ApiKey) { $headers['X-API-Key'] = $ApiKey }
$root = $BaseUrl.TrimEnd('/') + '/api/v1/evaluation/datasets'
if (!$DatasetId) {
    $datasets = Invoke-RestMethod -Uri $root -Headers $headers
    $datasets = @($datasets)
    if (!$datasets.Count) { throw '평가 데이터 세트가 없습니다.' }
    $DatasetId = $datasets[0].id
}
if ($DatasetId -notmatch '^[a-f0-9-]{36}$') { throw '데이터 세트 ID를 확인하세요.' }
$snapshot = Invoke-RestMethod -Uri "$root/$DatasetId/git-snapshot" -Headers $headers
$path = [IO.Path]::GetFullPath($OutputPath)
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path)) | Out-Null
$json = $snapshot | ConvertTo-Json -Depth 100
[IO.File]::WriteAllText($path, $json + "`n", [Text.UTF8Encoding]::new($false))
Write-Output "검토한 $($snapshot.cases.Count)상품 저장: $path"
