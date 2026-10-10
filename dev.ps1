<#
Dina development entry point. Output is deliberately short: one summary line on success,
only the relevant errors on failure (full Gradle log: android/build/dev-last.log).

  .\dev.ps1 test           JVM unit tests -> "OK <n> tests" or the failing tests (the full edition; lite shares the code)
  .\dev.ps1 check          compile Kotlin only (fastest feedback)
  .\dev.ps1 build [lite]   debug APK without models (~86 MB); editions: full (default, Dina 4.5 1.2B) and lite (350M)
  .\dev.ps1 install [lite] build + install debug APK on the connected phone
  .\dev.ps1 push-models [lite]  copy models to the phone once (needed by debug APKs)
  .\dev.ps1 tts-models [dq8|nbits8]  prepare Supertonic for the app (8-bit, 1x1 as MatMul, guidance split) -> models\tts\supertonic3
  .\dev.ps1 wake-synthesize | wake-train | wake-calibrate | wake-eval  detector tools (scripts\wakeword)
  .\dev.ps1 run [lite]     open the app on the phone
  .\dev.ps1 logs [-Lines N] recent Dina logs and crashes from the phone
  .\dev.ps1 llm-bench      PC benchmark of the LLM path with the real model
  .\dev.ps1 keystore       create the release signing key once -> ~\.dina\ (back that folder up)
  .\dev.ps1 apk [full|lite]  release APKs with models, signed with that key -> dist\Dina-<version>.apk and dist\Dina-Lite-<version>.apk
  .\dev.ps1 screenshots    render UI and character PNGs on the PC -> screenshots\ (git-ignored)
  .\dev.ps1 icons          redraw the launcher icons (Brote, Musgo) from the character code
  .\dev.ps1 eval [brain] [set] [k=v]  evaluate a brain on the PC -> eval-results\<set>\<brain>\report.md
  .\dev.ps1 latency [k=v]  transcript -> first audio by stage on the PC -> eval-results\latency\<tag>.md
  .\dev.ps1 data <step> batch=<name>  Kotlin steps of the data pipeline -> data\batches\<batch>\
  .\dev.ps1 train [k=v]    LoRA training in WSL + GGUF quantization -> models\train-<base>\ (or out=)

PC tools (llm-bench, eval, latency, train) also need w64devkit (gcc; set W64DEVKIT if it is not in
C:\w64devkit) and the Android SDK's CMake; train needs WSL with a conda env `ml` (see README.md).
#>
param(
    [Parameter(Position = 0)][string]$Command = "help",
    [int]$Lines = 60,
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Rest = @()
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Android = Join-Path $Root "android"
$Log = Join-Path $Android "build\dev-last.log"
# Editions (Gradle product flavors): full = Dina 4.5 1.2B, lite = Dina 4.5 350M (applicationId .lite). A word "lite" or
# "full" among the arguments selects one for build, install, run, push-models and apk.
$Variant = if ($Rest -contains "lite") { "lite" } else { "full" }
$VariantGiven = ($Rest -contains "lite") -or ($Rest -contains "full")
$Cap = (Get-Culture).TextInfo.ToTitleCase($Variant)
$Package = if ($Variant -eq "lite") { "com.kakauet.dina.lite" } else { "com.kakauet.dina" }
$RemoteModels = "/sdcard/Android/data/$Package/files/models"
if (-not $env:JAVA_HOME) {
    # Any JDK 17 in the usual install folders (Microsoft, Temurin); otherwise Gradle uses java from PATH.
    $jdk = Get-ChildItem "$env:ProgramFiles\Microsoft", "$env:ProgramFiles\Eclipse Adoptium" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$Adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
$W64 = if ($env:W64DEVKIT) { $env:W64DEVKIT } else { "C:\w64devkit" }

function Fail([string]$Message) { Write-Host "FAIL $Message"; exit 1 }

function Invoke-Gradle([string[]]$Tasks) {
    New-Item -ItemType Directory -Force -Path (Split-Path $Log) | Out-Null
    Push-Location $Android
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue" # Gradle writes warnings to stderr.
    try {
        # cmd does the redirection: PowerShell 5.1 would wrap and re-encode native stderr lines.
        $arguments = (@($Tasks) + @("--console=plain", "--warning-mode=none") | ForEach-Object { '"' + $_ + '"' }) -join ' '
        & cmd.exe /d /c "cd /d `"$Android`" && .\gradlew.bat $arguments > `"$Log`" 2>&1"
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
        Pop-Location
    }
    if ($code -ne 0) {
        $text = @(Get-Content $Log)
        # Kotlin prints "e: <file>:<line>:<col>" and the message on the following line(s).
        $errors = @()
        for ($i = 0; $i -lt $text.Count -and $errors.Count -lt 25; $i++) {
            $line = $text[$i]
            if ($line -match '^e: ') {
                if ($line -match '^e:\s*$' -and $i + 1 -lt $text.Count) { $i++; $line = "e: " + $text[$i] }
                if ($line -match ':\d+:\d+\s*$' -and $i + 1 -lt $text.Count) { $i++; $line = "$line$($text[$i])" }
                $errors += $line
            } elseif ($line -match 'error:|FAILED') { $errors += $line }
        }
        $cause = ($text | Select-String -Pattern 'What went wrong' -Context 0, 4 | Select-Object -First 1)
        if ($errors.Count -eq 0) { $errors = $text | Select-Object -Last 8 }
        $errors | ForEach-Object { Write-Host ($_ -replace [regex]::Escape("file:///" + ($Root -replace '\\', '/') + "/"), "") }
        if ($cause) { $cause.Context.PostContext | ForEach-Object { Write-Host $_ } }
        Fail "gradle $($Tasks -join ' ') (full log: android/build/dev-last.log)"
    }
}

function Show-TestSummary {
    $dir = Join-Path $Android "app\build\test-results\testFullDebugUnitTest"
    $total = 0; $failed = @()
    Get-ChildItem $dir -Filter "*.xml" -ErrorAction SilentlyContinue | ForEach-Object {
        [xml]$xml = Get-Content $_.FullName
        $suite = $xml.testsuite
        $total += [int]$suite.tests
        foreach ($case in $suite.testcase) {
            $failure = $case.failure
            if (-not $failure) { $failure = $case.error }
            if ($failure) {
                $short = $suite.name -replace '^com\.kakauet\.dina\.', ''
                $message = ($failure.message -split "`n")[0]
                $failed += "  $short.$($case.name): $message"
            }
        }
    }
    if ($failed.Count -gt 0) { $failed | ForEach-Object { Write-Host $_ }; Fail "$($failed.Count) of $total tests" }
    Write-Host "OK $total tests"
}

function Get-Apk([switch]$Release, [string]$Edition = $Variant) {
    $type = if ($Release) { "release" } else { "debug" }
    $apk = Join-Path $Android "app\build\outputs\apk\$Edition\$type\app-$Edition-$type.apk"
    if (-not (Test-Path $apk)) { Fail "no $Edition $type APK; run .\dev.ps1 build $Edition" }
    return $apk
}

function Assert-Device {
    if (-not (Test-Path $Adb)) { Fail "adb not found at $Adb" }
    $devices = & $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' }
    if (-not $devices) { Fail "no phone connected (enable USB debugging and run: adb devices)" }
}

# Builds [Target] of android\tools\llm-bench for the PC (the app's llama.cpp, PromptCache and JNI); returns the build folder.
function Build-Native([string]$Target) {
    $cmakeBin = Get-ChildItem (Join-Path $env:ANDROID_HOME "cmake") -Directory | Sort-Object Name -Descending | Select-Object -First 1
    $env:PATH = "$(Join-Path $cmakeBin.FullName 'bin');$W64\bin;$env:PATH"
    $buildDir = Join-Path $Android "build\llm-bench"
    New-Item -ItemType Directory -Force -Path (Split-Path $Log) | Out-Null
    $previous = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    if (-not (Test-Path (Join-Path $buildDir "build.ninja"))) {
        cmake -S (Join-Path $Android "tools\llm-bench") -B $buildDir -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=gcc -DCMAKE_CXX_COMPILER=g++ *> $Log
    }
    cmake --build $buildDir --target $Target *>> $Log
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0) { Fail "$Target build (log: android/build/dev-last.log)" }
    return $buildDir
}

# Release signing key (read by android\app\build.gradle.kts): DINA_SIGNING or ~\.dina\release.properties.
$Signing = if ($env:DINA_SIGNING) { $env:DINA_SIGNING } else { Join-Path $env:USERPROFILE ".dina\release.properties" }

function Get-BuildTool([string]$Name) {
    $tools = Get-ChildItem (Join-Path $env:ANDROID_HOME "build-tools") -Directory -ErrorAction SilentlyContinue | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    $tool = if ($tools) { Join-Path $tools.FullName $Name }
    if (-not $tool -or -not (Test-Path $tool)) { Fail "$Name not found in $env:ANDROID_HOME\build-tools" }
    return $tool
}

function Get-VersionName {
    $line = Select-String -Path (Join-Path $Android "app\build.gradle.kts") -Pattern 'versionName = "([^"]+)"' | Select-Object -First 1
    return $line.Matches[0].Groups[1].Value
}

switch ($Command) {
    "test" {
        Invoke-Gradle @(":app:testFullDebugUnitTest")
        Show-TestSummary
    }
    "check" {
        Invoke-Gradle @(":app:compileFullDebugKotlin", ":app:compileFullDebugUnitTestKotlin")
        Write-Host "OK compiled"
    }
    "build" {
        Invoke-Gradle @(":app:assemble${Cap}Debug")
        $apk = Get-Apk
        Write-Host ("OK {0} ({1:N0} MB)" -f ($apk.Substring($Root.Length + 1)), ((Get-Item $apk).Length / 1MB))
    }
    "install" {
        Assert-Device
        Invoke-Gradle @(":app:assemble${Cap}Debug")
        $output = & $Adb install -r (Get-Apk) 2>&1
        if ($LASTEXITCODE -ne 0) { Fail ($output | Select-Object -Last 3 | Out-String).Trim() }
        Write-Host "OK installed $Variant debug build (models: .\dev.ps1 push-models $Variant)"
    }
    "push-models" {
        Assert-Device
        & (Join-Path $Root "scripts\models\prepare-models.ps1") -Variant $Variant | Out-Null
        $assetRoot = Join-Path $Android "model-assets\$Variant"
        $pushed = 0; $skipped = 0
        Get-ChildItem $assetRoot -Recurse -File | Where-Object { $_.Name -ne "manifest.json" } | ForEach-Object {
            $relative = $_.FullName.Substring($assetRoot.Length + 1).Replace('\', '/') -replace '^models/', ''
            $remote = "$RemoteModels/$relative"
            $size = "$(& $Adb shell "stat -c %s '$remote' 2>/dev/null")".Trim()
            # A retrained wake head keeps its name and its length: always copy it.
            $isWakeHead = $relative -eq 'wake/dina_wakeword_head.onnx'
            if ($size -eq [string]$_.Length -and -not $isWakeHead) { $skipped++; return }
            $remoteDir = $remote.Substring(0, $remote.LastIndexOf('/'))
            & $Adb shell "mkdir -p '$remoteDir'" | Out-Null
            $output = & $Adb push $_.FullName $remote 2>&1
            if ($LASTEXITCODE -ne 0) { Fail "push $relative`: $(($output | Select-Object -Last 1))" }
            $pushed++
        }
        Write-Host "OK $Variant models on phone ($pushed copied, $skipped already there)"
    }
    "run" {
        Assert-Device
        # The enabled launcher alias (Brote or Musgo); MainActivity itself is not exported.
        & $Adb shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
        Write-Host "OK started"
    }
    { $_ -in @("wake-synthesize", "wake-train", "wake-calibrate", "wake-eval") } {
        # The "Dina" detector's tools (scripts\wakeword\README.md), with the Python of .venv-wakeword.
        $python = Join-Path $Root ".venv-wakeword\Scripts\python.exe"
        if (-not (Test-Path -LiteralPath $python)) { Fail "create .venv-wakeword with scripts/wakeword/requirements.txt first" }
        $table = @{
            "wake-synthesize" = @("synthesize.py", "synthesize_speech.py", "import_public.py"); "wake-train" = @("train.py")
            "wake-calibrate" = @("evaluate.py calibrate"); "wake-eval" = @("evaluate.py test")
        }
        if ($Command -eq "wake-synthesize") { & (Join-Path $Root "scripts\wakeword\synthesize_windows.ps1") }
        Push-Location $Root
        try {
            foreach ($step in $table[$Command]) {
                $parts = @($step -split ' ')
                & $python (Join-Path $Root "scripts\wakeword\$($parts[0])") @($parts | Select-Object -Skip 1) @Rest
                if ($LASTEXITCODE -ne 0) { Fail "$Command failed ($($parts[0]))" }
            }
        } finally { Pop-Location }
    }
    "logs" {
        Assert-Device
        & $Adb logcat -d -v brief "DinaService:V" "DinaVoice:V" "DinaLLM:W" "DinaInit:V" "AndroidRuntime:E" "*:S" | Select-Object -Last $Lines
    }
    "llm-bench" {
        # Desktop run of the app's llama.cpp + PromptCache on the real model (no phone needed).
        $buildDir = Build-Native "llm_bench"
        & (Join-Path $buildDir "llm_bench.exe") (Join-Path $Root "models\dina-4.5\model-Q4_K_M.gguf") 6
        if ($LASTEXITCODE -ne 0) { Fail "prompt cache is not exact" }
    }
    "eval" {
        # The app's Brain + ToolEngine + llama.cpp/PromptCache (JNI built for the PC) on a benchmark.
        # brain: dina45 (default; model=<gguf> for another one, grammar=off, history=0|1), oracle,
        #        dina45-scripted (real Dina 4.5 brain + a model scripted from the oracle; tokens=<gguf>|none)
        # set: v2 (default, RW2 v2.1), conv (its conversions section), rw200, dina-real, dev:<batch>[+<batch2>] or a folder of episodes;
        # options: filter=, limit=, policy=ampm:mixed+bulk:confirm, model=, threads=, tag=
        $brain = if ($Rest.Count -gt 0) { $Rest[0] } else { "dina45" }
        $set = if ($Rest.Count -gt 1) { $Rest[1] } else { "v2" }
        if ($brain -ne "oracle") { Build-Native "dina_native" | Out-Null }
        $spec = (@($brain, $set) + @($Rest | Select-Object -Skip 2)) -join ","
        Invoke-Gradle @(":app:testFullDebugUnitTest", "-Pdina.eval=$spec")
        Get-Content -Encoding UTF8 (Join-Path $Root "eval-results\last.txt") | ForEach-Object { Write-Host "OK $_" }
    }
    "latency" {
        # The app's turn path (controller, brain, ToolEngine, llama.cpp DLL) + Supertonic (desktop ORT), stage by stage.
        # Options: model=<gguf> warm=1|0 stream=1|0 steps=3 voice_threads=4 (0 = ORT default) llm_threads=6 reps=2 tag=<name>
        Build-Native "dina_native" | Out-Null
        Invoke-Gradle @(":app:testFullDebugUnitTest", "-Pdina.latency=$($Rest -join ',')")
        Get-Content -Encoding UTF8 (Join-Path $Root "eval-results\last.txt") | ForEach-Object { Write-Host "OK $_" }
    }
    "data" {
        # Kotlin steps of the data pipeline; the orchestrator is scripts\data\pipeline.py.
        if ($Rest.Count -lt 1) { Fail "usage: .\dev.ps1 data <scenarios|verify|render> batch=<name> [n=200 seed=1 dev=0.1]" }
        Invoke-Gradle @(":app:testFullDebugUnitTest", "-Pdina.data=$($Rest -join ',')")
        Get-Content -Encoding UTF8 (Join-Path $Root "data\batches\last.txt") | ForEach-Object { Write-Host "OK $_" }
    }
    "train" {
        # LoRA SFT in WSL (env ml), merged and exported to GGUF F16, then quantized here with the app's llama.cpp.
        # Options: data=data/batches/<batch>[+data/batches/<batch2>] base=1p2b|350m epochs=2 lr=2e-4 rank=32 weights=1+2 dedup=1 full=1 limit=N history=0 quants=Q4_K_M+Q8_0 smoke=1 out=<dir>
        $opts = @{}; foreach ($kv in $Rest) { $k, $v = $kv -split "=", 2; $opts[$k] = $v }
        $base = if ($opts["base"]) { $opts["base"] } else { "1p2b" }
        $quants = if ($opts["quants"]) { $opts["quants"] } elseif ($base -eq "350m") { "Q8_0,Q6_K" } else { "Q4_K_M,Q5_K_M,Q8_0" }
        $out = if ($opts["out"]) { $opts["out"] } else { "models/train-$base" + $(if ($opts["smoke"]) { "-smoke" } else { "" }) }

        $trainArgs = @("--base", $base, "--out", $out)
        if ($opts["data"]) { $trainArgs += @("--data", $opts["data"]) }
        if ($opts["epochs"]) { $trainArgs += @("--epochs", $opts["epochs"]) }
        if ($opts.ContainsKey("lr")) { $trainArgs += @("--lr", $opts["lr"]) }
        if ($opts.ContainsKey("rank")) { $trainArgs += @("--rank", $opts["rank"]) }
        if ($opts.ContainsKey("weights")) { $trainArgs += @("--weights", $opts["weights"]) }
        if ($opts["dedup"] -eq "1") { $trainArgs += "--dedup" }
        if ($opts["prepare_only"] -eq "1") { $trainArgs += "--prepare-only" }
        if ($opts["smoke"]) { $trainArgs += "--smoke" }
        if ($opts["full"]) { $trainArgs += "--full" }
        if ($opts["limit"]) { $trainArgs += @("--limit", $opts["limit"]) }
        if ($opts["history"]) { $trainArgs += @("--history", $opts["history"]) }
        $linuxRoot = "/mnt/" + $Root.Substring(0, 1).ToLower() + ($Root.Substring(2) -replace "\\", "/")
        $cmd = "cd '$linuxRoot' && ~/miniconda3/envs/ml/bin/python scripts/train/train.py $($trainArgs -join ' ')"
        $previous = $ErrorActionPreference; $ErrorActionPreference = "Continue" # Python warnings go to stderr
        wsl.exe -d Ubuntu-22.04 -- bash -lc $cmd
        $code = $LASTEXITCODE; $ErrorActionPreference = $previous
        if ($code -ne 0) { Fail "training (see the output above)" }
        if ($opts["prepare_only"] -eq "1") { break }
        $f16 = Join-Path $Root (Join-Path $out "model-f16.gguf")
        $quantize = Join-Path $Android "build\llama-tools\bin\llama-quantize.exe"
        $env:PATH = "$W64\bin;$env:PATH" # its runtime DLLs
        foreach ($q in ($quants -split "[+,]")) {
            $target = Join-Path $Root (Join-Path $out "model-$q.gguf")
            $previous = $ErrorActionPreference; $ErrorActionPreference = "Continue" # it logs to stderr
            & $quantize $f16 $target $q *> (Join-Path $Root (Join-Path $out "quantize-$q.log"))
            $ErrorActionPreference = $previous
            if ($LASTEXITCODE -ne 0) { Fail "llama-quantize $q (log: $out/quantize-$q.log)" }
            Write-Host "OK $target"
        }
    }
    "keystore" {
        # One key for every release: Android only installs an update signed with the same key.
        if (Test-Path $Signing) { Write-Host "OK $Signing already exists (never replace it: back it up)"; break }
        $keytool = Join-Path $env:JAVA_HOME "bin\keytool.exe"
        if (-not $env:JAVA_HOME -or -not (Test-Path $keytool)) { Fail "keytool not found; set JAVA_HOME to a JDK 17" }
        $dir = Split-Path $Signing
        $store = Join-Path $dir "release.jks"
        if (Test-Path $store) { Fail "$store exists without $Signing; restore the properties file from your backup" }
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $bytes = New-Object byte[] 24
        [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $env:DINA_KEY_PASSWORD = [Convert]::ToBase64String($bytes) -replace '[+/=]', 'x'
        $previous = $ErrorActionPreference; $ErrorActionPreference = "Continue" # keytool logs to stderr
        # PKCS12 keeps one password for the store and the key.
        & $keytool -genkeypair -keystore $store -storetype PKCS12 -alias dina -keyalg RSA -keysize 4096 -validity 36500 `
            -dname "CN=Dina, O=Kakauet" -storepass:env DINA_KEY_PASSWORD -keypass:env DINA_KEY_PASSWORD *> $Log
        $code = $LASTEXITCODE
        $ErrorActionPreference = $previous
        if ($code -ne 0) { Remove-Item Env:DINA_KEY_PASSWORD; Remove-Item $store -ErrorAction SilentlyContinue; Fail "keytool (log: android/build/dev-last.log)" }
        $properties = @("storeFile=release.jks", "storePassword=$env:DINA_KEY_PASSWORD", "keyAlias=dina", "keyPassword=$env:DINA_KEY_PASSWORD")
        [System.IO.File]::WriteAllLines($Signing, $properties)
        Remove-Item Env:DINA_KEY_PASSWORD
        Write-Host "OK $dir (release.jks + $(Split-Path $Signing -Leaf)): back this folder up; losing it means no more updates"
    }
    "apk" {
        # Both editions unless one is named; every APK is checked to be signed with the release key (never the debug one).
        if (-not (Test-Path $Signing)) { Fail "no release key at $Signing; run .\dev.ps1 keystore once (or set DINA_SIGNING)" }
        $editions = if ($VariantGiven) { @($Variant) } else { @("full", "lite") }
        Invoke-Gradle @(":app:testFullDebugUnitTest")
        Show-TestSummary
        $version = Get-VersionName
        $made = @()
        foreach ($edition in $editions) {
            $capital = (Get-Culture).TextInfo.ToTitleCase($edition)
            & (Join-Path $Root "scripts\models\prepare-models.ps1") -Variant $edition | Out-Null
            Invoke-Gradle @(":app:assemble${capital}Release")
            $apk = Get-Apk -Release -Edition $edition
            $certs = & (Get-BuildTool "apksigner.bat") verify --print-certs $apk 2>&1 | Out-String
            if ($LASTEXITCODE -ne 0 -or $certs -notmatch 'certificate DN: (.+)') { Fail "apksigner verify ($edition): $certs" }
            $signer = $Matches[1].Trim()
            if ($signer -match 'Android Debug') { Fail "the $edition release APK is signed with the debug key ($signer)" }
            $dist = Join-Path $Root "dist"
            New-Item -ItemType Directory -Force -Path $dist | Out-Null
            $name = if ($edition -eq "lite") { "Dina-Lite-$version.apk" } else { "Dina-$version.apk" }
            $target = Join-Path $dist $name
            Copy-Item $apk $target -Force
            $made += ("{0} ({1:N0} MB, signed: {2})" -f ($target.Substring($Root.Length + 1)), ((Get-Item $target).Length / 1MB), $signer)
        }
        $made | ForEach-Object { Write-Host "OK $_" }
    }
    "tts-models" {
        # Supertonic for the app: 8-bit weights, 1x1 convolutions as MatMul, guidance split out of the estimator.
        # fp32 originals (models\tts\supertonic3-fp32, read-only) -> models\tts\supertonic3. Needs WSL with the conda env `tts`.
        $current = Join-Path $Root "models\tts\supertonic3"
        if (Test-Path (Join-Path $current "onnx")) { Fail "models\tts\supertonic3 is already prepared (nothing is overwritten); move it away to prepare it again" }
        $format = if ($Rest.Count -gt 0) { $Rest[0] } else { "dq8" }
        $linuxRoot = "/mnt/" + $Root.Substring(0, 1).ToLower() + ($Root.Substring(2) -replace "\\", "/")
        $cmd = "cd '$linuxRoot' && ~/miniconda3/envs/tts/bin/python scripts/tts/quantize_supertonic.py models/tts/supertonic3-fp32 models/tts/supertonic3 --format $format"
        $previous = $ErrorActionPreference; $ErrorActionPreference = "Continue"
        wsl.exe -d Ubuntu-22.04 -- bash -lc $cmd
        $code = $LASTEXITCODE; $ErrorActionPreference = $previous
        if ($code -ne 0) { Fail "tts-models (see the output above)" }
        Write-Host "OK models\tts\supertonic3 ready (prepare-models checks the sizes ModelInstaller expects)"
    }
    "screenshots" {
        # Robolectric + Roborazzi render Compose on the JVM; no phone needed.
        $out = Join-Path $Root "screenshots"
        New-Item -ItemType Directory -Force -Path $out | Out-Null
        Get-ChildItem $out -Recurse -File | Remove-Item -Force
        Invoke-Gradle @(":app:testFullDebugUnitTest", "-Pdina.screenshots=$out")
        Show-TestSummary
        $png = (Get-ChildItem $out -Recurse -Filter "*.png").Count
        $gif = (Get-ChildItem $out -Recurse -Filter "*.gif").Count
        Write-Host "OK $png PNG + $gif GIF in screenshots\"
    }
    "icons" {
        $res = Join-Path $Android "app\src\main\res"
        Invoke-Gradle @(":app:testFullDebugUnitTest", "-Pdina.icons=$res")
        Show-TestSummary
        Write-Host "OK launcher icons in android\app\src\main\res\mipmap-*"
    }
    default {
        Get-Content $PSCommandPath | Select-Object -Skip 1 -First 27 | ForEach-Object { Write-Host $_ }
    }
}
