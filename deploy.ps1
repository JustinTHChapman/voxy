# deploy.ps1 — Build voxy and update only the jar in the .minecraft repo
# Usage: .\deploy.ps1
# Does NOT merge unrelated .minecraft changes (configs, mods, saves).

$ErrorActionPreference = "Stop"

$MC = "$env:APPDATA\PrismLauncher\instances\World of Titans\.minecraft"

Write-Host "Building jar..."
& .\gradlew.bat build --no-daemon | Select-Object -Last 5
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

$jar = Get-ChildItem "build\libs" -Filter "voxy-neoforge-*.jar" |
       Where-Object { $_.Name -notmatch "sources|all" } |
       Select-Object -First 1
if (-not $jar) { throw "No jar found in build/libs" }
Write-Host "Jar: $($jar.Name)"

# Update local mods folder
Remove-Item "$MC\mods\voxy-neoforge-*.jar" -ErrorAction SilentlyContinue
Copy-Item $jar.FullName "$MC\mods\"

# Sync only the voxy jar into the .minecraft git index (no pull, no merge)
Push-Location $MC
try {
    git fetch origin --quiet
    # Stage the new jar and remove the old ones from git index
    git rm --cached --ignore-unmatch "mods/voxy-neoforge-*.jar" --quiet 2>$null
    git add "mods/$($jar.Name)"
    git diff --cached --quiet 2>$null
    $hasStagedChanges = ($LASTEXITCODE -ne 0)
    if ($hasStagedChanges) {
        git commit -m "chore(mods): update voxy to $($jar.Name)"
        git push
        Write-Host "Pushed to .minecraft repo."
    } else {
        Write-Host "Jar unchanged, nothing to commit."
    }
} finally {
    Pop-Location
}

Write-Host "Done. Launch with: prismlauncher.exe --launch 'World of Titans'"
