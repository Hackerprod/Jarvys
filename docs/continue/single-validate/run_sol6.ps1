Set-Location C:\Projects\Jarvys-wt\single
$prompt = Get-Content C:\Projects\Jarvys-wt\single-validate\SOL_BRIEF6.txt -Raw -Encoding UTF8
$prompt | codex exec -m gpt-6.1-sol -c model_reasoning_effort=high --skip-git-repo-check -s read-only -C C:\Projects\Jarvys-wt\single -o C:\Projects\Jarvys-wt\single-validate\sol-verdict6.txt - *> C:\Projects\Jarvys-wt\single-validate\sol-run6.log

