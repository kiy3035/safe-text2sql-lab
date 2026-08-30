Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# 이 스크립트는 저장소 루트가 아닌 위치에서 호출해도 같은 파일을 읽도록 자체 위치를 기준으로 삼는다.
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$blogPath = Join-Path $repositoryRoot 'docs/blog-draft.md'
$reportPath = Join-Path $repositoryRoot 'docs/experiment-report.md'
$progressPath = Join-Path $repositoryRoot 'PROGRESS.md'
$blog = Get-Content -LiteralPath $blogPath -Raw
$report = Get-Content -LiteralPath $reportPath -Raw
$progress = Get-Content -LiteralPath $progressPath -Raw
$invariantCulture = [System.Globalization.CultureInfo]::InvariantCulture

function Assert-ContainsText {
    param(
        [Parameter(Mandatory = $true)][string]$DocumentName,
        [Parameter(Mandatory = $true)][string]$Document,
        [Parameter(Mandatory = $true)][string]$Expected
    )

    # 수치가 다른 문맥에 우연히 등장하는 것을 줄이기 위해 가능한 경우 표의 한 행 전체를 전달한다.
    if (-not $Document.Contains($Expected)) {
        throw "$DocumentName does not contain expected text: $Expected"
    }
}

function Format-Decimal {
    param(
        [Parameter(Mandatory = $true)][double]$Value,
        [Parameter(Mandatory = $true)][string]$Pattern
    )

    return $Value.ToString($Pattern, $invariantCulture)
}

# 자연어 실험은 실제 모델이 없었던 실행이다. null 정확도를 숫자로 바꾸는 문서 회귀를 먼저 막는다.
$nlSummary = Get-Content -LiteralPath (
    Join-Path $repositoryRoot 'results/stage5-nl-pending-20260830/nl-summary.json'
) -Raw | ConvertFrom-Json
if ($nlSummary.status -ne 'PENDING' -or $nlSummary.pendingReason -ne 'OLLAMA_NOT_CONFIGURED') {
    throw 'Natural-language result must remain PENDING / OLLAMA_NOT_CONFIGURED.'
}
if ($null -ne $nlSummary.correctCount -or $null -ne $nlSummary.accuracy) {
    throw 'Unmeasured natural-language accuracy must remain null.'
}
Assert-ContainsText 'blog draft' $blog '`PENDING / OLLAMA_NOT_CONFIGURED`'
Assert-ContainsText 'experiment report' $report '| 상태 | `PENDING` |'
Assert-ContainsText 'experiment report' $report '| 정확도 | 측정 안 함 (`null`) |'

$questions = Get-Content -LiteralPath (Join-Path $repositoryRoot 'experiments/nl-questions.jsonl') |
    ForEach-Object { $_ | ConvertFrom-Json }
$goldenSql = Get-Content -LiteralPath (Join-Path $repositoryRoot 'experiments/golden-sql.jsonl') |
    ForEach-Object { $_ | ConvertFrom-Json }
if ($questions.Count -ne 50 -or $goldenSql.Count -ne 50) {
    throw 'Question and Golden SQL fixtures must each contain exactly 50 entries.'
}

$difficultyRows = @(
    @{ Difficulty = 'EASY'; Scalar = 17; Ordered = 4; Unordered = 2; Total = 23 },
    @{ Difficulty = 'MEDIUM'; Scalar = 9; Ordered = 2; Unordered = 8; Total = 19 },
    @{ Difficulty = 'HARD'; Scalar = 2; Ordered = 3; Unordered = 3; Total = 8 }
)
foreach ($expected in $difficultyRows) {
    $subset = $questions | Where-Object { $_.difficulty -eq $expected.Difficulty }
    $scalar = ($subset | Where-Object { $_.comparison -eq 'SCALAR' }).Count
    $ordered = ($subset | Where-Object { $_.comparison -eq 'ORDERED' }).Count
    $unordered = ($subset | Where-Object { $_.comparison -eq 'UNORDERED' }).Count
    if ($subset.Count -ne $expected.Total -or $scalar -ne $expected.Scalar `
            -or $ordered -ne $expected.Ordered -or $unordered -ne $expected.Unordered) {
        throw "Question distribution changed for $($expected.Difficulty)."
    }
    $expectedRow = '| {0} | {1} | {2} | {3} | {4} |' -f 
        $expected.Difficulty, $expected.Scalar, $expected.Ordered, $expected.Unordered, $expected.Total
    Assert-ContainsText 'experiment report' $report $expectedRow
}

# 악의적 fixture와 원본 plan 수는 블로그의 시험 범위를 결정하므로 파일 개수까지 대조한다.
$malicious = Get-Content -LiteralPath (
    Join-Path $repositoryRoot 'src/test/resources/security/malicious-sql.json'
) -Raw | ConvertFrom-Json
$planFiles = Get-ChildItem -LiteralPath (
    Join-Path $repositoryRoot 'results/stage5-index-20260830/plans'
) -Filter '*.json'
if ($malicious.Count -ne 20 -or $planFiles.Count -ne 60) {
    throw 'Expected 20 malicious fixtures and 60 raw execution plans.'
}
Assert-ContainsText 'blog draft' $blog '악의적 fixture 20건은 모두'
Assert-ContainsText 'blog draft' $blog '원본 실행계획 JSON 60개'

# 인덱스 표는 CSV의 같은 case/condition 쌍에서 구성한다. 비율도 두 중앙값에서 다시 계산한다.
$indexRows = Import-Csv -LiteralPath (
    Join-Path $repositoryRoot 'results/stage5-index-20260830/index-summary.csv'
)
foreach ($caseId in 'IDX-001', 'IDX-002', 'IDX-003') {
    $before = $indexRows | Where-Object { $_.caseId -eq $caseId -and $_.condition -eq 'BEFORE' }
    $after = $indexRows | Where-Object { $_.caseId -eq $caseId -and $_.condition -eq 'AFTER' }
    if ($null -eq $before -or $null -eq $after) {
        throw "Missing index summary pair for $caseId."
    }

    $beforeMedian = Format-Decimal ([double]$before.executionTimeMedianMs) '0.0000'
    $afterMedian = Format-Decimal ([double]$after.executionTimeMedianMs) '0.0000'
    $ratio = Format-Decimal (
        [double]$before.executionTimeMedianMs / [double]$after.executionTimeMedianMs
    ) '0.00'
    Assert-ContainsText 'blog draft' $blog "$beforeMedian`ms → $afterMedian`ms"
    Assert-ContainsText 'blog draft' $blog "$ratio`배"
    Assert-ContainsText 'experiment report' $report "$ratio`배"

    foreach ($row in $before, $after) {
        $minimum = Format-Decimal ([double]$row.executionTimeMinMs) '0.000'
        $maximum = Format-Decimal ([double]$row.executionTimeMaxMs) '0.000'
        $p95 = Format-Decimal ([double]$row.executionTimeP95Ms) '0.000'
        $condition = $row.condition
        $dispersionRow = "| $caseId | $condition | $minimum | $maximum | $p95 |"
        Assert-ContainsText 'experiment report' $report $dispersionRow
    }
}

# 재시도 원본 30건이 모두 기대한 호출 수·결과로 끝났는지 확인한 뒤 요약 표의 수치를 대조한다.
$retryMeasurements = Get-Content -LiteralPath (
    Join-Path $repositoryRoot 'results/stage5-retry-20260830/retry-measurements.json'
) -Raw | ConvertFrom-Json
if ($retryMeasurements.Count -ne 30) {
    throw 'Retry experiment must contain exactly 30 raw measurements.'
}
$retryExpectations = @{
    SERVER_SERVER_SUCCESS = @{ Calls = 3; Outcome = 'SUCCESS'; Label = '500→500→200' }
    SERVER_SERVER_SERVER = @{ Calls = 3; Outcome = 'LLM_UNAVAILABLE'; Label = '500→500→500' }
    CLIENT_ERROR = @{ Calls = 1; Outcome = 'LLM_REQUEST_REJECTED'; Label = '400' }
}
foreach ($entry in $retryExpectations.GetEnumerator()) {
    $subset = @($retryMeasurements | Where-Object { $_.scenario -eq $entry.Key })
    if ($subset.Count -ne 10) {
        throw "Retry scenario $($entry.Key) must have 10 repetitions."
    }
    $unexpected = @($subset | Where-Object {
        $_.httpCallCount -ne $entry.Value.Calls -or $_.attemptCount -ne $entry.Value.Calls `
            -or $_.outcome -ne $entry.Value.Outcome
    })
    if ($unexpected.Count -ne 0) {
        throw "Retry scenario $($entry.Key) contains an unexpected outcome."
    }
}

$retrySummary = Import-Csv -LiteralPath (
    Join-Path $repositoryRoot 'results/stage5-retry-20260830/retry-summary.csv'
)
foreach ($row in $retrySummary) {
    $expectation = $retryExpectations[$row.scenario]
    $median = Format-Decimal ([double]$row.elapsedMedianMs) '0.0'
    Assert-ContainsText 'blog draft' $blog "$median`ms"
    Assert-ContainsText 'experiment report' $report $expectation.Label
    Assert-ContainsText 'experiment report' $report $median
}

# 측정 코드 SHA가 세 metadata에서 동일하고 문서에도 명시됐는지 확인한다.
$metadataPaths = @(
    'results/stage5-nl-pending-20260830/metadata.json',
    'results/stage5-index-20260830/metadata.json',
    'results/stage5-retry-20260830/metadata.json'
)
$commitShas = @($metadataPaths | ForEach-Object {
    (Get-Content -LiteralPath (Join-Path $repositoryRoot $_) -Raw | ConvertFrom-Json).commitSha
} | Select-Object -Unique)
if ($commitShas.Count -ne 1) {
    throw 'All stage 5 result metadata files must reference the same measurement commit.'
}
Assert-ContainsText 'experiment report' $report $commitShas[0]

# 전체 테스트 수치는 실제 Gradle XML이 있을 때만이 아니라 반드시 그 결과를 근거로 확인한다.
$testResultDirectory = Join-Path $repositoryRoot 'build/test-results/test'
if (-not (Test-Path -LiteralPath $testResultDirectory)) {
    throw 'Run the full Gradle test task before verifying documented test totals.'
}
$testSuites = Get-ChildItem -LiteralPath $testResultDirectory -Filter 'TEST-*.xml' | ForEach-Object {
    [xml](Get-Content -LiteralPath $_.FullName -Raw)
}
$testCount = ($testSuites | ForEach-Object { [int]$_.testsuite.tests } | Measure-Object -Sum).Sum
$failureCount = ($testSuites | ForEach-Object {
    [int]$_.testsuite.failures + [int]$_.testsuite.errors
} | Measure-Object -Sum).Sum
$skippedCount = ($testSuites | ForEach-Object { [int]$_.testsuite.skipped } | Measure-Object -Sum).Sum
if ($failureCount -ne 0 -or $skippedCount -ne 0) {
    throw 'Document verification requires a full test run with no failures, errors, or skipped tests.'
}
Assert-ContainsText 'blog draft' $blog "최종 전체 테스트 $testCount`개가 통과"
Assert-ContainsText 'experiment report' $report "결과는 $testCount`개 통과"
Assert-ContainsText 'progress' $progress "$testCount`개 통과"

Write-Output (
    'DOCUMENTED_RESULTS_VERIFIED questions=50 golden=50 malicious=20 plans=60 ' +
    "retry=30 tests=$testCount failures=0 skipped=0"
)
