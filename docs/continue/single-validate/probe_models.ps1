foreach ($m in 'gpt-6.1-sol','gpt-6-luna','gpt-6-astra') {
  $o = 'ping' | codex exec -m $m --skip-git-repo-check -s read-only -C C:\Projects\Jarvys-wt\single-validate - 2>&1 | Out-String
  $first = ($o -split "`n" | Where-Object { $_ -match 'usage limit|ERROR|tokens used|pong|ping' } | Select-Object -First 2) -join ' | '
  "$m => $first"
}
