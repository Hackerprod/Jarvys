# S6 — memoria automática y cambio de estado

Contrato revisado antes de implementar. No declara garantía ya desplegada ni PASS S6.

## Semántica

- Gate compartido por raíz canónica de archivos de aplicación, en el proceso actual. Los manifests
  actuales no separan escritores por `android:process`; no se promete coordinación multiproceso futura.
- Ticket opaco runtime-only: propietario, epoch y estado capturados antes de modelo/fase automática.
  OFF→ON invalida resultados anteriores. No se recaptura el ticket al persistir ni lo elige el modelo.
- OFF linealiza al terminar el setter confirmado, no al toque UI. Setter fuera de main comparte
  exclusión con commits cortos. Persiste preferencias antes de confirmar; fallo de persistencia
  invalida epoch y deja deny-overlay en proceso, devuelve error, no falso éxito. Resultado de disco
  tras un fallo no se presenta como garantizado al reiniciar.
- Commit automático valida ticket/epoch/estado/cancelación bajo exclusión, mantenida hasta commit o
  rollback. No se mantiene gate durante modelo, red, UI, espera de consentimiento ni listeners.
- USER explícito conserva edición/corrección/olvido con OFF. Audit causal y cierres de trabajos ya
  admitidos no se bloquean. Compactación conserva resumen/cobertura estructural e incomplete con OFF,
  sin digest, through_seq semántico ni contador de flush completado.

## Orden de locks

Room: inicialización de repositorios antes del gate; adquirir/liberar en callable del bridge alrededor
completo de runBlocking, antes de cualquier lease Room y sin llamadas a MemoryStore/family.

Legacy: family → callback de admisión (puede esperar bridge) → gate → journal/archivo/rollback →
release gate → publish/listeners. Nunca envolver NativeActions o withNativeState enteros con gate.
Una admisión anterior a OFF puede quedar sin efecto posterior; no borrar ni inventar éxito del recibo.
SEED, recuperación y rollback de transacciones previas son housekeeping, no nuevas memorias automáticas.

## Cobertura y pruebas

A: primitive/tickets, setter offmain y fallos. B: herramientas Room, narrador, digest/flush, consolidación,
snapshot/cursor. C: WorkspaceStore/run legacy hasta write/edit/remove y nuevo grupo de reflexión.
No afirmar cierre universal tras A/B sin C. D: regresiones integradas Full/Play y revisión independiente.

Objetivos de validación (no lista de garantías ya ejecutadas): barreras deterministas, no sleeps: commit ganador y OFF esperando; OFF ganador y cero nuevas escrituras;
modelo pendiente no retiene gate; ABA OFF→ON; rollback libera; mismo root comparte; ticket ajeno rechaza;
USER y evidencia terminal OFF permitidos; structural compaction OFF; failed preferences fail closed;
legacy admisión→cola bridge sin deadlock y listener reentrante después de liberar.

## Implementación candidata y alcance verificado

El candidato usa un coordinador por raíz canónica, setter FIFO fuera de main y tickets capturados
antes de cada fase automática. La misma identidad atraviesa herramientas Room, narrador, extracción,
consolidación y preparación de flush; la compactación estructural sigue disponible cuando el ticket
semántico caduca. Los escritores legacy conservan el ticket al derivar scopes y comprueban cancelación
tras esperar el gate. Los callbacks de admisión ocurren antes del gate y los listeners después.

Las pruebas nuevas cubren primitive (9), política compartida (6), integración Room/runtime (7),
escritores legacy (8) y fase de consolidación (2): 32 por sabor Full/Play, ejecutadas satisfactoriamente. La regresión ampliada
terminó satisfactoriamente tras corregir fixtures históricos y la factory scheduled. La revisión
detectó captura tardía en consolidación: se corrigió antes de leer candidatos, con dos pruebas
causales del worker antes de lectura de candidatos/prompt. Selección final: 1.010 ejecuciones PASS;
970 casos únicos contando flavor, 57 clases, cero skips. Los runs previos al último ajuste de factory
se identifican explícitamente en el [resumen](S6-memory-gate-test-results.json), no se presentan como
rerun global. Véase el [manifiesto](S6-validation-manifest.md) para diagnóstico de fallos previos.

La barrera de concurrencia comprueba que el hilo del setter está realmente encolado en el lock antes
de liberar el commit: observar sólo Future.isDone podía producir un falso verde. La prueba reforzada
rechazó la mutación sin locks en 200/200 repeticiones; el candidato pasó 200/200. Quitar el control de
epoch fue detectado. Quitar únicamente el término denyOverlay del predicado de commit sobrevivió:
los controles observedEnabled, epoch y ticket deshabilitado mantienen esa condición redundante. No se
presenta ese mutante como detectado ni se deduce cobertura universal de los resultados.

Límites: garantía dentro del proceso actual; no recuperación de una preferencia fallida garantizada al
reiniciar, ni ausencia de housekeeping con memoria OFF. Una fase con ticket antiguo puede consumir
trabajo del modelo antes del rechazo final; no puede comprometer nueva memoria automática. No se ha
validado APK/dispositivo y este bloque no cierra por sí solo S6 ni la paridad Engram.
