param([string]$OutputDirectory = "data/wakeword/corpus")
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Speech
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$target = Join-Path $root $OutputDirectory
New-Item -ItemType Directory -Force -Path $target | Out-Null
$phrases = Get-Content -Raw -Encoding UTF8 (Join-Path $PSScriptRoot "phrases.json") | ConvertFrom-Json
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, 16, 1)
$speaker = New-Object System.Speech.Synthesis.SpeechSynthesizer
$rows = @()
try {
    $voices = $speaker.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -like 'es-*' }
    foreach ($installed in $voices) {
        $voice = $installed.VoiceInfo.Name
        # Desktop and OneCore aliases of the same speaker must stay in one split.
        $identity = ($voice -replace ' Desktop$', '').ToLowerInvariant()
        $split = if ($identity -match 'laura') { 'test' } elseif ($identity -match 'pablo') { 'calibration' } else { 'train' }
        $speaker.SelectVoice($voice)
        foreach ($rate in @(-2, 0, 2)) {
            $speaker.Rate = $rate
            $index = 0
            foreach ($label in @('positive', 'negative')) {
                foreach ($text in $phrases.$label) {
                    $safe = ($voice -replace '[^A-Za-z0-9]+', '_').ToLowerInvariant()
                    $name = "windows_${safe}_${rate}_${index}.wav"
                    if (-not (Test-Path -LiteralPath (Join-Path $target $name))) {
                        $speaker.SetOutputToWaveFile((Join-Path $target $name), $format)
                        $speaker.Speak($text)
                        $speaker.SetOutputToNull()
                    }
                    $rows += [ordered]@{file=$name; label=$label; text=$text; voice=$voice; speaker_id="windows:$identity";
                        split=$split; engine='windows'; style='tts'; rate=$rate; source='synthetic'; language=$installed.VoiceInfo.Culture.Name}
                    $index++
                }
            }
        }
    }
} finally { $speaker.Dispose() }
$rows | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $target 'windows.json')
Write-Host "OK Windows Spanish: $($rows.Count) clips; speaker aliases share their split"
