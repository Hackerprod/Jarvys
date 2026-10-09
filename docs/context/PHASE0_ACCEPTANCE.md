# Aceptación de fase 0 de gestión selectiva del contexto

Estado: trabajo de desarrollo pendiente en UX34. Iniciar únicamente después de terminar todos los pendientes anteriores. Este archivo define pruebas y entregables; no contiene resultados ejecutados.

Contrato normativo: [SPEC.md](SPEC.md). La fase 0 prepara observabilidad/contratos internos y transporte de prueba. No implementa gestión de memoria ni habilita otras fases.

## 1. Allowlist de alcance

Se puede modificar lo estrictamente necesario en ModelReply, clientes/registro de proveedor y seams de transporte, más pruebas/fixtures sintéticos y documentación pertinente.

Resultados esperados:
- finalidad/motivo/identidad del mensaje e items propios del asistente distinguibles;
- uso desglosado opcional sin reinterpretar el contador antiguo;
- bytes realmente enviados capturables en tests con transporte/respuestas falsos;
- cobertura de dos familias de serializadores y sus cuatro configuraciones;
- selección legacy y campos existentes preservados para fixtures estándar válidos.

No se permite dentro de esta fase:
- añadir `<memoria>` a prompts o interpretarlo;
- cambiar selección/compactación de historial, aliases o protección;
- modificar esquemas/datos de conversaciones, migrar, purgar o resumir;
- activar DRY_RUN/APPLY real, cambiar permisos/cuentas o llamar a proveedores personales;
- instalar APK o publicar releases como parte de este encargo.

Una necesidad concreta fuera de esta allowlist se documenta para decisión; no se convierte automáticamente en alcance. Se puede compilar para comprobar el código, sin instalar ni presentar la build como entrega funcional de memoria.

## 2. Inspección inicial acotada

Registrar HEAD/base real y diff propio. Comparar únicamente cambios relevantes desde la auditoría, sin regresar a una referencia histórica ni sobrescribir trabajo ajeno. Revisar instrucciones del repositorio y propiedad del checkout/lock de build.

Confirmar firmas y consumidores actuales de ModelReply.contextTokensUsed; comprobar parsers de ambas familias, tratamiento de tool calls, SSE y excepciones. Preservar estos consumidores o introducir compatibilidad explícita. No reabrir una auditoría general para posponer las pruebas ya definidas.

## 3. Contrato diagnosticable

Los nombres de campos pueden adaptarse al código, pero deben poder expresar por separado:

1. finalización del transporte/respuesta: éxito confirmado, incompleto, filtrado, fallo, cancelación o desconocido;
2. intención del resultado: respuesta de texto, solicitud de herramientas u otra/no determinable;
3. identidad de cada texto propio de assistant y del mensaje final inequívoco;
4. motivo y response ID reales cuando existan;
5. uso reportado/desglosado opcional y procedencia de cálculos locales.

Una respuesta completada con tool calls NO equivale a respuesta final al usuario. EOF no demuestra éxito. Un ordinal local de item sirve para asociar fragmentos, pero no debe presentarse como ID remoto inventado. Cancelación/fallo no borra el cuerpo útil disponible ni lo transforma en éxito.

La nueva clasificación es diagnóstica en esta fase. No activa el nuevo comportamiento de historial ni un parser de footer. Si una corrección de parsing cambia un campo legacy visible, debe documentarse como divergencia concreta y revisarse; no cambiar silenciosamente el contrato general bajo una adición de metadatos.

## 4. Fixtures mínimos

Todos los datos serán sintéticos. Endpoints de test, transporte fake o servidor loopback; prohibir tráfico a cuentas/proveedores reales. Usar credenciales ficticias y no copiar tokens de desarrollo al fixture.

| ID | Entrada sintética | Resultado normalizado a demostrar |
|---|---|---|
| P0-01 | Chat Completions, choice con contenido y finish_reason=stop | Éxito confirmado; texto/choice identificado; respuesta final de assistant |
| P0-02 | Chat Completions, tool_calls y finish_reason=tool_calls, sin texto | Solicitud de herramientas válida; calls/argumentos/IDs intactos; no final de texto |
| P0-03 | Chat Completions, texto de progreso y tool_calls | Texto propio identificado sin tratarlo como respuesta final; calls intactos |
| P0-04 | Chat Completions, finish_reason=length y texto parcial | Incompleto; cuerpo conservado; no evidencia positiva de final completo |
| P0-05 | Chat Completions, content_filter | Filtrado; cuerpo disponible conservado; motivo no sustituido por stop |
| P0-06 | Chat Completions, finish_reason ausente/desconocido | UNKNOWN sin inventar éxito, aun con texto válido |
| P0-07 | Codex SSE, deltas/items y completed consistente | Éxito confirmado; items/deltas vinculados; final propio inequívoco cuando exista |
| P0-08 | Codex completed con function_call(s) | Transporte completo y solicitud de herramientas; no final al usuario |
| P0-09 | Codex incomplete con motivo/uso disponibles | Incompleto; contenido/uso disponibles preservados; no COMPLETED derivado de texto |
| P0-10 | Codex failed con error explícito | Fallo/motivo conservados; no final exitoso; semántica de excepción legacy documentada |
| P0-11 | SSE con texto y EOF sin completed | UNKNOWN/incompleto según evidencia, nunca éxito por EOF |
| P0-12 | SSE con `[DONE]` sin evidencia terminal suficiente | No sustituir falta de confirmación por un éxito inventado |
| P0-13 | SSE cortado dentro de JSON o delimitador, error de lectura | Estado/diagnóstico honesto y cero evidencia positiva de final; no reparar inventando JSON |
| P0-14 | SSE fragmentado byte a byte, incluyendo UTF-8 multibyte | Mismos items/texto/estado que la entrada completa válida |
| P0-15 | Varios items del asistente, fragmentos repetidos legítimos e IDs distintos | No fusionar identidad por substrings; conservar repetición legítima en representación normalizada |
| P0-16 | Items ambiguos/sin final único | Mantener items disponibles y final identity no disponible; no inventar unicidad |
| P0-17 | Cancelación local antes/durante respuesta | Cancelación distinguida; no control de memoria ni éxito tardío activo |
| P0-18 | Respuesta vacía/solo contenido no soportado | Vacío/contenido no soportado diagnosticado; conservar finalidad realmente reportada; no fabricar cuerpo/calls ni clasificarlo como respuesta final de texto exitosa |
| P0-19 | Texto ordinario con `<memoria>` literal | En fase 0 sigue el comportamiento legacy; no parser ni mutación de contexto |
| P0-20 | Retry simulado de autenticación tras 401, luego respuesta válida | Mismo cuerpo lógico y sessionId; renovación ficticia de credenciales; sin refresh real ni duplicación de items |

Los campos exactos de eventos deben corresponder a los formatos efectivamente soportados por los clientes. No incluir un alias de proveedor no implementado solo para aumentar el número de tests. P0-13 puede conservar la excepción existente: no exige retornar un ModelReply que antes no existía; sí exige no promover el fallo a éxito.

## 5. Uso y compatibilidad legacy

Fixtures separados:
- uso completo sintético: entrada 10, salida 5, total 15, cached input 4, reasoning 3; los subconjuntos no producen total 22;
- solo total: preservar 15 y dejar desgloses ausentes;
- entrada/salida sin total: preservar fallback antiguo documentado, marcando el cálculo;
- uso ausente: null/ausente, nunca ceros fingidos;
- uso malformed/negativo/inconsistente: diagnóstico conservador sin romper consumidores; documentar el comportamiento legacy que se mantiene o la divergencia requerida.

Validar que el significado de contextTokensUsed no cambia para MemoryReflectionWorker u otros consumidores. No calcular coste monetario en esta fase ni atribuir cache hits reales a campos de fixtures.

## 6. Captura efectiva de solicitudes

Capturar exactamente los bytes que el transporte falso recibe, no solo un JSONObject anterior a posibles modificaciones. El objeto capturado debe ser copia inmutable o bytes propios; una mutación posterior del builder no cambia la evidencia del intento ya registrado.

Cobertura:
- OPENAI_CODEX: instructions/input, mensajes, function_call/function_call_output y cabeceras de sesión relevantes;
- OPENAI_API, OPENROUTER y CUSTOM: modelo, system/messages, tool_calls/tool, schemas y opciones realmente configuradas;
- petición inicial de texto;
- petición posterior a tool-only y resultados;
- reintento de autenticación simulado;
- contenido sintético multimodal si el seam lo atraviesa, sin datos personales.

No activar todavía la ruta selectiva sin userPrompt adicional. En esta fase las aserciones describen el wire legacy existente; el cambio de historia preparada queda para fase 2. La prueba de coherencia completa contexto/configuración/fingerprint es gate de fase 2, no una capacidad que se dé por implementada al añadir el seam.

Capturas persistidas en fixtures/logs de test deben contener solo datos sintéticos. No añadir un interceptor productivo que vuelque conversaciones o Authorization reales.

## 7. Regresiones de herramientas y persistencia

Reutilizar las pruebas existentes de continuidad/checkpoints. Añadir únicamente casos necesarios para la frontera nueva:
- tool-only normalizado conserva IDs/nombres/argumentos y llega una vez a la ruta legacy;
- intención/STARTED/resultado siguen las barreras actuales en un store temporal de test;
- el diagnóstico no añade registros de mantenimiento, migraciones ni aliases al diario;
- recrear objetos de respuesta o un retry no duplica ejecución ni altera la relación llamada/resultado en los fixtures cubiertos;
- los registros canónicos preexistentes de test mantienen contenido; no simular datos reales.

La fase 0 no mejora ni certifica la reparación física del diario. A/B/C, hash chain, incrementalidad y escrituras ambiguas pertenecen a fase 1. No presentar esos riesgos como resueltos por pasar los tests anteriores.

## 8. Ejecución y entrega

El implementador registrará los comandos exactos que realmente ejecute. Como referencias a comprobar en la base real, las tareas del módulo Android incluyen pruebas Full/Play y compilación de ambas variantes. No ejecutar comandos supuestos solo porque están nombrados aquí ni usar `clean` sobre un checkout compartido en uso.

Secuencia recomendada:
1. tests enfocados de parser/finalidad/transporte/uso;
2. regresiones de registro de proveedores, tool continuity y checkpoints;
3. suites pertinentes de ambas variantes y compilación según impacto;
4. lint/checks requeridos con comparación de deuda heredada, sin presentarla como pasada limpia si devuelve fallo;
5. revisión de diff, fixtures y ausencia de selección/persistencia nueva.

Entregable de resultados:
- base/commit/diff y archivos/símbolos propios;
- matriz P0-ID → fixture/test → estado → cuerpo → identidad → uso;
- comandos, exit codes, número de casos y resultados reales;
- rutas de logs/capturas sintéticas, sin secrets;
- evidencia de legacy intacto y ausencia de activación de memoria;
- fallos, divergencias, pruebas no ejecutadas y lo que impida el gate P1.

No es suficiente un parser que siempre devuelva UNKNOWN o siempre SUCCEEDED. Deben pasar distinciones positivas y negativas. Mocks no prueban servicios remotos ni ahorro.

## 9. Salida de fase

Terminar después de P0 y presentar evidencia para revisión. No avanzar automáticamente a identidades/migraciones, DRY_RUN/APPLY o datos reales. La autorización de respaldar código/documentos no convierte esta fase diagnóstica en permiso de despliegue o mantenimiento real.
