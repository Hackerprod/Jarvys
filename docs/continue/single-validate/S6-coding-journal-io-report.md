# S6 — extracción del writer del journal Coding

Estado: validación focal final **38 Full + 6 Play PASS**, cero fallos/errores/skips; revisión independiente conforme. No es cierre S6 ni activación ASYNC.

## Cambio acotado

`CodingJobManager.persist` utiliza el nuevo writer real para los mismos JSON operacionales
schemas 1/2. El candidato conserva bytes defensivos y destino/job/schema/intento exactos;
el token interno positivo se devuelve sólo después de write, file-sync, close, rename y
directory-sync. No es una frontera de seguridad frente a código del mismo módulo, un recibo
causal de ejecución, ni prueba física universal de persistencia del dispositivo.

Los fallos conservan candidato y fronteras intentada/confirmada; leer bytes nuevos después
de una excepción no convierte incertidumbre en commit. La limpieza no oculta la excepción
primaria. No existe API para reintentar candidatos, migración automática a schema3 ni replay.
La serialización por raíz es local al proceso, no lease de liveness, CAS o defensa contra
snapshots legacy atrasados. El patrón de directory-sync compatible con API anterior a 26
se conserva. El Snapshot público y los permisos no cambian.

## Evidencia conservada

- Primer compile falló: el nuevo oráculo de concurrencia usaba una API de gestión del JDK
  ausente de los stubs Android. Se accede mediante reflexión a tipos públicos del host, sin
  dependencia nueva ni fallback permisivo. Se conserva el intento fallido.
- La segunda compilación Full/Play pasó. El nuevo writer pasó 8 pruebas, Play boundary pasó 6.
- Las otras tres clases Full tuvieron 25 fallos entre 30 casos. Un checkout limpio de
  `fd91c50bfa641a4752f4646a73b7cc10d5227f19`, compilado y ejecutado con el mismo runner
  offline, reprodujo exactamente los métodos fallidos, tipos y primera línea de excepción.
  Los fixtures carecían de bridge Room registrado o ejecutaban operaciones en main-thread.
  La corrección prepara Room real y ejecuta en worker; no debilita guards ni cambia asertos/datos.
- Tras esa preparación, 37/38 casos Full pasan. El restante invocaba el registro Crew directamente
  sin checkpoint preparado. Baseline con sólo fixture worker reproduce exactamente ese fallo.
  Se adapta esa prueba a Loop y coordinador reales con modelo local scriptado, sin fabricar proof
  ni desactivar el provider de procedencia. Los asertos originales de catálogo y resultado permanecen.

El oráculo de serialización observa al segundo thread realmente BLOCKED en el monitor exacto
con el primer thread como owner antes de liberarlo. Un plazo vencido falla: no se interpreta
como evidencia positiva. OPEN se prueba con fallo previo; WRITE/SYNC/CLOSE/RENAME/DIRSYNC antes y después de operaciones sobre
ficheros temporales reales; no son ensayos de corte eléctrico en Android.

## Pendiente

La compilación final Full/Play pasó y las 44 ejecuciones focales frescas pasan en el runner
standalone offline calibrado. [Manifiesto verificable](S6-coding-journal-io-test-results.json).
La revisión independiente verificó seis hashes de código/pruebas y cinco XML exactos. Permanecen
claim de worker, owner lease durable, schema3 con reserva Room real, admisión de preparación
antes de factory lazy y proyección técnica sin replay. No se acredita ejecución completa,
memoria semántica, paridad Engram, APK ni CI.
