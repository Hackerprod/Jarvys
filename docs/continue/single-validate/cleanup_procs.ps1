$today = (Get-Date).Date
$cut = (Get-Date).AddHours(-2)
$all = Get-CimInstance Win32_Process
$byParent = @{}
foreach ($p in $all) { $k = [int]$p.ParentProcessId; if (-not $byParent.ContainsKey($k)) { $byParent[$k] = New-Object System.Collections.ArrayList }; [void]$byParent[$k].Add($p) }
$targets = New-Object System.Collections.Generic.HashSet[int]
function Add-Tree([int]$id) {
  if ($targets.Add($id) -and $byParent.ContainsKey($id)) { foreach ($c in $byParent[$id]) { Add-Tree ([int]$c.ProcessId) } }
}
# codegraph servers (shim, workers, wrapper cmd) started before today
foreach ($p in $all) {
  if ($p.Name -match '^(node|cmd)\.exe$' -and ([string]$p.CommandLine) -match 'codegraph' -and $p.CreationDate -lt $today) { Add-Tree ([int]$p.ProcessId) }
}
# children of the leaking codex.exe (72136) older than 2 hours, never the codex.exe itself
if ($byParent.ContainsKey(72136)) {
  foreach ($c in $byParent[72136]) {
    if ($c.Name -match '^(node|node_repl|cmd|conhost)\.exe$' -and $c.CreationDate -lt $cut) { Add-Tree ([int]$c.ProcessId) }
  }
}
[void]$targets.Remove(72136)
"objetivos: $($targets.Count)"
$free0 = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB
foreach ($t in $targets) { & taskkill.exe /F /PID $t 2>$null | Out-Null }
Start-Sleep 3
$free1 = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB
"libre antes {0:N1} GB, ahora {1:N1} GB" -f $free0, $free1
$a = Get-CimInstance Win32_Process
"node: {0}  node_repl: {1}  cmd: {2}  conhost: {3}" -f ($a | ? Name -eq 'node.exe').Count, ($a | ? Name -eq 'node_repl.exe').Count, ($a | ? Name -eq 'cmd.exe').Count, ($a | ? Name -eq 'conhost.exe').Count
