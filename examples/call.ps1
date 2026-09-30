param(
    [ValidateSet('rule', 'ai', 'uncertain')]
    [string]$Example = 'rule',
    [string]$BaseUrl = 'http://127.0.0.1:8081',
    [switch]$TestMode
)
$ErrorActionPreference = 'Stop'
$requestFile = Join-Path $PSScriptRoot "$Example-request.json"
$url = "$BaseUrl/api/v1/coupang/purchase-options/infer"
if ($TestMode) { $url += '?testMode=true' }
& curl.exe --fail-with-body --silent --show-error $url -H 'Content-Type: application/json' --data-binary "@$requestFile"
exit $LASTEXITCODE
