# 저장된 예제 JSON을 추론 API에 전송하며 -TestMode 지정 시 실제 AI 대신 모의 추출을 사용한다.
param(
    [ValidateSet('rule', 'ai', 'uncertain', 'quantity', 'capacity', 'weight', 'set')]
    [string]$Example = 'rule',
    [string]$BaseUrl = 'http://127.0.0.1:8081',
    [switch]$TestMode
)
$ErrorActionPreference = 'Stop'
$requestFile = Join-Path $PSScriptRoot "$Example-request.json"
$url = "$BaseUrl/api/v1/coupang/purchase-options/infer"
if ($TestMode) { $url += '?testMode=true' }
# 파일을 바이트 그대로 전송해 상품 원문의 줄바꿈·문자 인코딩을 유지한다.
& curl.exe --fail-with-body --silent --show-error $url -H 'Content-Type: application/json' --data-binary "@$requestFile"
exit $LASTEXITCODE
