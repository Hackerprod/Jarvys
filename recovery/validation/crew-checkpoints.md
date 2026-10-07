> Registro de un hito anterior. El resultado vigente está en [FINAL.md](FINAL.md).

# Crew y checkpoints: estado de restauración

Restaurados perfiles schema 2, checkpoints con hashes, artefactos grandes paginados y redacción de credenciales, mensajes duraderos, compacción, estado de trabajos y recuperación explícita.

Invariantes: guardar intención antes de lanzar una acción; conservar resultados antes de cancelación; no repetir efectos inciertos; persistir mensajes antes de vaciar el buzón; restaurar historial sin reiniciar trabajo; exigir acción explícita para reanudar perfiles tras interrupción/STOP/fallo; conservar aislamiento de bot y conversación.

39 nuevas pruebas enfocadas en seis clases RecoveryTest. Las expectativas antiguas CrewC0/C1c se migraron del texto del prompt a observaciones TOOL_RESULT duraderas; no se relajaron comprobaciones de procedencia ni resultado.

Las fuentes completas Full (Kotlin y Java) compilaron en la integración de trabajo. Suite completa testFullDebugUnitTest en curso; estos tests todavía no se consideran aprobados hasta registrar resultado. El commit de módulo es parte de una restauración progresiva y depende de los siguientes commits de runtime/UI.
