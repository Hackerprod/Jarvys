# S6 — ACK Coding interrumpido y guards de compaction

**Checkpoint interno WIP; ASYNC sigue desactivado en el catálogo.**

## Cambio y evidencia

El job queda observable antes de esperar la aceptación, conservando el commit original de submission. El callable original publica su aceptación exacta después de la transacción; una interrupción del waiter no autoriza repetir enqueue ni vincular el recibo a otra invocación. La continuación técnica proyecta el terminal fuera del worker de Bridge y mantiene el pin hasta el resultado real.

El oráculo post-commit/pre-Future detectó un defecto: la interrupción conservada impedía confirmar el journal terminal y el pin se cerraba sin proyección. El diagnóstico del writer real confirma el mecanismo host (DIRECTORY_SYNC interrumpido después de RENAME, sin Commit); no se presenta como stack capturado del manager. El ajuste consume y restaura el flag inicial **sólo alrededor del persist terminal técnico de esta ruta abandonada**. STOP, preparación, dispatch y callbacks no se ejecutan con permisos nuevos. Otra interrupción durante I/O sigue siendo incierta, sin retry infinito.

La prueba causal ahora confirma: mismo job/binding, aceptación original conservada, cero enqueue/backend, caller todavía interrumpido, pin sin drenar mientras la proyección está bloqueada y terminal real en Room antes de confirmar/drenar. Una observación incorrecta del test que consultaba el Bridge retenido fue sustituida por lectura directa del fixture Room; el guard de producto y los timeouts no se relajaron.

Compaction considera jobs pendientes del destino exacto y los incluye ordenados en su fingerprint/CAS. Un nuevo job o cambio de estado invalida un digest preparado. Los jobs terminales omitidos/suprimidos conservan un marcador explícito y `goal_verified=false`, incluso bajo presupuesto; no se infiere éxito global desde otras evidencias. La cobertura mediante referencias externas retenidas sigue pendiente.

## Validación

- Compilación Full/Play: PASS.
- **221 Full + 174 Play = 395 casos frescos**, 27/20 clases; cero fallos, errores o skips.
- Runner JVM offline revisado, sin nuevas excepciones de red o subprocess.
- Incluye ACK/diagnóstico, manager y regresiones anteriores, seis lectores de fuentes, guards de narración/compaction y persistencia del flush.
- Los lectores de fuentes sembrados prueban consumidores, no reemplazan la cadena completa con un productor real.

[Manifiesto por clase y hashes](S6-coding-acceptance-test-results.json). Los intentos rojos anteriores se conservan como diagnóstico; no se reutilizan los 335 casos del checkpoint anterior como ejecución de este árbol.

## Pendientes

Reserva interrumpida antes del journal, primer journal incierto, reparación live del terminal deseado, recovery acreditado de otro owner, cadena semántica real retenida, referencias externas de compaction y matriz restante antes del opt-in. No es cierre de S6, S7/S8, paridad Engram ni APK.
