param(
    [string]$ServerDir = "..\server",
    [string]$OutputPluginName = "LiteCookingMakerBridge.jar"
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$SourceDir = Join-Path $ProjectRoot "src\main\java"
$ResourceDir = Join-Path $ProjectRoot "src\main\resources"
$BuildDir = Join-Path $ProjectRoot "build"
$ClassesDir = Join-Path $BuildDir "classes"
$OutputJar = Join-Path $BuildDir "LiteCookingMakerBridge-1.0.0.jar"

$ServerPath = Resolve-Path (Join-Path $ProjectRoot $ServerDir)
$ApiJar = (Get-ChildItem -Path (Join-Path $ServerPath "libraries\org\purpurmc\purpur\purpur-api") -Filter "purpur-api-*.jar" -File -Recurse -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
$LiteCookingJar = (Get-ChildItem -Path (Join-Path $ServerPath "plugins") -Filter "*LiteCooking-*.jar" -File | Select-Object -First 1).FullName
$AdventureApiJar = (Get-ChildItem -Path (Join-Path $ServerPath "libraries\net\kyori\adventure-api") -Filter "adventure-api-*.jar" -File -Recurse -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
$AdventureKeyJar = (Get-ChildItem -Path (Join-Path $ServerPath "libraries\net\kyori\adventure-key") -Filter "adventure-key-*.jar" -File -Recurse -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName

if ([string]::IsNullOrWhiteSpace($ApiJar) -or !(Test-Path $ApiJar)) {
    throw "Missing API jar: $ApiJar"
}
if ([string]::IsNullOrWhiteSpace($LiteCookingJar) -or !(Test-Path $LiteCookingJar)) {
    throw "Missing LiteCooking jar: $LiteCookingJar"
}
if ([string]::IsNullOrWhiteSpace($AdventureApiJar) -or !(Test-Path $AdventureApiJar)) {
    throw "Missing Adventure API jar: $AdventureApiJar"
}
if ([string]::IsNullOrWhiteSpace($AdventureKeyJar) -or !(Test-Path $AdventureKeyJar)) {
    throw "Missing Adventure Key jar: $AdventureKeyJar"
}

New-Item -ItemType Directory -Path $ClassesDir -Force | Out-Null
Get-ChildItem -Path $ClassesDir -Force -Recurse -ErrorAction SilentlyContinue | Remove-Item -Force -Recurse -ErrorAction SilentlyContinue

$JavaFiles = @(Get-ChildItem -Path $SourceDir -Filter *.java -File -Recurse | Select-Object -ExpandProperty FullName)
if ($JavaFiles.Count -eq 0) {
    throw "No Java source files found in: $SourceDir"
}

$Classpath = @($ApiJar, $LiteCookingJar, $AdventureApiJar, $AdventureKeyJar) -join ";"
javac --release 21 -encoding UTF-8 -cp $Classpath -d $ClassesDir $JavaFiles
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

Copy-Item -Path (Join-Path $ResourceDir "*") -Destination $ClassesDir -Recurse -Force

New-Item -ItemType Directory -Path $BuildDir -Force | Out-Null
if (Test-Path $OutputJar) {
    Remove-Item -Path $OutputJar -Force
}
jar --create --file $OutputJar -C $ClassesDir .
if ($LASTEXITCODE -ne 0) {
    throw "jar packaging failed with exit code $LASTEXITCODE"
}

$PluginOutputPath = Join-Path $ServerPath ("plugins\" + $OutputPluginName)
Copy-Item -Path $OutputJar -Destination $PluginOutputPath -Force

Write-Host "Build completed: $OutputJar"
Write-Host "Copied to: $PluginOutputPath"
