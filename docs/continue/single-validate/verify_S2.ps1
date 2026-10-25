$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
Set-Location C:\Projects\Jarvys-wt\single\app
$log = 'C:\Projects\Jarvys-wt\single-validate\verify-S2.log'
"== compile full+play (main+tests) ==" | Out-File $log -Encoding utf8
.\gradlew.bat :app:compileFullDebugKotlin :app:compileFullDebugJavaWithJavac :app:compilePlayDebugKotlin :app:compilePlayDebugJavaWithJavac :app:compileFullDebugUnitTestKotlin :app:compileFullDebugUnitTestJavaWithJavac --console=plain 2>&1 | Out-File $log -Append -Encoding utf8
"EXIT compile: $LASTEXITCODE" | Out-File $log -Append -Encoding utf8
"== tests ==" | Out-File $log -Append -Encoding utf8
$cls = 'SingleSessionResetTest','SingleSessionReadBoundariesTest','SingleSessionStartupTest','MainActivityWorkspaceRoutingTest','SettingsEntriesComposeTest','DrawerNavigationComposeTest','MainActivityDrawerNavigationTest','MainAgentContractTest','PersonalAgentBehaviorTest','TasksNavigationContractTest'
$targs = @(); foreach ($c in $cls) { $targs += '--tests'; $targs += "*$c" }
$targs += '--tests'; $targs += '*JarvysChatDatabaseM0Test'
.\gradlew.bat :app:testFullDebugUnitTest --rerun @targs -x :app:prepareApkFactoryTemplate --console=plain 2>&1 | Out-File $log -Append -Encoding utf8
"EXIT tests: $LASTEXITCODE" | Out-File $log -Append -Encoding utf8
