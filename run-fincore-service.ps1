param(
    [Parameter(Mandatory = $true)]
    [string]$Service
)

$ErrorActionPreference = "Stop"

$Root = "E:\fincore"
$Maven = "C:\Tools\apache-maven-3.10.0"

$env:MAVEN_HOME = $Maven
$env:Path = "$Maven\bin;$env:Path"
$env:KAFKA_BOOTSTRAP_SERVERS = "localhost:29092"

$jwtLine = Get-Content "$Root\.env" |
    Where-Object { $_ -match '^JWT_SECRET=' } |
    Select-Object -First 1

if (!$jwtLine) {
    throw "JWT_SECRET missing from .env"
}

$env:JWT_SECRET = (
    $jwtLine -replace '^JWT_SECRET=', ''
).Trim().Trim('"').Trim("'")

if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
    throw "JWT_SECRET is empty"
}

Set-Location $Root

$pom = Join-Path $Root "$Service\pom.xml"

if (!(Test-Path $pom)) {
    throw "POM not found: $pom"
}

Write-Output "Starting $Service..."

& "$Maven\bin\mvn.cmd" -f $pom spring-boot:run

exit $LASTEXITCODE
