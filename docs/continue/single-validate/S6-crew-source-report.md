# S6 — Informe WIP de procedencia Crew

**Estado: validación ampliada focal PASS y revisión independiente del código/evidencia conforme. No cierre de S6 ni PASS de Coding async.**

Código/harness: `113cd8de7c0c2b3d048b18b350e3dc10451f6678` (21 fuentes/tests + 4 archivos del harness).
[Manifiesto verificable: hashes de 25 archivos y 157 XML, sin logs ni rutas privadas](S6-crew-source-test-results.json).
Contrato: [procedencia e inputs Crew](S6-crew-call-source-contract.md).
Dependencia posterior: [evidencia de jobs Coding](S6-coding-job-evidence-contract.md).

## Implementación del bloque

- El `CoreAgentLoop` prepara un snapshot opaco de la llamada con argumentos RAW y EFFECTIVE
  profundamente inmutables. La coerción pertenece a esa preparación; no se vuelve a resolver contra
  un catálogo posterior. El mapa EFFECTIVE validado es el que recibe la herramienta.
- La transición exacta STARTED se persiste antes de invocar el Registry. El store devuelve la
  confirmación de los mismos bytes guardados/sincronizados, no una lectura «latest» posterior.
- CrewManager enlaza la confirmación con loop, bot, token y ciclo vigentes. Runtime instala el
  provider en la ruta real y Registry transporta el mismo snapshot hacia una invocación concreta.
- El witness verifica callId/nombre únicos, STARTED y ausencia de resultado, además de owner/scope.
  La proyección RAW sanitizada se compara estructuralmente con el checkpoint; equivalencia numérica
  JSON exacta no cambia los tipos/valores EFFECTIVE ni permite una coerción nueva.
- Los consumidores probatorios usan una vista defensiva reconstruida desde el payload privado
  verificado. No dependen de una vista pública legacy mutable ni de reserializarla después.
- `CrewInputContextExtractor` describe inputs del mismo snapshot: referencias por canal,
  identidades/digests ya registrados, y IDs aplicados sin metadata como unresolved/PARTIAL. No hace
  IO adicional, lookup temporal, inferencia de permisos ni copia del body.

## Límites de procedencia y alcance

La raíz Central guardada con el assignment es **historia de la delegación**, no la causa exclusiva de
los comandos siguientes. Los inputs incorporados/conocidos son plurales; pueden incluir follow-ups,
replies y controles nativos. No acreditan comprensión del modelo, consumo actual, objetivo cumplido
ni causalidad exclusiva. Un digest de correlación no se interpreta como hash del texto del input.

COMPLETE significa que cada applied ID tiene al menos una referencia tipada conocida; no afirma que
se hayan descubierto todos los canales ni que todos esos inputs estén en el prompt actual. Si un ID
conserva más de un canal, se mantienen sus afirmaciones separadas. Los campos de bot en provenance
se conservan como registrados: pueden representar al emisor peer o al destinatario supervisor.

El snapshot Crew prueba scope y digest/owner/ciclo/llamada, **no un DB UUID ni una generación Room
actual**. No se inventa una generación desde `g3` ni desde el root Central histórico. La conversión
al envelope Coding que requiere identidad de almacenamiento queda pendiente de su etapa operacional.

La ruta real que exige fuente no acepta un invoke directo sin checkpoint preparado ni reconstituye
un witness a partir del callId. Los clones ordinarios conservan su provider; `forDelegatedAgent` lo
elimina. Un subagente legacy sin checkpoint propio no hereda la atribución del padre. Estos límites
no añaden permisos ni activan capacidades nuevas.

## Evidencia de validación disponible

- Núcleo puro de contexto de inputs: **9 tests PASS**. Cubren pluralidad, exclusión de queued,
  PARTIAL/unresolved, conflictos por canal/ID, copias inmutables y separación de correlación/payload.
  No sustituyen las pruebas del wiring real.
- Guard offline standalone: **6 smoke PASS**.
- Focal Gradle con Coding y guard: **Full 21/21 y Play 21/21 PASS**.
- Calibración standalone independiente con el conjunto focal equivalente:
  **Full 21/21 y Play 21/21 PASS**.
- Ampliación standalone con el harness offline calibrado: **Full 851/851 (79 clases) + Play
  847/847 (78 clases) = 1.698 ejecuciones PASS**, cero fallos, errores o skips. Ambos launchers
  finalizaron con exit 0. No es una ejecución Gradle de toda la suite ni una suite global.
- Revisión independiente de los 25 archivos y los 157 XML conforme; la copia pública del mapa de
  hashes fue cotejada: 157/157 XML y 25/25 archivos coinciden con la evidencia y el commit citado.
  Los resultados no equivalen a cierre S6.

La validación usa proveedor fake/caché explícitos y guard Java de DNS/sockets/subprocesos. La excepción
acotada a la consulta JDK `File.canExecute` no permite lanzar procesos y tiene prueba negativa de
`ProcessBuilder`. Es un alcance Java instrumentado, no una garantía universal OS/JNI. La investigación
previa de una ruta incidental GET de catálogo no confirmó un envío de red ni implicó envío de prompts.

Las fixtures se ajustaron al contrato worker real y al source checkpoint real sin eliminar los
asertos de aislamiento. La selección de herramientas de misión y skills explícitas se conserva;
el reader automático sólo contiene las skills internas del perfil, y una skill importada habilitada
pero no seleccionada se rechaza. No se cambió producto para hacer pasar esa expectativa legacy.

## Diagnóstico de la fixture de recuperación de títulos

La ampliación expuso tres supuestos antiguos de la fixture, sin modificar el productor: ejecutaba
almacenamiento en main durante startup; creaba un checkpoint sin la conversación interna necesaria
para reconocer inputs aplicados; y esperaba reanudación explícita de un estado DONE válido. La
baseline anterior reprodujo el guard de afinidad y, tras mover sólo la fixture a worker, el fallback
por conversación ausente. Se mantiene la restricción READ_ONLY y todos los asertos de presentación.

La fixture corregida usa una sesión de ejecución real con fila durable. El caso DONE exige checkpoint
válido y ausencia de reanudación obligatoria; un segundo caso detiene realmente el modelo mediante
latch/cancelación y exige STOPPED, checkpoint durable, READ_ONLY y reanudación explícita sin reiniciar
el modelo. Los dos casos pasaron en el rerun ampliado de ambos sabores; no se infiere corrección sólo por inspección.

## Lo que sigue pendiente

Este bloque no conecta submission ni terminal async Coding. Faltan el journal operacional con
confirmación durable, reserva/enlace causal, preparación antes de la factoría fría, lanzamiento,
liveness y reconciliación sin replay, y la proyección terminal correspondiente. Enqueue no es OK de
comando; exit zero tampoco demuestra build, misión u objetivo correctos.

El audit técnico determinista no se omite por memoria OFF; el gate de memoria se aplica a nueva
semántica/narración. La etapa posterior debe preservar esa separación y las fronteras de locks/IO.
La integración ASYNC posterior requiere su propia implementación, revisión y pruebas end-to-end.
