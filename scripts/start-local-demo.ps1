$ErrorActionPreference = 'Stop'

<#
.SYNOPSIS
로컬 데모에 필요한 환경과 PostgreSQL을 준비한 뒤 Spring Boot를 실행한다.

.DESCRIPTION
실제 비밀번호를 저장소에 하드코딩하지 않기 위해 최초 실행에서만 무작위 비밀번호를 만들고,
Git에서 제외된 루트 `.env` 파일에 보존한다. 이후에는 같은 `.env`를 다시 불러오므로 사용자가
PowerShell 환경 변수를 매번 입력할 필요가 없다.

Ollama 프로그램과 모델은 자동 설치하지 않는다. 설치·다운로드처럼 사용자 환경을 크게 바꾸는
작업은 사용자가 명시적으로 수행해야 하며, 이 스크립트는 이미 준비된 로컬 API만 확인한다.
#>

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$environmentFile = Join-Path $repositoryRoot '.env'
$composeProject = 'safe-text2sql-demo'

function New-LocalEnvironmentFile {
    param([Parameter(Mandatory)][string]$Path)

    $settings = [ordered]@{
        DB_NAME = 'text2sql'
        DB_PORT = '55432'
        DB_MIGRATION_PASSWORD = 'local-migration-' + [guid]::NewGuid().ToString('N')
        APP_DB_PASSWORD = 'local-readonly-' + [guid]::NewGuid().ToString('N')
        DOCKER_HOST = 'npipe:////./pipe/docker_engine'
        SERVER_PORT = '18080'
        SQL_GENERATOR_PROVIDER = 'ollama'
        OLLAMA_BASE_URL = 'http://127.0.0.1:11434'
        OLLAMA_MODEL = 'qwen3:4b-instruct'
        LLM_TEMPERATURE = '0'
        OLLAMA_CONNECT_TIMEOUT = '2s'
        OLLAMA_READ_TIMEOUT = '120s'
    }

    # BOM 없는 UTF-8을 사용해 Docker Compose와 PowerShell이 같은 파일을 안정적으로 읽게 한다.
    $lines = $settings.GetEnumerator() | ForEach-Object { '{0}={1}' -f $_.Key, $_.Value }
    [System.IO.File]::WriteAllLines($Path, $lines, [System.Text.UTF8Encoding]::new($false))
    Write-Output '로컬 전용 .env를 생성했습니다. 이 파일은 Git에서 제외됩니다.'
}

function Import-LocalEnvironmentFile {
    param([Parameter(Mandatory)][string]$Path)

    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = $rawLine.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith('#')) {
            continue
        }

        $separator = $line.IndexOf('=')
        if ($separator -le 0) {
            throw "잘못된 .env 항목입니다. KEY=VALUE 형식인지 확인하세요."
        }

        $name = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1)
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

function Assert-CommandAvailable {
    param([Parameter(Mandatory)][string]$Name)

    if ($null -eq (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "필수 명령 '$Name'을 찾지 못했습니다. README의 로컬 요구 사항을 확인하세요."
    }
}

function Test-TcpPortAvailable {
    param([Parameter(Mandatory)][int]$Port)

    # Docker Desktop는 IPv4/IPv6 전체 주소에 포트를 게시할 수 있다. IPv4 loopback에 임시 bind하는
    # 방식만 사용하면 IPv6에서 이미 점유한 포트를 빈 포트로 오인하므로 Windows의 실제 LISTEN
    # 소켓을 모두 확인한다.
    $listeners = @(Get-NetTCPConnection `
            -LocalPort $Port `
            -State Listen `
            -ErrorAction SilentlyContinue)
    return $listeners.Count -eq 0
}

function Set-LocalEnvironmentValue {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Value
    )

    # 비밀번호를 포함한 다른 줄은 그대로 보존하고 충돌한 포트 항목만 교체한다.
    $prefix = "$Name="
    $updated = foreach ($line in Get-Content -LiteralPath $environmentFile) {
        if ($line.StartsWith($prefix, [StringComparison]::Ordinal)) {
            "$prefix$Value"
        } else {
            $line
        }
    }
    [System.IO.File]::WriteAllLines(
            $environmentFile,
            $updated,
            [System.Text.UTF8Encoding]::new($false))
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Use-AvailableLocalPort {
    param(
        [Parameter(Mandatory)][string]$VariableName,
        [Parameter(Mandatory)][int]$PreferredPort
    )

    $configuredPort = [int][Environment]::GetEnvironmentVariable($VariableName, 'Process')
    if (Test-TcpPortAvailable -Port $configuredPort) {
        return
    }

    foreach ($candidate in $PreferredPort..($PreferredPort + 50)) {
        if (Test-TcpPortAvailable -Port $candidate) {
            Set-LocalEnvironmentValue -Name $VariableName -Value $candidate.ToString()
            Write-Output ("포트 {0}이 사용 중이어서 {1}을 {2}(으)로 변경했습니다." -f `
                    $configuredPort, $VariableName, $candidate)
            return
        }
    }

    throw "$VariableName 용도로 사용할 빈 로컬 포트를 찾지 못했습니다."
}

Push-Location $repositoryRoot
try {
    if (-not (Test-Path -LiteralPath $environmentFile)) {
        New-LocalEnvironmentFile -Path $environmentFile
    }
    Import-LocalEnvironmentFile -Path $environmentFile

    Assert-CommandAvailable -Name 'java'
    Assert-CommandAvailable -Name 'docker'
    Assert-CommandAvailable -Name 'ollama'

    # Docker Desktop이 설치돼 있어도 엔진이 꺼져 있으면 뒤 단계의 오류가 길고 불명확해진다.
    $dockerVersion = & docker info --format '{{.ServerVersion}}' 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($dockerVersion)) {
        throw 'Docker 엔진에 연결할 수 없습니다. Docker Desktop을 먼저 실행하세요.'
    }

    # 이미 실행 중인 동일 demo DB는 자신이 포트를 점유하므로 그대로 재사용한다. 실행 중인 demo
    # 컨테이너가 없을 때만 충돌 여부를 검사하고 빈 포트를 자동 선택한다.
    $runningDemoContainers = @(& docker ps `
            --filter "label=com.docker.compose.project=$composeProject" `
            --filter 'status=running' `
            --format '{{.Names}}')
    if ($runningDemoContainers.Count -eq 0) {
        Use-AvailableLocalPort -VariableName 'DB_PORT' -PreferredPort 55432
    }
    Use-AvailableLocalPort -VariableName 'SERVER_PORT' -PreferredPort 18080

    try {
        $ollamaTags = Invoke-RestMethod -Uri "$env:OLLAMA_BASE_URL/api/tags" -TimeoutSec 5
    } catch {
        throw 'Ollama 로컬 API에 연결할 수 없습니다. Windows Ollama 앱을 먼저 실행하세요.'
    }

    $installedModels = @($ollamaTags.models | ForEach-Object { $_.name })
    if ($installedModels -notcontains $env:OLLAMA_MODEL) {
        throw "모델 '$env:OLLAMA_MODEL'이 없습니다. 'ollama pull $env:OLLAMA_MODEL'을 먼저 실행하세요."
    }

    Write-Output ("Docker {0}, Ollama 모델 {1}을 확인했습니다." -f $dockerVersion, $env:OLLAMA_MODEL)
    Write-Output 'PostgreSQL을 준비합니다...'
    & docker compose -p $composeProject up -d --wait postgres
    if ($LASTEXITCODE -ne 0) {
        throw 'PostgreSQL Docker Compose 실행에 실패했습니다.'
    }

    # bootRun은 애플리케이션 JVM과 Gradle JVM을 동시에 유지한다. 8GB 메모리 환경에서 Docker와
    # Ollama까지 함께 실행하면 운영체제 자원이 부족해질 수 있으므로, 먼저 실행 jar를 만든 뒤
    # Gradle을 종료하고 애플리케이션 JVM 하나만 남긴다.
    Write-Output 'Spring Boot 실행 파일을 준비합니다...'
    & .\gradlew.bat --no-daemon bootJar
    if ($LASTEXITCODE -ne 0) {
        throw 'Spring Boot 실행 파일 빌드에 실패했습니다.'
    }

    $applicationJar = Get-ChildItem -LiteralPath (Join-Path $repositoryRoot 'build\libs') `
            -Filter '*.jar' |
        Where-Object {
            # test fixture jar도 실행 가능한 manifest가 없으므로 데모 후보에서 제외한다.
            $_.Name -notlike '*-plain.jar' -and $_.Name -notlike '*-test-fixtures.jar'
        } |
        Select-Object -First 1
    if ($null -eq $applicationJar) {
        throw '실행할 Spring Boot jar를 찾지 못했습니다.'
    }

    Write-Output ''
    Write-Output '로컬 데모를 시작합니다.'
    Write-Output ("브라우저 주소: http://localhost:{0}" -f $env:SERVER_PORT)
    Write-Output '종료하려면 이 창에서 Ctrl+C를 누르세요.'
    Write-Output ''

    & java -jar $applicationJar.FullName
    if ($LASTEXITCODE -ne 0) {
        throw 'Spring Boot 실행이 실패했습니다.'
    }
} finally {
    Pop-Location
}
