# S6 — claim local del worker Coding

Validación focal: **47 Full + 6 Play PASS**, cero fallos/errores/skips, compilación ambos
flavors. Revisión independiente conforme; cuatro hashes de código/pruebas y siete XML verificados. [Manifiesto](S6-coding-worker-claim-test-results.json).

## Cambio

El Runnable obtiene un claim atómico antes de cualquier setup/backend. Una copia simultánea,
tardía o ejecutada después de rechazo no vuelve a ejecutar ni cierra recursos ajenos.
El catch de submission sólo finaliza rechazo si ganó frente al worker; una vez reclamado,
la finalización pertenece al worker y ocurre una sola vez. Cleanup anidado intenta liberar
lease/unregister y completa el estado/latch incluso ante excepción. Callback fuera de locks.
Los errores fatales se propagan tras cleanup y no se convierten en éxito ordinario.

Si el executor ejecuta y después lanza excepción, se conserva el job y su estado factual.
Una excepción tipada transporta jobId/estado/exitCode observados. La herramienta devuelve error
estructurado con incertidumbre de transporte separada de ejecución, sin comando ni detalle
libre del executor. Un SUCCEEDED ya observado no se sobrescribe con NOT_STARTED/UNCERTAIN;
el resultado de la llamada tampoco se transforma en command-OK o recibo administrativo.
La causa Java/Kotlin existe en la excepción interna, pero no se publica en ese JSON.

## Pruebas

Ocho casos nuevos del manager cubren ejecución inline y throw posterior, CancellationException
posterior al claim, worker vivo con backend bloqueado, Runnable duplicado, cancelación previa,
rechazo con Runnable retenido, Error fatal antes/después de launch y callback reentrante que
crea otro job. Un caso de herramienta comprueba identidad, terminal factual y ausencia de
comando/detalle interno/recibos en el error. Los latches acreditan los puntos de carrera; se
verifican versiones de scope y exclusión real del writer además de contadores de callbacks.
Regresiones focales del writer, recovery, assembly y tools conservadas y ejecutadas frescas.

Se usa runner standalone offline calibrado, no inferencia ni procesos nativos. La compilación
Gradle exporta classpaths y no ejecuta acciones Test en este bloque. No son pruebas de dispositivo.

## Límites

El claim es RAM/local al proceso, no CAS de revisión del journal ni prueba de liveness entre
procesos. Siguen pendientes owner lease durable, schema3, reserva/proyección Room y admisión
antes de factory lazy. No cambia permisos ni activa ASYNC; no completa S6, S7/S8, Engram o APK.
