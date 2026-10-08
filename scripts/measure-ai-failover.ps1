param([string]$Path = 'logs/ai-usage.jsonl')
$ErrorActionPreference = 'Stop'
$rows = @(Get-Content -LiteralPath $Path -Encoding UTF8 | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
# 기존 JSONL과 새 camelCase JSONL을 함께 집계할 수 있게 정규화한다.
foreach ($row in $rows) {
    foreach ($pair in @(@('inputTokens','input_tokens'), @('cachedTokens','cached_tokens'),
            @('outputTokens','output_tokens'), @('totalTokens','total_tokens'), @('estimatedCostUsd','estimated_cost_usd'))) {
        if ($row.PSObject.Properties.Name -notcontains $pair[0]) {
            $row | Add-Member -NotePropertyName $pair[0] -NotePropertyValue $row.($pair[1])
        }
    }
}
function AverageKnown([string]$Field) {
    $known = @($rows | Where-Object { $null -ne $_.$Field })
    if (!$known.Count) { return $null }
    return ($known | Measure-Object -Property $Field -Average).Average
}
$requests = @($rows | Group-Object inferenceId)
$initial = @($requests | ForEach-Object { $_.Group | Sort-Object retryCount | Select-Object -First 1 })
$final = @($requests | ForEach-Object { $_.Group | Sort-Object retryCount | Select-Object -Last 1 })
$light = @($initial | Where-Object { $_.initialPromptMode -eq 'LIGHT' })
$retries = @($rows | Where-Object { $_.retryCount -eq 1 })
$lightSuccess = @($light | Where-Object { $_.status -eq 'SUCCESS' })
$knownExtra = @($retries | Where-Object { $null -ne $_.estimatedCostUsd })
[decimal]$extraCost = 0
foreach ($row in $knownExtra) { $extraCost += [decimal]$row.estimatedCostUsd }
[decimal]$totalCost = 0
foreach ($row in $rows) { if ($null -ne $row.estimatedCostUsd) { $totalCost += [decimal]$row.estimatedCostUsd } }
[pscustomobject]@{
    totalCalls = $rows.Count
    lightCalls = @($rows | Where-Object { $_.promptMode -eq 'LIGHT' }).Count
    fullCalls = @($rows | Where-Object { $_.promptMode -eq 'FULL' }).Count
    lightRatioPercent = $(if ($rows.Count) { 100 * @($rows | Where-Object { $_.promptMode -eq 'LIGHT' }).Count / $rows.Count } else { $null })
    fullRatioPercent = $(if ($rows.Count) { 100 * @($rows | Where-Object { $_.promptMode -eq 'FULL' }).Count / $rows.Count } else { $null })
    averageInputTokens = AverageKnown 'inputTokens'
    averageCachedTokens = AverageKnown 'cachedTokens'
    averageOutputTokens = AverageKnown 'outputTokens'
    averageConfidence = AverageKnown 'confidence'
    averageElapsedMs = AverageKnown 'elapsedMs'
    uncertainCalls = @($rows | Where-Object { $null -ne $_.certain -and !$_.certain }).Count
    totalProducts = $requests.Count
    lightStarts = $light.Count
    fullStarts = @($initial | Where-Object { $_.initialPromptMode -eq 'FULL' }).Count
    lightSuccess = $lightSuccess.Count
    lightToFull = $retries.Count
    lightSuccessRatePercent = $(if ($light.Count) { [math]::Round(100 * $lightSuccess.Count / $light.Count, 2) } else { $null })
    failoverRatePercent = $(if ($light.Count) { [math]::Round(100 * $retries.Count / $light.Count, 2) } else { $null })
    fullRetrySuccess = @($retries | Where-Object { $_.status -eq 'SUCCESS' }).Count
    finalReviewRequired = @($final | Where-Object { $_.status -eq 'REVIEW_REQUIRED' }).Count
    finalApiErrors = @($final | Where-Object { $_.status -eq 'API_ERROR' }).Count
    incompleteRequests = @($final | Where-Object { $_.status -eq 'VALIDATION_ERROR' }).Count
    knownExtraEstimatedCostUsd = $extraCost
    unknownExtraCostCalls = $retries.Count - $knownExtra.Count
    knownTotalEstimatedCostUsd = $totalCost
    unknownTotalCostCalls = @($rows | Where-Object { $null -eq $_.estimatedCostUsd }).Count
} | ConvertTo-Json
