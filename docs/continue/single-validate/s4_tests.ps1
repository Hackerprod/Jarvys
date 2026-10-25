param([string]$Name, [string[]]$Classes, [string]$Root = "C:\Projects\Jarvys-wt\single")
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
Set-Location "$Root\app"
$args2 = @(':app:testFullDebugUnitTest','--rerun')
foreach($c in ($Classes -join ',' -split ',')){ $args2 += '--tests'; $args2 += "*$c" }
$args2 += @('-x',':app:prepareApkFactoryTemplate','--console=plain')
"COMMAND: .\gradlew.bat $($args2 -join ' ')" | Set-Content "C:\Projects\Jarvys-wt\single-validate\$Name.log"
.\gradlew.bat @args2 2>&1 | Add-Content "C:\Projects\Jarvys-wt\single-validate\$Name.log"
"EXIT: $LASTEXITCODE" | Add-Content "C:\Projects\Jarvys-wt\single-validate\$Name.log"

