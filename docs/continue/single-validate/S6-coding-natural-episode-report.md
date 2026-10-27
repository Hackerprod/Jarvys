# S6 — cadena semántica Coding con origen natural

**Pruebas de integración internas; producto sin cambios y catálogo ASYNC desactivado.**

La nueva prueba Full recorre CentralWorkspaceTool y WorkspaceCoordinator reales, transcript/source
provider y EpisodeRecorder reales, assignment persistido, Crew/checkpoint y el seam instrumentado
existente de CodingJobManager. La entrada se crea por el flujo normal antes de delegar: no se inserta
un episodio por SQL ni se construye una raíz, witness o Commit para aparentar integración. Los modelos
y el backend son fakes locales bajo el mismo guard offline.

Tres casos comprueban:

1. Aceptación duradera mantiene backend sin ejecutar, roles Coding vacíos y narración bloqueada.
   El terminal produce exactamente submission+execution del mismo job en el destino capturado;
   repetir el runnable no duplica la ejecución. El narrador cuenta un job, no afirma meta completada
   y la fuente sigue retenida después de guardar la narración. La raíz histórica no es causa exclusiva.
2. OFF→ON antes del terminal conserva la evidencia técnica, pero invalida el ticket original: no
   añade roles ni hace backfill; el narrador conserva el marcador de supresión.
3. Olvidar la entrada capturada antes del terminal no la recrea; termina la auditoría técnica y la
   fuente semántica queda no disponible.

## Validación

Full/Play compilan (26s, 91 tasks: 4 ejecutadas, 87 up-to-date). **Tres casos Full PASS**, cero
fallos, errores o skips; exit0 y launcher sin fallos. Un hash fuente y el XML corresponden al mismo
archivo congelado. [Manifiesto](S6-coding-natural-episode-test-results.json).

La producción sigue idéntica a `025c0e31`. Play no se reejecuta porque sólo se añade testFull; esta
evidencia no se suma a ejecuciones anteriores como una suite fresca. El seam explícito no habilita
la herramienta de producción. Quedan recovery/scanner/Crew, referencias externas de compaction,
variantes restantes de retiro/integridad y la matriz final. No hay cierre S6/S7/S8, paridad Engram ni APK.
