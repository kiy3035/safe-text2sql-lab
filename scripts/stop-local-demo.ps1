param(
    [switch]$RemoveData
)

$ErrorActionPreference = 'Stop'

<#
Spring Boot는 start-local-demo.ps1을 실행한 창에서 Ctrl+C로 먼저 종료한다. 이 스크립트는 데모용
Compose project만 내리며, -RemoveData를 명시한 경우에만 합성 PostgreSQL volume까지 삭제한다.
다른 Docker project와 Ollama 모델 파일은 건드리지 않는다.
#>

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$environmentFile = Join-Path $repositoryRoot '.env'
$composeProject = 'safe-text2sql-demo'

if (-not (Test-Path -LiteralPath $environmentFile)) {
    throw '루트 .env가 없습니다. start-local-demo.ps1을 먼저 실행했는지 확인하세요.'
}

foreach ($rawLine in Get-Content -LiteralPath $environmentFile) {
    $line = $rawLine.Trim()
    if ($line.Length -eq 0 -or $line.StartsWith('#')) {
        continue
    }

    $separator = $line.IndexOf('=')
    if ($separator -le 0) {
        throw '잘못된 .env 항목입니다.'
    }
    [Environment]::SetEnvironmentVariable(
            $line.Substring(0, $separator).Trim(),
            $line.Substring($separator + 1),
            'Process')
}

Push-Location $repositoryRoot
try {
    $arguments = @('compose', '-p', $composeProject, 'down')
    if ($RemoveData) {
        $arguments += '--volumes'
        Write-Output '데모용 PostgreSQL 합성 데이터 volume도 함께 제거합니다.'
    }

    & docker @arguments
    if ($LASTEXITCODE -ne 0) {
        throw '데모용 Docker Compose 종료에 실패했습니다.'
    }
} finally {
    Pop-Location
}
