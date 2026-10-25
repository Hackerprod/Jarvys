param(
  [Parameter(Mandatory)][string]$Stage,
  [string]$Model = 'gpt-6-astra',
  [string]$Mode = 'impl'   # impl = escribe codigo; judge = solo lectura
)
$d = 'C:\Projects\Jarvys-wt\single-validate'
$brief = Join-Path $d "stage-$Stage-brief.txt"
$out   = Join-Path $d "stage-$Stage-last.txt"
$log   = Join-Path $d "stage-$Stage-run.log"
$sandbox = if ($Mode -eq 'judge') { 'read-only' } else { 'danger-full-access' }
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
Set-Location C:\Projects\Jarvys-wt\single
$prompt = Get-Content $brief -Raw -Encoding UTF8
$prompt | codex exec -m $Model -c model_reasoning_effort=high --skip-git-repo-check -s $sandbox -C C:\Projects\Jarvys-wt\single -o $out - *> $log
