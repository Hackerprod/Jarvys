# UX34: resultados de la fase 0

Estado: fase 0 diagnóstica implementada y validada en desarrollo; cerrada en su alcance acotado. La gestión selectiva del contexto continúa desactivada y las fases siguientes conservan sus gates.

## Base, alcance y evidencia

Base inspeccionada: `1a2836bd8aa9284a35cd3738c8f92054b343438e`. Fuente de fase 0 respaldada en `b3de69189017e088e8c0c534d82f482c4d0fe120`. No cambia la versión de la aplicación: 51 / 1.2.44-UX33. Las compilaciones de comprobación son sin firma; no son una entrega funcional de memoria.

Todos los fixtures nuevos son sintéticos. Las pruebas atraviesan el escritor y lector HTTP reales con una `HttpURLConnection` falsa; no abren sockets, hacen DNS real, usan cuentas ni llaman a proveedores externos. Las credenciales del fixture son ficticias. Las afirmaciones sobre servicios remotos, consumo facturado, caché real, rendimiento y ahorro permanecen **NO VERIFICADAS**.

La revisión independiente de código comprobó que los campos legacy, los builders de solicitudes, los errores, la renovación y las cabeceras conservan su comportamiento. Durante esa revisión se añadieron dos casos de regresión del estado de items/respuestas: un item posterior activo o desconocido, o una respuesta que regresa a in_progress/queued, no pueden conservar evidencia terminal obsoleta. La revisión no sustituye los resultados ejecutados indicados abajo.

## Archivos y símbolos

Rutas relativas a `app/app/src/main/java/com/jarvys/agent/`:

- `ModelReply.java`: añade `diagnostics`; el constructor existente delega al nuevo con `ResponseDiagnostics.unknown()`. No cambia `text`, `calls`, `rawResponseBody`, `httpStatus`, `model` ni `contextTokensUsed`.
- `ResponseDiagnostics.java`: contrato inmutable de finalización, motivo, identidad local/remota, items propios del asistente, final inequívoco y uso opcional. `isSuccessfulFinalAnswer()` exige éxito confirmado, texto final propio y ausencia de tool calls.
- `ResponseDiagnosticsParser.java`: diagnóstico de Chat Completions y del cuerpo SSE/JSON bruto de Codex. No reemplaza los parsers legacy.
- `ProviderHttp.java`: `ConnectionFactory` y overload interno para pruebas. Los overloads package-private existentes conservan la apertura de conexión habitual.
- `OpenRouterClient.java`: constructor interno con transporte inyectable y diagnóstico de la respuesta recibida. Cubre OPENAI_API, OPENROUTER y CUSTOM, que comparten serializador.
- `OpenAICodexResponsesClient.java`: proveedor de credenciales y transporte inyectables por instancia para pruebas; añade diagnóstico del cuerpo bruto después del parsing existente. La ruta pública y las operaciones de imagen conservan sus valores por defecto.

Pruebas nuevas en `app/app/src/test/java/com/jarvys/agent/`:

- `ResponseDiagnosticsTest.java`: 51 casos de contrato/parser/uso/límites.
- `ProviderDiagnosticsTransportTest.java`: 13 casos de transporte, cuatro configuraciones, dos familias, continuidad, cancelación y persistencia legacy.

No hay dependencias nuevas, migración, almacenamiento nuevo, instrucciones adicionales, aliases de memoria, intérprete de `<memoria>` ni conexión de los nuevos metadatos a la política del loop.

## Contrato comprobable

`Completion` separa `SUCCEEDED`, `INCOMPLETE`, `FILTERED`, `FAILED`, `CANCELLED` y `UNKNOWN`. Una respuesta completa que pide herramientas no es una respuesta final al usuario. EOF y `[DONE]` por sí solos no demuestran éxito. Evidencia contradictoria no se resuelve inventando un final.

Cada item conserva una identidad local de diagnóstico (`key`) distinta del ID remoto (`providerId`, nullable), índice, texto, estado, fase, origen y finalidad. Esas claves no son IDs canónicos ni aliases durables del diseño de memoria. Textos iguales con IDs distintos y deltas repetidos legítimos se conservan. Si hay varios candidatos sin un final inequívoco, `finalAssistantItemKey` permanece ausente.

Los contadores nuevos son opcionales. La ausencia no se convierte en cero. Entrada cacheada y razonamiento son subconjuntos, no sumandos extra del total. Un total calculado a partir de entrada/salida se distingue del reportado. Uso inválido/inconsistente queda señalado sin alterar el contador legacy que consumen, entre otros, `MemoryReflectionWorker` y `CoreAgentLoop`.

El parser diagnóstico limita únicamente su trabajo adicional a 1.048.576 caracteres de cuerpo, 1.024 items acumulados de mensaje y elementos por array choices/output, y profundidad JSON 128 (no es un límite global de eventos SSE o llamadas). En exceso devuelve evidencia desconocida y `DIAGNOSTIC_LIMIT`. Esto no limita la respuesta legacy, herramientas, historial o tareas; evita que el diagnóstico añadido sea una vía ilimitada de memoria/recursión. No se añade logging de cuerpos o credenciales.

## Matriz de aceptación P0

Los métodos de parser están en `ResponseDiagnosticsTest`; los de transporte, en `ProviderDiagnosticsTransportTest`. Cada fila identifica evidencia reproducible, no una prueba contra un servidor real.

| Caso | Fixture o método | Evidencia esperada y comprobada por el test |
|---|---|---|
| P0-01 | `p001StopIdentifiesTheAssistantChoice`; `threeChatConfigurationsUseActualWireAndKeepLegacyFields` | Stop confirma éxito; choice propio identificado; cuerpo y campos legacy iguales en las tres configuraciones Chat. |
| P0-02 | `p002ToolOnlyCompletionHasNoFinalText`; `twoToolRoundsUseActualLegacyWireAndDurableRecordsWithoutMemoryActivation` | Solicitud de herramientas distinguida de final; el replay comprueba dos ejecuciones/rondas y tipos de registros. La igualdad de IDs y contenido de resultados Chat tiene cobertura separada en los builders legacy, no una aserción nueva exhaustiva del wire. |
| P0-03 | `p003ProgressAlongsideCallsIsNotFinal` | Texto de progreso propio disponible; herramientas impiden clasificarlo como final. |
| P0-04 | `p004LengthPreservesPartialText`; `chatLengthAndMissingFinishReasonKeepLegacyBodyAndUsage` | INCOMPLETE; cuerpo/uso legacy conservados. |
| P0-05 | `p005ContentFilterIsNotStop` | FILTERED; motivo real, sin éxito inventado. |
| P0-06 | `p006MissingAndUnknownReasonsStayUnknown` | UNKNOWN aunque haya texto. |
| P0-07 | `p007SseDeltasAndCompletedSnapshotShareIdentity`; `codexActualWriterPreservesHeadersBodyMultibyteAndMetadata` | Deltas/snapshot vinculados y final inequívoco cuando existe evidencia. |
| P0-08 | `p008CompletedFunctionCallIsTransportSuccess`; `codexToolOnlyAndContinuationCaptureFunctionProtocolAtActualWriter` | Éxito de respuesta con herramientas; function_call/function_call_output conservados en la solicitud siguiente. |
| P0-09 | `p009IncompletePreservesReasonTextAndUsage` | INCOMPLETE; motivo, cuerpo y total disponibles conservados. `fullUsageKeepsSubsetsSeparateInBothFamilies` comprueba por separado los desgloses. |
| P0-10 | `p010FailedKeepsAvailableTextAndProviderErrorCode` | FAILED y código disponible. Un evento response.failed dentro de HTTP 200 no provoca por sí mismo una excepción legacy: conserva el parsing existente y añade FAILED. Las excepciones HTTP/lectura/JSON siguen intactas. |
| P0-11 | `p011EofAfterTextDoesNotProveResponseSuccess`; `codexTransportEofAndIncompleteRemainDiagnosticNotLegacyPolicyChanges` | EOF con texto permanece UNKNOWN. |
| P0-12 | `p012DoneSentinelCannotSupplyTerminalEvidence` | `[DONE]` no sustituye terminal confirmado. |
| P0-13 | Ambos métodos `p013...`; `truncatedTransportBodyDoesNotFabricateCompletedReply`; `malformedSseRetainsLegacyParseFailure` | Truncamiento de cada prefijo terminal, JSON malformado y error de lectura no fabrican éxito. Se preservan las excepciones existentes. |
| P0-14 | `codexActualWriterPreservesHeadersBodyMultibyteAndMetadata` | Lectura byte a byte con UTF-8 multibyte produce el texto/identidad correctos. |
| P0-15 | Ambos métodos `p015...` | Repeticiones legítimas no se deduplican por substring; IDs distintos siguen separados. |
| P0-16 | Métodos `p016...`, conflictos de IDs y regresiones de estado | Final ambiguo ausente; fase final explícita puede desambiguar; origen/ID desconocidos no se inventan. |
| P0-17 | `p017LocalCancellationWinsAndRetainsAvailableEvidence`; ambos tests de cancelación de transporte | Cancelación prevalece en metadatos; la ruta cliente sigue lanzando su cancelación y desconectando, sin producir respuesta tardía exitosa. |
| P0-18 | `p018EmptyOrUnsupportedCompletedResponseRetainsTransportSuccess` | Puede confirmarse transporte sin fabricar texto final o calls. |
| P0-19 | `p019MemoryLikeTextIsPreservedWithoutInterpretation`; replay de dos rondas | El pie literal sigue como texto legacy; no se aplica mantenimiento. |
| P0-20 | `codex401RetryUsesSameRequestAndSessionWithFakeRefreshOnly` | Dos intentos con mismo cuerpo/sessionId y renovación ficticia; no refresh real. |

Los tests adicionales cubren: uso completo/solo total/ausente/cero real/cálculo/inconsistencia/overflow, múltiples choices, estados contradictorios, dos response IDs, SSE CRLF/comentarios/data multilínea, límites diagnósticos e inmutabilidad.

## Wire efectivo, herramientas y baseline

El transporte falso captura bytes copiados desde `getOutputStream()`, no solo el JSON anterior al envío. Comprueba modelo, system/messages o instructions/input, declaraciones, opciones configuradas, cabeceras/sessionId y dos intentos de autenticación. La captura Codex comprueba los campos de function_call/function_call_output; el replay Chat comprueba rondas, tamaños y registros. La preservación de IDs y contenido de resultados Chat se apoya además en los builders cubiertos por `MainChatToolContinuityTest`, sin atribuir todas esas aserciones al nuevo transporte. El fixture multimodal verifica partes de imagen sintéticas en ambas familias sin duplicar el mensaje de usuario actual. No certifica todas las combinaciones multimodales ni servidores locales arbitrarios.

El replay usa el `CoreAgentLoop` real, dos rondas de herramientas, `LocalRunStore` y `MainChatTranscriptStore` en un directorio temporal. Comprueba seis filas totales del transcript, dos grupos de llamadas, dos resultados y recuperación del historial sin volver a ejecutar efectos. El test no cuenta individualmente cada categoría INTENT/STARTED/RESULT. También conserva la continuación legacy `Continue.`. No prueba recuperación tras caída física del proceso ni reparación A/B/C del diario; esas obligaciones siguen en fase 1.

La referencia de compactación sigue siendo `ConversationCompactor`/`ConversationCompactionPolicy` y su uso en `CoreAgentLoop`: preflight, post-turn, overflow y compactación manual. `ConversationCompactionTest` cubre las reservas por ventana, pares de herramientas, reconstrucción append-only, cancelación y reintento acotado; las suites existentes de Crew/checkpoint completan la regresión. No se sustituye esa referencia por un historial artificialmente sin compactación.

La protección de diez interacciones, snapshot coherente de configuración/fingerprint, proyección activa, recuperación selectiva y preflight del nuevo modo todavía son contratos futuros. El seam captura una petición concreta; no demuestra la atomicidad de todas las fuentes que la originaron. Tampoco certifica el algoritmo incremental de journal. No se presenta ahorro o calidad semántica a partir de fixtures mecánicos.

## Validación ejecutada

Pruebas frescas sobre los 849 archivos de aplicación congelados, sin cambios durante la ejecución:

| Comprobación | Resultado |
|---|---|
| Parser aislado con Java 8/JUnit | 51/51, exit 0 |
| Enfoque parser/transporte y regresiones previas | 105 tests, cero fallos/errores/omitidas; ejecución anterior a los dos últimos casos de regresión, que sí están en las suites finales |
| Full Debug, suite completa | 2.196 tests en 306 clases; cero fallos, errores u omitidas; exit 0 |
| Play Debug, suite completa | 1.814 tests en 267 clases; cero fallos, errores u omitidas; exit 0 |
| Compilación Full/Play sin firma | Ambas completadas; exit 0 |
| Lint Full/Play comparado con base | Exit 1 por deuda heredada: Full 46 errores/270 avisos/4 hints; Play 37/266/4. Cero incidencias nuevas o resueltas |

Las suites finales incluyen los 64 tests nuevos en cada variante. La referencia anterior tenía 2.132/1.750; no se descartaron tests para obtener los resultados. Entre las regresiones preservadas están ConversationCompactionTest (13), CrewConversationCompactionRecoveryTest (7), CoreAgentLoopCheckpointRecoveryTest (8), CoreAgentLoopFailureRecoveryTest (6), CoreAgentLoopTest (5) y MemoryReflectionTest (12).

Comandos efectivos desde `app/`, tras cargar el toolchain local ya existente:

```sh
# Ejecutados por separado para Full y Play, bajo el lock compartido de Gradle.
./gradlew --offline --no-daemon \
  -I "$TOOLCHAIN/test-runtime.init.gradle" -I "$VALIDATION/fresh-tests.init.gradle" \
  --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
  -Pkotlin.compiler.execution.strategy=in-process -PunsignedBuild=true \
  :app:testFullDebugUnitTest
# Mismas opciones, con :app:testPlayDebugUnitTest.

./gradlew --offline --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs=-Xmx1536m -Pkotlin.compiler.execution.strategy=in-process \
  -PunsignedBuild=true :app:assembleFullDebug :app:assemblePlayDebug

./gradlew --offline --no-daemon --continue --max-workers=1 \
  -Dorg.gradle.jvmargs=-Xmx2048m -Pkotlin.compiler.execution.strategy=in-process \
  -PunsignedBuild=true :app:lintFullDebug :app:lintPlayDebug
```

`TOOLCHAIN` y `VALIDATION` representan directorios locales de ejecución, no variables requeridas por la aplicación. El init de pruebas usa el caché Robolectric existente; el init fresco fuerza la ejecución, un fork paralelo, heap de 1.280 MiB y recambio de JVM cada 60 clases. No se hizo clean del checkout. La primera invocación aislada de javac sin ruta no estaba disponible; se repitió usando el JDK existente, que produjo 51/51. Las suites Gradle posteriores son la evidencia principal.

Los reportes reproducibles de Gradle quedan en `app/app/build/test-results/testFullDebugUnitTest/`, `testPlayDebugUnitTest/` y `app/app/build/reports/`. Los cuerpos sintéticos y aserciones están versionados en los tests citados; no se publican volcados de ejecución, credenciales o datos de conversaciones. El comprobador local verificó fecha fresca de los XML, conteos y hash de los 849 archivos. Lint rehízo su análisis, pero Gradle inicialmente reutilizó reportes idénticos; se ejecutaron `:app:lintReportFullDebug :app:lintReportPlayDebug` con las mismas opciones de compilación y un init que fuerza únicamente esos dos reportes (`outputs.upToDateWhen { false }`). Esa regeneración devolvió exit 0; no equivale a lint limpio. La comparación por ID/severidad/mensaje/archivos, ignorando desplazamientos de línea, encontró cero incidencias nuevas o resueltas. Los reportes frescos tienen SHA-256 Full `c5a5b0041a8f4b4b281f2aa3167bd5e66c434666b6703d62d30a5b5fff15c823` y Play `df1bde55b3da3bcbddc36ca5ee5c0bb73a66887c9f0908e34bfd57af32f710db`.

El commit de fuente no tiene statuses ni workflow runs de GitHub asociados en la comprobación realizada. La evidencia aquí es ejecución local verificada; no se presenta como CI remota aprobada.

## Límites y gate siguiente

- No se ejecutó ninguna propuesta de PURGAR/RESUMIR, ni simulación automática de ese protocolo.
- No se probaron cuentas externas, facturación, caché remota, instalación o comportamiento de dispositivos físicos.
- La normalización no certifica fidelidad de resúmenes ni decisiones de purga; tampoco convierte un diagnóstico favorable en autorización.
- Los futuros modos LEGACY/DRY_RUN/APPLY/PAUSED, aliases y originales requieren las fases y gates de SPEC.md.
- No iniciar fase 1 por el mero cierre de P0. Primero revisar esta evidencia, resolver cualquier diferencia y respetar el orden de los pendientes de producto. UX38, UX35, UX36 y UX37 están por delante de la continuación de memoria.
