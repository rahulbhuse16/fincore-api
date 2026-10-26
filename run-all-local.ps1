
$ErrorActionPreference = "Stop"

$Root = "E:\fincore"
$Jdk = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"

$services = @(
    @{ Name = "identity-service";        Port = 8081 },
    @{ Name = "customer-service";        Port = 8082 },
    @{ Name = "account-service";         Port = 8083 },
    @{ Name = "transaction-service";     Port = 8084 },
    @{ Name = "ledger-service";          Port = 8085 },
    @{ Name = "risk-service";            Port = 8086 },
    @{ Name = "notification-service";    Port = 8087 },
    @{ Name = "reconciliation-service";  Port = 8088 },
    @{ Name = "api-gateway";             Port = 8080 }
)

if (!(Test-Path "$Jdk\bin\java.exe")) {
    throw "Java 21 not found at $Jdk"
}

Set-Location $Root
$env:JAVA_HOME = $Jdk
$env:Path = "$Jdk\bin;$env:Path"

# Find IntelliJ's bundled Maven
$ideRoots = @()

foreach ($base in @(
    "$env:ProgramFiles\JetBrains",
    "$env:LOCALAPPDATA\Programs"
)) {
    if (Test-Path $base) {
        $ideRoots += Get-ChildItem $base -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like "*IntelliJ IDEA*" }
    }
}

$mvn = $null

foreach ($ide in $ideRoots) {
    $candidate = Join-Path $ide.FullName `
        "plugins\maven\lib\maven3\bin\mvn.cmd"

    if (Test-Path $candidate) {
        $mvn = $candidate
        break
    }
}

if (!$mvn) {
    throw "Could not find bundled Maven. Locate IntelliJ's Maven home and update this script with its bin\mvn.cmd path."
}

Write-Host "Java 21: $(& "$Jdk\bin\java.exe" -version 2>&1 | Select-Object -First 1)"
Write-Host "Maven: $mvn"

# Build the root project first
Write-Host "`nBuilding FinCore..."
& $mvn -f "$Root\pom.xml" -DskipTests install

if ($LASTEXITCODE -ne 0) {
    throw "Build failed. Fix the build error before launching services."
}

# Read resolved environment settings from Docker Compose.
# This lets us reuse the existing database credentials and service settings.
$configText = docker compose config --format json

if ($LASTEXITCODE -ne 0) {
    throw "Could not read Docker Compose configuration."
}

$config = $configText | ConvertFrom-Json

function Quote-PSString([string]$Value) {
    return "'" + $Value.Replace("'", "''") + "'"
}

$appNames = @($services | ForEach-Object { $_.Name })

foreach ($service in $services) {
    $name = $service.Name
    $pom = Join-Path $Root "$name\pom.xml"

    if (!(Test-Path $pom)) {
        Write-Warning "Skipping $name; its pom.xml was not found."
        continue
    }

    $composeService = $config.services.PSObject.Properties[$name]

    if (!$composeService) {
        Write-Warning "No Compose configuration for $name; using application defaults."
        $serviceEnv = @{}
    } else {
        $serviceEnv = @{}
        $environment = $composeService.Value.environment

        if ($environment) {
            foreach ($property in $environment.PSObject.Properties) {
                if ($null -ne $property.Value) {
                    $serviceEnv[$property.Name] = [string]$property.Value
                }
            }
        }
    }

    # Change Docker-only hostnames to addresses accessible from Windows.
    foreach ($key in @($serviceEnv.Keys)) {
        $value = [string]$serviceEnv[$key]

        $value = $value.Replace("postgres:5432", "localhost:5433")
        $value = $value.Replace("kafka:9092", "localhost:29092")
        $value = [regex]::Replace(
            $value,
            '(?<=://)redis(?=:\d+)',
            'localhost'
        )

        foreach ($appName in $appNames) {
            $escaped = [regex]::Escape($appName)
            $value = [regex]::Replace(
                $value,
                "(?<=://)$escaped(?=:\d+)",
                "localhost"
            )
        }

        $serviceEnv[$key] = $value
    }

    # Supply the Windows-accessible database URL if Compose did not define one.
    if (!$serviceEnv.ContainsKey("SPRING_DATASOURCE_URL")) {
        $yaml = Join-Path $Root "$name\src\main\resources\application.yml"

        if (Test-Path $yaml) {
            $content = Get-Content $yaml -Raw
            $match = [regex]::Match(
                $content,
                '(?m)^\s*url:\s*\$\{SPRING_DATASOURCE_URL:([^}]+)\}'
            )

            if ($match.Success) {
                $dbUrl = $match.Groups[1].Value
                $dbUrl = $dbUrl.Replace("localhost:5432", "localhost:5433")
                $dbUrl = $dbUrl.Replace("postgres:5432", "localhost:5433")
                $serviceEnv["SPRING_DATASOURCE_URL"] = $dbUrl
            }
        }
    }

    $serviceEnv["SERVER_PORT"] = [string]$service.Port

    # Build a separate child PowerShell command for this service.
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("Set-Location $(Quote-PSString $Root)")
    $lines.Add("`$env:JAVA_HOME = $(Quote-PSString $Jdk)")
    $lines.Add("`$env:Path = $(Quote-PSString "$Jdk\bin;") + `$env:Path")
    $lines.Add("`$env:MAVEN_OPTS = '-Xms32m -Xmx160m'")

    foreach ($key in $serviceEnv.Keys) {
        $lines.Add("`$env:${key} = $(Quote-PSString ([string]$serviceEnv[$key]))")
    }

    $lines.Add(
        "& $(Quote-PSString $mvn) -f $(Quote-PSString $pom) " +
        "'-Dspring-boot.run.jvmArguments=-Xms32m -Xmx256m' spring-boot:run"
    )

    $childScript = $lines -join "`r`n"
    $encoded = [Convert]::ToBase64String(
        [Text.Encoding]::Unicode.GetBytes($childScript)
    )

    Write-Host "Launching $name on port $($service.Port)..."

    Start-Process powershell.exe -ArgumentList @(
        "-NoExit",
        "-ExecutionPolicy", "Bypass",
        "-EncodedCommand", $encoded
    )

    Start-Sleep -Seconds 2
}

Write-Host "`nLaunch commands submitted."
Write-Host "Check each service window for startup errors and readiness."
