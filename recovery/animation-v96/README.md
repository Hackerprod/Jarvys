# Android host test guard recovery

Source-only snapshot of the independently reviewed v96 test guard. Copy this directory outside the repository before running build.sh; it rejects building within Git. Use a verified JDK21 and the exact public OkHttp5.2.3/Okio3.16.4/Kotlin2.2.20 artifacts. No keys, user logs or compiled binaries are included.

Enable `-Djarvys.offlineGuard.okhttp=required` for app test workers. Existing seven JDK probes remain; five additional standard OkHttp hooks verify actual classloaders before transport. Shutdown acceptance requires okhttpComplete, positive verified loaders, five hooks per loader and zero transformation/verification failures. The evidence applies to reviewed transport paths, not universal network isolation. Gradle artifact downloads run separately from offline application tests.

Reviewed outputs: agent SHA25677f6c6e128fc6a6820850de6007889d2ec566e1a2c388d16b274c6de66e13ef9; bootstrap SHA25670f706b71ce5e505233938411f93072a00853fafa1792b49bc2e5a7e4a777a05. A rebuild may differ in archive metadata; review and pin the actual output before use.
