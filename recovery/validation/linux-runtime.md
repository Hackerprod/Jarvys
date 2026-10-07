# Linux: recuperación de ejecución de proyectos

Restauradas herramientas project_environment_status, project_exec y project_jobs; diario duradero, identidad de propietario, paginación UTF-8 de logs, redacción de credenciales, cancelación/timeouts, incertidumbre después de reinicio y ausencia de repetición automática. El lanzamiento conserva intención antes del proceso y verifica identidad PID antes de cancelarlo. Los errores al persistir salida se propagan.

Autonomía ASK/ALLOW/DENY con cambios duraderos y aprobación atómica; cierre de entorno y lanzamiento excluidos mutuamente. Las rutas privadas de proyectos tienen home/tmp propios.

Añadidas 17 pruebas en CodingJobRecoveryTest, LinuxExecAutonomyTest y CodingExecutionToolsTest. No se consideran aprobadas todavía; la JVM Robolectric necesita la configuración externa del runner. Producción Kotlin Full y sus Java compartidos compilaron en la integración. No se arrancó el APK ni se instaló un entorno Linux en un teléfono.
