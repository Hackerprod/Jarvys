# Cola: espera a que no haya otra corrida codex pesada (Sol) y a tener RAM libre, luego lanza Astra.
$verdict = 'C:\Projects\Jarvys-wt\single-validate\sol-verdict.txt'
while ($true) {
    $solRunning = [bool](Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { $_.CommandLine -match 'gpt-6.1-sol' })
    $freeGB = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB
    if (-not $solRunning -and $freeGB -ge 2.0) { break }
    Start-Sleep -Seconds 20
}
powershell -NoProfile -ExecutionPolicy Bypass -File C:\Projects\mascot-lab\run_astra.ps1
