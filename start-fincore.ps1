$ErrorActionPreference = "Stop"

# =========================================================
# FinCore - Single Terminal Startup
# Reads .env on every run, then starts local Java services.
# Docker infrastructure remains managed separately.
# =========================================================

$Root = "E:\fincore"
$Maven = "C:\Tools\apache-maven-3.10.0"
$EnvFile = Join-Path $Root ".env"

$Services = @(
    "identity-service",
    "customer-service",
    "account-service",
    "transaction-service",
    "ledger-service",
    "risk-service",
    "notification-service",
    "reconciliation-service",
    "api-gateway"
)

# Service -> database configuration.
# Override these defaults in .env with PREFIX_DB_NAME / PREFIX_DB_URL
# if your actual PostgreSQL database names or URLs differ.
$DbConfig = @{
    "identity-service" = @{
        Prefix = "IDENTITY"
        User = "identity_user"
        Database = "identity_db"
    }
    "customer-service" = @{
        Prefix = "CUSTOMER"
        User = "customer_user"
        Database = "customer_db"
    }
    "account-service" = @{
        Prefix = "ACCOUNT"
        User = "account_user"
        Database = "account_db"
    }
    "transaction-service" = @{
        Prefix = "TRANSACTION"
        User = "transaction_user"
        Database = "transaction_db"
    }
    "ledger-service" = @{
        Prefix = "LEDGER"
        User = "ledger_user"
        Database = "ledger_db"
    }
    "risk-service" = @{
        Prefix = "RISK"
        User = "risk_user"
        Database = "risk_db"
    }
    "notification-service" = @{
        Prefix = "NOTIFICATION"
        User = "notification_user"
        Database = "notification_db"
    }
    "reconciliation-service" = @{
        Prefix = "RECONCILIATION"
        User = "reconciliation_user"
        Database = "reconciliation_db"
    }
}

# =========================================================
# Validate paths
# =========================================================

if (!(Test-Path -LiteralPath $Root)) {
    throw "Project folder not found: $Root"
}
if (!(Test-Path -LiteralPath (Join-Path $Maven "bin\mvn.cmd"))) {
    throw "Maven not found: $Maven"
}
if (!(Test-Path -LiteralPath $EnvFile)) {
    throw ".env file not found: $EnvFile"
}

Set-Location $Root

# =========================================================
# Load .env every time this script is launched
# Supports KEY=value, quoted values, blank lines and comments.
# Splits on the first '=' so values can contain '=' characters.
# =========================================================

$EnvValues = @{}

foreach ($line in Get-Content -LiteralPath $EnvFile) {
    $line = $line.Trim()

    if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) {
        continue
    }

    $separator = $line.IndexOf("=")
    if ($separator -le 0) {
        continue
    }

    $key = $line.Substring(0, $separator).Trim()
    $value = $line.Substring($separator + 1).Trim()

    # Strip matching surrounding single or double quotes only.
    if (
    $value.Length -ge 2 -and
            (
            ($value.StartsWith('"') -and $value.EndsWith('"')) -or
                    ($value.StartsWith("'") -and $value.EndsWith("'"))
            )
    ) {
        $value = $value.Substring(1, $value.Length - 2)
    }

    if (-not [string]::IsNullOrWhiteSpace($key)) {
        $EnvValues[$key] = $value
    }
}

Write-Host "Loaded $($EnvValues.Count) variables from .env." -ForegroundColor Green

# Make .env values available to this script's process too.
# Background jobs also receive $EnvValues explicitly below.
foreach ($key in $EnvValues.Keys) {
    [Environment]::SetEnvironmentVariable(
            [string]$key,
            [string]$EnvValues[$key],
            "Process"
    )
}

# =========================================================
# Validate required settings without printing secret values
# =========================================================

$RequiredPasswordKeys = @(
    "IDENTITY_DB_PASSWORD",
    "CUSTOMER_DB_PASSWORD",
    "ACCOUNT_DB_PASSWORD",
    "TRANSACTION_DB_PASSWORD",
    "LEDGER_DB_PASSWORD",
    "RISK_DB_PASSWORD",
    "NOTIFICATION_DB_PASSWORD",
    "RECONCILIATION_DB_PASSWORD"
)

$MissingKeys = @()
foreach ($key in $RequiredPasswordKeys) {
    if (
    -not $EnvValues.ContainsKey($key) -or
            [string]::IsNullOrWhiteSpace([string]$EnvValues[$key])
    ) {
        $MissingKeys += $key
    }
}

if ($MissingKeys.Count -gt 0) {
    throw "Missing or empty .env variable(s): $($MissingKeys -join ', '). Services were not started."
}

if (
-not $EnvValues.ContainsKey("JWT_SECRET") -or
        [string]::IsNullOrWhiteSpace([string]$EnvValues["JWT_SECRET"])
) {
    throw "Missing or empty JWT_SECRET in .env. Services were not started."
}

if ([string]$EnvValues["JWT_SECRET"] -match '^(change-me|changeme|your-secret|replace-me)$') {
    throw "JWT_SECRET still appears to be a placeholder. Set a strong secret in .env."
}

# =========================================================
# Check service project files
# =========================================================

$ValidServices = @()
foreach ($service in $Services) {
    $pom = Join-Path $Root "$service\pom.xml"
    if (Test-Path -LiteralPath $pom) {
        $ValidServices += $service
    } else {
        Write-Warning "Skipping $service because pom.xml was not found: $pom"
    }
}

if ($ValidServices.Count -eq 0) {
    throw "No service pom.xml files found under $Root."
}

# =========================================================
# Stop older jobs created by this script
# =========================================================

$oldJobs = @(Get-Job -Name "FinCore-*" -ErrorAction SilentlyContinue)
if ($oldJobs.Count -gt 0) {
    Write-Host "Stopping existing FinCore jobs..." -ForegroundColor Yellow
    $oldJobs | Stop-Job -ErrorAction SilentlyContinue
    $oldJobs | Remove-Job -Force -ErrorAction SilentlyContinue
}

# =========================================================
# Build/install shared Maven modules
# =========================================================

Write-Host "Building/installing Maven modules (tests skipped)..." -ForegroundColor Cyan
& "$Maven\bin\mvn.cmd" -B -DskipTests install
if ($LASTEXITCODE -ne 0) {
    throw "Maven install failed with exit code $LASTEXITCODE. Services were not started."
}

# =========================================================
# Launch services as background jobs
# =========================================================

$StartedJobs = @()

foreach ($service in $ValidServices) {
    Write-Host "Launching $service..." -ForegroundColor Green

    $job = Start-Job `
        -Name "FinCore-$service" `
        -ArgumentList @(
        $Root,
        $Maven,
        $service,
        $EnvValues,
        $DbConfig
    ) `
        -ScriptBlock {
        param(
            $Root,
            $Maven,
            $Service,
            $EnvValues,
            $DbConfig
        )

        $ErrorActionPreference = "Stop"

        try {
            Set-Location $Root

            # Import .env into this job's process.
            foreach ($key in $EnvValues.Keys) {
                [Environment]::SetEnvironmentVariable(
                        [string]$key,
                        [string]$EnvValues[$key],
                        "Process"
                )
            }

            $env:MAVEN_HOME = $Maven
            $env:Path = "$Maven\bin;$env:Path"
            $env:KAFKA_BOOTSTRAP_SERVERS = "localhost:29092"

            # Map service-specific DB credentials to Spring Boot's
            # standard datasource variables.
            if ($DbConfig.ContainsKey($Service)) {
                $db = $DbConfig[$Service]
                $prefix = $db.Prefix

                $userKey = "${prefix}_DB_USER"
                $passwordKey = "${prefix}_DB_PASSWORD"
                $nameKey = "${prefix}_DB_NAME"
                $urlKey = "${prefix}_DB_URL"

                $dbUser = [string]$EnvValues[$userKey]
                $dbPassword = [string]$EnvValues[$passwordKey]
                $dbName = [string]$EnvValues[$nameKey]
                $dbUrl = [string]$EnvValues[$urlKey]

                if ([string]::IsNullOrWhiteSpace($dbUser)) {
                    $dbUser = $db.User
                }
                if ([string]::IsNullOrWhiteSpace($dbPassword)) {
                    throw "Missing $passwordKey in .env. $Service was not started."
                }
                if ([string]::IsNullOrWhiteSpace($dbName)) {
                    $dbName = $db.Database
                }
                if ([string]::IsNullOrWhiteSpace($dbUrl)) {
                    $dbUrl = "jdbc:postgresql://localhost:5433/$dbName"
                }

                $env:SPRING_DATASOURCE_URL = $dbUrl
                $env:SPRING_DATASOURCE_USERNAME = $dbUser
                $env:SPRING_DATASOURCE_PASSWORD = $dbPassword
                Write-Output "[$Service] Diagnostic: datasource password set = $(-not [string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_PASSWORD))"
                Write-Output "[$Service] Diagnostic: datasource username set = $(-not [string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_USERNAME))"
            }

            Write-Output "[$Service] Starting Spring Boot..."
            $pom = Join-Path $Root "$Service\pom.xml"

            & "$Maven\bin\mvn.cmd" `
                    -B `
                    -f $pom `
                    spring-boot:run 2>&1 |
                    ForEach-Object {
                        "[$Service] $_"
                    }

            if ($LASTEXITCODE -ne 0) {
                Write-Output "[$Service] Maven exited with code $LASTEXITCODE."
            }
        }
        catch {
            Write-Output "[$Service] STARTUP ERROR: $($_.Exception.Message)"
        }
    }

    $StartedJobs += $job
}

Write-Host ""
Write-Host "All service jobs launched. Logs will stream below." -ForegroundColor Cyan
Write-Host "Press Ctrl+C to stop this monitor; it will stop the FinCore jobs." -ForegroundColor Yellow
Write-Host ""

# =========================================================
# Stream all job output in this terminal until interrupted
# =========================================================

try {
    while ($true) {
        $receivedAny = $false

        foreach ($job in $StartedJobs) {
            $output = @(Receive-Job -Job $job -Keep -ErrorAction SilentlyContinue)
            if ($output.Count -gt 0) {
                $receivedAny = $true
                foreach ($item in $output) {
                    Write-Host ([string]$item)
                }
            }
        }

        # Exit if all jobs have completed.
        $runningJobs = @($StartedJobs | Where-Object { $_.State -eq "Running" })
        if ($runningJobs.Count -eq 0) {
            Write-Warning "All FinCore service jobs have exited."
            break
        }

        Start-Sleep -Milliseconds 500
    }
}
finally {
    Write-Host "Stopping FinCore service jobs..." -ForegroundColor Yellow
    foreach ($job in $StartedJobs) {
        if ($job.State -eq "Running") {
            Stop-Job -Job $job -ErrorAction SilentlyContinue
        }
        Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
    }
    Write-Host "FinCore service jobs stopped." -ForegroundColor Green
}
