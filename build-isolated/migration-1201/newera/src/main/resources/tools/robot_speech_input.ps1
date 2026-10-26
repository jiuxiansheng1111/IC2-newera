param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('zh-CN', 'en-US', 'ja-JP')]
    [string]$Language
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Write-ProtocolError([string]$Code) {
    [Console]::Out.WriteLine("IC2NE_SPEECH_ERROR:$Code")
}

$engine = $null
try {
    Add-Type -AssemblyName System.Speech -ErrorAction Stop
    $culture = [System.Globalization.CultureInfo]::GetCultureInfo($Language)
    $recognizers = [System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers()
    $match = $recognizers | Where-Object { $_.Culture.Name -ceq $culture.Name } | Select-Object -First 1
    if ($null -eq $match) {
        Write-ProtocolError 'recognizer_unavailable'
        exit 0
    }

    $engine = [System.Speech.Recognition.SpeechRecognitionEngine]::new($match)
    $engine.SetInputToDefaultAudioDevice()
    $grammar = [System.Speech.Recognition.DictationGrammar]::new()
    $engine.LoadGrammar($grammar)
    $result = $engine.Recognize([TimeSpan]::FromSeconds(15))
    if ($null -eq $result -or [string]::IsNullOrWhiteSpace($result.Text)) {
        Write-ProtocolError 'no_speech'
        exit 0
    }

    $encoded = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($result.Text))
    [Console]::Out.WriteLine("IC2NE_SPEECH_OK:$encoded")
} catch {
    if ($null -eq $engine) {
        Write-ProtocolError 'recognizer_unavailable'
    } else {
        Write-ProtocolError 'microphone_unavailable'
    }
} finally {
    if ($null -ne $engine) { $engine.Dispose() }
}
