$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
Set-Location C:\Projects\Jarvys-wt\single\app
$log = 'C:\Projects\Jarvys-wt\single-validate\verify-S1b.log'
"== compile ==" | Out-File $log -Encoding utf8
.\gradlew.bat :app:compileFullDebugKotlin :app:compileFullDebugJavaWithJavac --console=plain 2>&1 | Out-File $log -Append -Encoding utf8
"EXIT compile: $LASTEXITCODE" | Out-File $log -Append -Encoding utf8
"== tests B9/B10/B11 ==" | Out-File $log -Append -Encoding utf8
.\gradlew.bat :app:testFullDebugUnitTest --tests com.jarvys.agent.SingleSessionResetTest --tests com.jarvys.agent.SingleSessionReadBoundariesTest --rerun --tests 'com.jarvys.agent.MainActivityWorkspaceRoutingTest.B10_startIntentNewChatAndRestoredPickerAlwaysUseCentral' --tests com.jarvys.agent.SingleSessionStartupTest --tests com.jarvys.agent.GeneratedImageStoreTest -x :app:prepareApkFactoryTemplate --console=plain 2>&1 | Out-File $log -Append -Encoding utf8
"EXIT tests: $LASTEXITCODE" | Out-File $log -Append -Encoding utf8

