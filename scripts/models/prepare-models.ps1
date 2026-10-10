param(
    # Edition to stage: full (Dina 4.5 1.2B) or lite (Dina 4.5 350M). Gradle packs android/model-assets/<edition>.
    [ValidateSet("full", "lite")][string]$Variant = "full",
    [switch]$CopyInsteadOfLink
)

# Stages the models from <repo>/models into android/model-assets/<edition>, which Gradle packs into the APK.
# Hardlinks by default so no extra disk space is used. The sizes the app checks (BrainSpec, ModelInstaller) are
# compared with the staged files, so a retrained or reconverted model cannot ship with stale numbers.

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$androidRoot = Join-Path $repoRoot "android"
$modelsRoot = Join-Path $repoRoot "models"
$assetRoot = Join-Path $androidRoot "model-assets\$Variant"
$kotlinRoot = Join-Path $androidRoot "app\src\main\java\com\kakauet\dina"

function Stage-Model([string]$Source, [string]$RelativeTarget) {
    $sourcePath = Join-Path $modelsRoot $Source
    if (-not (Test-Path -LiteralPath $sourcePath)) { throw "Falta el modelo: models\$Source (ver models/README.md)" }
    $targetPath = Join-Path $assetRoot $RelativeTarget
    New-Item -ItemType Directory -Force -Path (Split-Path $targetPath) | Out-Null
    if (Test-Path -LiteralPath $targetPath) { Remove-Item -LiteralPath $targetPath -Force }
    if (-not $CopyInsteadOfLink) {
        try {
            New-Item -ItemType HardLink -Path $targetPath -Target $sourcePath | Out-Null
            return
        } catch {
            Write-Host "Hardlink no disponible; copiando $RelativeTarget"
        }
    }
    Copy-Item -LiteralPath $sourcePath -Destination $targetPath
}

# The brain of this edition (Dina45Brain.Spec / Dina45Brain.LiteSpec) and the size the app expects of it.
$brain = if ($Variant -eq "lite") {
    @{ id = "Dina 4.5 350M"; source = "dina-4.5-350m\model-Q8_0.gguf"; target = "models\llm\dina-4.5-350m-Q8_0.gguf"; asset = "models/llm/dina-4.5-350m-Q8_0.gguf"
       quantization = "Q8_0"; base = "LiquidAI/LFM2.5-350M"; folder = "models/dina-4.5-350m"; kotlin = "LiteSpec" }
} else {
    @{ id = "Dina 4.5 1.2B"; source = "dina-4.5\model-Q4_K_M.gguf"; target = "models\llm\dina-4.5-1.2b-Q4_K_M.gguf"; asset = "models/llm/dina-4.5-1.2b-Q4_K_M.gguf"
       quantization = "Q4_K_M"; base = "LiquidAI/LFM2.5-1.2B-Instruct"; folder = "models/dina-4.5"; kotlin = "Spec" }
}
$brainSource = Get-Content -Raw -Encoding UTF8 (Join-Path $kotlinRoot "brain\dina45\Dina45Brain.kt")
if ($brainSource -notmatch ("object $($brain.kotlin) : BrainSpec[\s\S]*?assetPath = ""$([regex]::Escape($brain.asset))""[\s\S]*?bytes = ([\d_]+)L")) {
    throw "Dina45Brain.$($brain.kotlin) no apunta a $($brain.asset): actualiza el codigo o este script"
}
$expectedBytes = [int64]($Matches[1] -replace "_", "")

Get-ChildItem (Join-Path $assetRoot "models\llm") -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne (Split-Path $brain.target -Leaf) } | Remove-Item
Stage-Model $brain.source $brain.target
$llmLength = (Get-Item (Join-Path $assetRoot $brain.target)).Length
if ($llmLength -ne $expectedBytes) { throw "$($brain.target) mide $llmLength bytes y Dina45Brain.$($brain.kotlin) espera ${expectedBytes}: actualiza ModelSpec.bytes" }

Stage-Model "voice\moonshine-base-es\encoder_model.ort" "models\stt\base-es\encoder_model.ort"
Stage-Model "voice\moonshine-base-es\decoder_model_merged.ort" "models\stt\base-es\decoder_model_merged.ort"
Stage-Model "voice\moonshine-base-es\tokenizer.bin" "models\stt\base-es\tokenizer.bin"
Stage-Model "voice\openwakeword\melspectrogram.onnx" "models\wake\melspectrogram.onnx"
Stage-Model "voice\openwakeword\embedding_model.onnx" "models\wake\embedding_model.onnx"
Get-ChildItem (Join-Path $assetRoot "models\wake") -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notin @("melspectrogram.onnx", "embedding_model.onnx", "dina_wakeword_head.onnx") } | Remove-Item
Stage-Model "wakeword\dina_wakeword_head.onnx" "models\wake\dina_wakeword_head.onnx"
Stage-Model "voice\piper-android\es_ES-sharvard-medium.ort" "tts\es_es\piper-voices\es_ES-sharvard-medium.ort"
Stage-Model "voice\piper-android\es_ES-sharvard-medium.onnx.json" "tts\es_es\piper-voices\es_ES-sharvard-medium.onnx.json"

# Supertonic 3 (Hugging Face Supertone/supertonic-3 @ 724fb5ab…; OpenRAIL-M, its LICENSE travels with it), prepared by
# scripts/tts/quantize_supertonic.py (.\dev.ps1 tts-models): 8-bit weights, 1x1 convolutions as MatMul, the guidance
# split out of the estimator (guidance.bin). The fp32 original is in models/tts/supertonic3-fp32.
$supertonic = Join-Path $modelsRoot "tts\supertonic3"
if (-not (Test-Path (Join-Path $supertonic "onnx\guidance.bin"))) {
    throw "models\tts\supertonic3 no esta preparado para la app 2.4 (falta onnx\guidance.bin): ejecuta .\dev.ps1 tts-models"
}
Remove-Item -LiteralPath (Join-Path $assetRoot "tts\supertonic3-v2") -Recurse -ErrorAction SilentlyContinue
foreach ($file in @("onnx\duration_predictor.onnx", "onnx\text_encoder.onnx", "onnx\vector_estimator.onnx", "onnx\vocoder.onnx", "onnx\guidance.bin",
        "onnx\tts.json", "onnx\unicode_indexer.json", "voice_styles\F2.json", "LICENSE")) {
    Stage-Model "tts\supertonic3\$file" "tts\supertonic3-v2\$file"
}
# ModelInstaller.SUPERTONIC lists the sizes the app expects: the copy is skipped when they match.
$installerSource = Get-Content -Raw -Encoding UTF8 (Join-Path $kotlinRoot "models\ModelInstaller.kt")
foreach ($m in [regex]::Matches($installerSource, '"((?:onnx|voice_styles)/[^"]+|LICENSE)" to ([\d_]+)L')) {
    $staged = Join-Path $assetRoot ("tts\supertonic3-v2\" + ($m.Groups[1].Value -replace "/", "\"))
    $want = [int64]($m.Groups[2].Value -replace "_", "")
    if ((Get-Item $staged).Length -ne $want) { throw "$($m.Groups[1].Value) mide $((Get-Item $staged).Length) bytes y ModelInstaller espera ${want}: actualiza ModelInstaller.SUPERTONIC" }
}

$manifest = [ordered]@{
    edition = $Variant
    slm = @(
        [ordered]@{
            id = $brain.id
            default = $true
            file = $brain.asset
            quantization = $brain.quantization
            sha256 = (Get-FileHash (Join-Path $assetRoot $brain.target) -Algorithm SHA256).Hash.ToLowerInvariant()
            base = $brain.base
            source = $brain.folder
        }
    )
    stt = [ordered]@{ id = "Moonshine Spanish Base"; file = "models/stt/base-es" }
    tts = @(
        [ordered]@{ id = "Supertonic 3 F2"; default = $true; dir = "tts/supertonic3-v2"; weights = "int8 + 1x1 as MatMul + guidance.bin (scripts/tts/quantize_supertonic.py)"; repo = "Supertone/supertonic-3"; revision = "724fb5abbf5502583fb520898d45929e62f02c0b"; license = "OpenRAIL-M" },
        [ordered]@{ id = "Piper Sharvard Medium"; voice = "piper_es_ES-sharvard-medium"; speaker = 1 }
    )
    wakeWord = [ordered]@{ head = "models/wake/dina_wakeword_head.onnx"; frontEnd = "openWakeWord 0.6 (melspectrogram + embedding)"; license = "CC BY-NC-SA 4.0" }
} | ConvertTo-Json -Depth 5
$utf8 = New-Object System.Text.UTF8Encoding($false)
New-Item -ItemType Directory -Force -Path (Join-Path $assetRoot "models") | Out-Null
[System.IO.File]::WriteAllText((Join-Path $assetRoot "models\manifest.json"), $manifest, $utf8)
Write-Host "Modelos ($Variant) preparados en $assetRoot"
