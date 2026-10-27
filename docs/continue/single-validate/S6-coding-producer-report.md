# S6 — productor Coding interno y consumidores: checkpoint WIP

## Alcance implementado

- Ruta interna real `startInstrumented`: pin de owner, fuente Crew preparada, reserva Room, journal schema 3, aceptación administrativa vinculada antes del enqueue, claim único y admisión técnica antes de construir el backend lazy.
- Proyección terminal fuera del lock del job. La captura semántica apunta a una entrada existente exacta; no inventa raíz ni recrea temas. La política OFF no suprime la auditoría técnica.
- Room 8 incorpora el enlace técnico-semántico mediante migración aditiva 7→8. La cola/narración y su CAS consideran jobs pendientes del destino exacto. Los roles semánticos se insertan sólo al terminal con ticket vivo; no hay backfill por volver a activar memoria.
- Registry separa la aceptación administrativa del resultado de ejecución; tareas no admiten el modo asíncrono. El contexto técnico del catálogo sigue desactivado por defecto y su declaración de cobertura sigue **false**.

**No es una entrega operativa de Coding ASYNC ni cierre S6.** Los consumidores probados con estado persistido no sustituyen la prueba pendiente de toda la cadena productor→fuente semántica retenida→narrador.

## Evidencia ejecutada

Full/Play y sus tests compilan. Selección focal reconciliada: **190 Full + 145 Play**, 23/17 clases, cero fallos/errores/skips en la composición final. Tras corregir únicamente un oráculo de test, se repitió Registry (8 + 8); las restantes clases proceden de la corrida anterior con hashes de producción, helper compartido y otros tests sin cambios. No se afirma que los 335 casos se ejecutaran de nuevo tras esa última edición.

Los cuatro casos nuevos del manager usan Registry/Crew/Room/journal reales y un backend local falso: admisión antes de su primera mutación, memoria OFF con auditoría íntegra, executor inline que luego falla, rechazo de preparación sin construir backend y quiesce antes del primer pin. No prueban rootfs o ejecución en dispositivo.

El protocolo separado entre JVM reales valida ALIVE antes/después de un probe interno, quiesce con pin y DEAD tras salida limpia o terminación abrupta observada. Cinco endpoints protegidos pasan; el holder terminado deliberadamente tiene exit -9 y **no** cuenta como test PASS. Es evidencia de liveness/locks del host, no de recovery, pérdida de alimentación ni Android.

Todas las pruebas usan la guardia JVM offline revisada. El fixture multiproceso añade un único directorio auxiliar al classpath exportado; no se presenta como configuración Gradle idéntica. No se añadió permiso de red o subprocess al JVM protegido.

[Manifiesto por clase, hashes e intentos previos](S6-coding-producer-test-results.json).

## Gates abiertos antes del opt-in

1. ACK durable interrumpido antes de bind: conservar el commit original y confirmar la proyección terminal real sin repetir enqueue.
2. Reparación de terminal pendiente y recovery verificado de schema 3; no inferir muerte de owner por timeout ni acuñar proof mediante decode.
3. Cadena real de fuente semántica retenida, retirada, OFF/restart y rechazo de destino/binding reemplazados.
4. Compaction: guard de pending y CAS, y cobertura mediante referencias Coding externas retenidas sin convertir los inputs Crew plurales en hojas Central. Actualmente esos roles quedan conservadoramente incompletos.
5. Matriz completa de fallos/concurrencia, activación del productor y juicio independiente del bundle.

S7/S8, paridad Engram, validación global y APK siguen pendientes. Sin cambios de permisos ni decisión nueva sobre `linux_status`.
