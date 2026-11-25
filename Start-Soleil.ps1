param(
    [string] $Server,
    [string] $DesktopExe
)

$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$clientArgs = @()

if ($DesktopExe) {
    $client = (Resolve-Path -LiteralPath $DesktopExe).Path
} else {
    $client = Join-Path $project 'desktop/dist/win-unpacked/Soleil.exe'
    if (!(Test-Path -LiteralPath $client -PathType Leaf)) {
        $client = Join-Path $project 'desktop/node_modules/electron/dist/electron.exe'
        if (!(Test-Path -LiteralPath $client -PathType Leaf)) {
            throw 'Install the desktop dependencies and Electron runtime using the Windows instructions in README.md.'
        }
        $clientArgs = @((Join-Path $project 'desktop'))
    }
}

if (!$Server) {
    if (!(Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'The local Soleil stack requires Docker Compose. Use -Server for an existing Soleil deployment.'
    }

    if (!(Test-Path -LiteralPath (Join-Path $project '.env') -PathType Leaf)) {
        throw 'Create .env from .env.example and fill in the database password and JWT secret.'
    }

    Push-Location -LiteralPath $project
    try {
        $effectiveConfig = docker compose config --format json
        if ($LASTEXITCODE -ne 0) { throw 'Could not load the Soleil Compose configuration.' }
        $Server = ($effectiveConfig | ConvertFrom-Json).services.backend.environment.APP_ORIGIN
        Remove-Variable effectiveConfig
        docker compose up --build --wait --wait-timeout 240
        if ($LASTEXITCODE -ne 0) { throw 'The local Soleil services did not become ready.' }
    } finally {
        Pop-Location
    }
}

& $client @clientArgs "--server=$Server"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
