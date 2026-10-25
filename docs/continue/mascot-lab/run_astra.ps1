Set-Location C:\Projects\mascot-lab
Copy-Item jdraw.js out\jdraw.before-astra.js -Force
$nota = "`n`nNOTA: poca RAM en el PC (unos 2 GB libres). Evita renders innecesarios (no lances varios Edge a la vez), cierra procesos que abras, y guarda tu informe final en out/astra-last.txt.`n"
$prompt = (Get-Content BRIEF.txt -Raw -Encoding UTF8) + $nota
$prompt | codex exec -m gpt-6-astra -c model_reasoning_effort=high --skip-git-repo-check -s danger-full-access -C C:\Projects\mascot-lab -i reference.png -o out\astra-last.txt - *> out\astra-run.log
