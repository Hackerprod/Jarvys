$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
Set-Location C:\Projects\Jarvys-wt\single\app
.\gradlew.bat :app:compileFullDebugKotlin :app:compileFullDebugJavaWithJavac :app:compilePlayDebugKotlin :app:compilePlayDebugJavaWithJavac :app:compileFullDebugUnitTestKotlin :app:compileFullDebugUnitTestJavaWithJavac --console=plain
