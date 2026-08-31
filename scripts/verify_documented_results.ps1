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

# 5단계 PENDING 결과는 당시 Ollama가 없었다는 역사적 원본이므로, 7단계 실측을 추가해도 덮어쓰지 않는다.
$pendingNlSummary = Get-Content -LiteralPath (
    Join-Path $repositoryRoot 'results/stage5-nl-pending-20260830/nl-summary.json'
) -Raw | ConvertFrom-Json
if ($pendingNlSummary.status -ne 'PENDING' -or $pendingNlSummary.pendingReason -ne 'OLLAMA_NOT_CONFIGURED') {
    throw 'Historical natural-language result must remain PENDING / OLLAMA_NOT_CONFIGURED.'
}
if ($null -ne $pendingNlSummary.correctCount -or $null -ne $pendingNlSummary.accuracy) {
    throw 'Historical unmeasured natural-language accuracy must remain null.'
}
Assert-ContainsText 'experiment report' $report '`stage5-nl-pending-20260830`은 `PENDING / OLLAMA_NOT_CONFIGURED`'

# 7단계 실제 모델 결과는 summary만 믿지 않고 JSON/CSV 50행을 다시 집계해 문서 수치와 교차 검증한다.
$actualRunDirectory = Join-Path $repositoryRoot 'results/stage7-qwen3-4b-instruct-20260831'
$actualNlSummary = Get-Content -LiteralPath (Join-Path $actualRunDirectory 'nl-summary.json') -Raw |
    ConvertFrom-Json
$actualNlResults = @(Get-Content -LiteralPath (Join-Path $actualRunDirectory 'nl-results.json') -Raw |
    ConvertFrom-Json)
$actualNlCsv = @(Import-Csv -LiteralPath (Join-Path $actualRunDirectory 'nl-results.csv'))
$actualNlMetadata = Get-Content -LiteralPath (Join-Path $actualRunDirectory 'metadata.json') -Raw |
    ConvertFrom-Json
$modelManifest = Get-Content -LiteralPath (Join-Path $actualRunDirectory 'model-manifest.json') -Raw |
    ConvertFrom-Json

$actualCorrect = @($actualNlResults | Where-Object { $_.correct }).Count
$actualGenerated = @($actualNlResults | Where-Object { $_.generationSucceeded }).Count
$actualFirstPass = @($actualNlResults | Where-Object { $_.firstAttemptValidationPassed }).Count
$actualAttemptSum = ($actualNlResults | Measure-Object -Property attemptCount -Sum).Sum
$actualRetried = @($actualNlResults | Where-Object { $_.attemptCount -gt 1 })
$actualMismatches = @($actualNlResults | Where-Object { $_.status -eq 'RESULT_MISMATCH' }).Count
$actualTimeouts = @($actualNlResults | Where-Object { $_.status -eq 'DB_TIMEOUT' }).Count

if ($actualNlSummary.status -ne 'COMPLETED' -or $null -ne $actualNlSummary.pendingReason `
        -or $actualNlResults.Count -ne 50 -or $actualNlCsv.Count -ne 50) {
    throw 'Actual natural-language run must contain 50 completed JSON and CSV results.'
}
if ($actualCorrect -ne 23 -or $actualGenerated -ne 50 -or $actualFirstPass -ne 49 `
        -or $actualAttemptSum -ne 51 -or $actualMismatches -ne 26 -or $actualTimeouts -ne 1) {
    throw 'Actual natural-language result counts changed unexpectedly.'
}
if ($actualNlSummary.correctCount -ne $actualCorrect -or $actualNlSummary.accuracy -ne 0.46 `
        -or $actualNlSummary.generationSuccessCount -ne $actualGenerated `
        -or $actualNlSummary.firstAttemptValidationPassCount -ne $actualFirstPass `
        -or $actualNlSummary.averageAttemptCount -ne 1.02 -or $actualNlSummary.medianAttemptCount -ne 1.0) {
    throw 'Actual natural-language summary does not match its raw result rows.'
}
if ($actualRetried.Count -ne 1 -or $actualRetried[0].id -ne 'NL-045' `
        -or $actualRetried[0].generatedSql.Count -ne 2 -or -not $actualRetried[0].correct) {
    throw 'Expected only NL-045 to recover on its second generated SQL.'
}
if ($actualNlMetadata.modelName -ne 'qwen3:4b-instruct' -or $actualNlMetadata.ollamaVersion -ne '0.33.2' `
        -or $actualNlMetadata.temperature -ne 0.0) {
    throw 'Actual run metadata changed model, Ollama version, or temperature.'
}
if ($modelManifest.modelName -ne $actualNlMetadata.modelName `
        -or $modelManifest.digest -ne '0edcdef34593eac1aa2be9c7d06c432dcf81945adca5eca2f27662c18f168ba0' `
        -or $modelManifest.quantization -ne 'Q4_K_M' -or $modelManifest.license -ne 'Apache-2.0' `
        -or $modelManifest.runtimeObservation.processor -ne '100% CPU' `
        -or $modelManifest.runtimeObservation.warmupWallMs -ne 13907) {
    throw 'Model manifest does not match the measured model identity.'
}

# 실패 경로는 현재 runner가 elapsedMs=0을 기록하므로 실제 시간이 있는 49건만 latency 통계에 넣는다.
$actualLatencies = @($actualNlResults | Where-Object { $_.elapsedMs -gt 0 } |
    ForEach-Object { [double]$_.elapsedMs } | Sort-Object)
$latencyCount = $actualLatencies.Count
$latencyAverage = [math]::Round(($actualLatencies | Measure-Object -Average).Average, 1)
$latencyMedian = $actualLatencies[[math]::Floor($latencyCount / 2)]
$latencyP95 = $actualLatencies[[math]::Ceiling($latencyCount * 0.95) - 1]
if ($latencyCount -ne 49 -or $latencyAverage -ne 12668.4 -or $latencyMedian -ne 10358 `
        -or $actualLatencies[0] -ne 6077 -or $actualLatencies[-1] -ne 65355 -or $latencyP95 -ne 22498) {
    throw 'Actual natural-language latency statistics changed unexpectedly.'
}

Assert-ContainsText 'blog draft' $blog '실제 정확도는 23/50, 46.0%였다.'
Assert-ContainsText 'experiment report' $report '| 전체 정확도 | 23/50 (46.0%) |'
Assert-ContainsText 'experiment report' $report '| 생성 성공률 | 50/50 (100.0%) |'
Assert-ContainsText 'experiment report' $report '| 첫 시도 검증 통과율 | 49/50 (98.0%) |'
Assert-ContainsText 'experiment report' $report '| 평균·중앙 attempt | 1.02 / 1.0 |'
Assert-ContainsText 'experiment report' $report '| 실패 | 결과 불일치 26건, DB timeout 1건 |'
Assert-ContainsText 'experiment report' $report $modelManifest.digest
Assert-ContainsText 'experiment report' $report '평균 12,668.4ms, 중앙값 10,358ms, 최소 6,077ms,'
Assert-ContainsText 'experiment report' $report '최대 65,355ms, nearest-rank p95 22,498ms'
Assert-ContainsText 'progress' $progress '정확도 23/50 (46.0%)'

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

$actualDifficultyExpectations = @(
    @{ Difficulty = 'EASY'; Correct = 11; Total = 23; Accuracy = '47.8%' },
    @{ Difficulty = 'MEDIUM'; Correct = 8; Total = 19; Accuracy = '42.1%' },
    @{ Difficulty = 'HARD'; Correct = 4; Total = 8; Accuracy = '50.0%' }
)
foreach ($expected in $actualDifficultyExpectations) {
    $subset = @($actualNlResults | Where-Object { $_.difficulty -eq $expected.Difficulty })
    $correct = @($subset | Where-Object { $_.correct }).Count
    if ($subset.Count -ne $expected.Total -or $correct -ne $expected.Correct) {
        throw "Actual accuracy changed for $($expected.Difficulty)."
    }
    Assert-ContainsText 'experiment report' $report (
        '| {0} | {1}/{2} | {3} |' -f $expected.Difficulty, $expected.Correct, $expected.Total, $expected.Accuracy
    )
}

$actualComparisonExpectations = @(
    @{ Comparison = 'SCALAR'; Correct = 11; Total = 28; Accuracy = '39.3%' },
    @{ Comparison = 'ORDERED'; Correct = 5; Total = 9; Accuracy = '55.6%' },
    @{ Comparison = 'UNORDERED'; Correct = 7; Total = 13; Accuracy = '53.8%' }
)
foreach ($expected in $actualComparisonExpectations) {
    $subset = @($actualNlResults | Where-Object { $_.comparison -eq $expected.Comparison })
    $correct = @($subset | Where-Object { $_.correct }).Count
    if ($subset.Count -ne $expected.Total -or $correct -ne $expected.Correct) {
        throw "Actual accuracy changed for $($expected.Comparison)."
    }
    Assert-ContainsText 'experiment report' $report (
        '| {0} | {1}/{2} | {3} |' -f $expected.Comparison, $expected.Correct, $expected.Total, $expected.Accuracy
    )
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
    "retry=30 nlActual=50 accuracy=46.0% tests=$testCount failures=0 skipped=0"
)
