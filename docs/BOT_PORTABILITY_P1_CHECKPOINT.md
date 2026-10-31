# Portabilidad de bots — checkpoint P1 aceptado en host

**31-10-2026 · PASS independiente R3, acotado a implementación y pruebas host.** Código revisado: `6bfd252a7e516cc25144194913a93b13b4dc8c67`. No es un PASS de dispositivo físico, proveedor vivo, APK ni despliegue.

Este checkpoint actualiza el estado de revisión que quedó pendiente en el [registro de implementación](BOT_PORTABILITY_P1_IMPLEMENTATION.md), sin borrar sus resultados intermedios. Las revisiones R1 y R2 siguen siendo FAIL históricos; sus defectos fueron corregidos y revalidados en R3.

## Alcance autorizado y conservado

La autorización del usuario de comenzar la implementación resolvió **D1 para implementar por fases** el [roadmap aprobado](BOT_PORTABILITY_ROADMAP.md), después del PASS P0. No derogó los demás gates ni autorizó cuotas nuevas de costo/autonomía.

Se conserva **un solo Central**, agentes generalistas, memoria Engram nativa con namespaces existentes y el menú restaurado de v97. No se agregan chats, memoria/outbox paralelos, privilegios heredados del Chief, rediseño de UI, migraciones ni borrado de datos. La base visual v97 sigue identificada por `bc3639918b36cf0af7831d057c29b90e6f43001e`; su aceptación física pendiente no se presume resuelta por estas pruebas.

P1 implementa el recorrido nativo opt-in: delegación → pregunta durable y suspensión exclusiva → respuesta retenida → Reanudar explícito en ciclo nuevo → resultado exacto → una continuación de Central. La recuperación exige fuente, identidad, permisos y punto seguro verificables; un efecto incierto no se vuelve a ejecutar automáticamente.

La reconciliación de catálogo/lectura no concede autoridad de ejecución. Un resultado vivo requiere su permiso exacto del proceso y productor actuales. Si sólo queda un resultado histórico PREPARED, el usuario puede escribir en Central **`/continue_result <result_id>`**; la herramienta valida el comando USER vigente y la fuente seleccionada. No reinicia STARTED incierto. STOP cancela el dueño exacto incluso después de ACK o en la ventana entre claim durable y registro del dueño; no alcanza generaciones posteriores. Resultados históricos inelegibles no bloquean resultados válidos hermanos, pero la corrupción no se silencia.

## Evidencia final, sin sumar cohortes

| Validación | Resultado |
|---|---|
| Aceptación Full | 90 clases / **798 casos**, cero fallos, errores u omisiones |
| Aceptación Play | 90 clases / **798 casos**, cero fallos, errores u omisiones |
| Reejecución independiente focal Full | 6 clases / **62 casos**, cero fallos, errores u omisiones |
| Reejecución independiente focal Play | 6 clases / **62 casos**, cero fallos, errores u omisiones |

El evaluador verificó identidad de fuentes/artefactos y los XML de ambas cohortes completas; reejecutó independientemente sólo las cohortes focales indicadas. Fuentes y artefactos permanecieron invariantes durante las ejecuciones. Las cohortes focales, D3 y los intentos anteriores no se suman a los 798 casos por sabor.

Identidades auditables:

- Roadmap aprobado SHA-256: `727e8e52a932d4a0b413d4a0fae8764b1ffa8384a07dca902c18f9b5207039b0`.
- Registro de implementación revisado SHA-256: `b7718160c909a37dfc5b846cfe6110975995d7ba1286a035159ae89f5d748585`.
- Revisión independiente R3 SHA-256: `25388e51d787a84d506dfe247904797d212e97822cc3429e682ac7eeab07e502`.
- Terminal de aceptación Full SHA-256: `aab8bf3c0cca75b3b37a643ae12054f059aa1befd730b729e055381f71bd82ab`.
- Terminal de aceptación Play SHA-256: `e07a4a91c7a7598ca8cf965afd3fc7e6beb72d19c71bce691825d8ce33a63303`.

Los artefactos privados de evaluación y registros no forman parte de este documento público.

## Compatibilidad y siguiente gate

D3 se ejecutó antes de habilitar nuevas escrituras. Se mantiene la lectura de referencias TEXT históricas; las nuevas referencias exactas usan BLOB UTF-16 para preservar NUL/surrogates sin reescribir las anteriores. No hay migración ni eliminación. **El downgrade binario a v97 con episodios/referencias nuevos no está soportado.** El rollback previsto deshabilita nuevas admisiones conservando lectores, guards y evidencia; no supone borrar datos ni reinstalar.

- **P2 bloqueado por decisión del usuario D2:** límites numéricos por request/árbol, profundidad, fan-out, llamadas/costo, política ANY y disposición de hijos en cola/ejecución al detener al padre frente a cancelar explícitamente un subárbol. No se activan valores nuevos ni presupuesto ilimitado por defecto.
- **P3, gate de entrega del slice P1:** PASS host del APK v98; todavía sin nueva entrega, instalación ni verificación física. Construcción, identidad/firma y recursos verificados en el checkpoint posterior. No se espera terminar P2 para preparar esa entrega acotada.
- No se acredita Android físico, force-stop/Doze/reinicio real, selección autónoma o llamadas a proveedor vivo, meses reales transcurridos, aceptación visual en teléfono ni paridad integral upstream. Las obligaciones de memoria/administración anteriores permanecen abiertas donde no fueron cerradas expresamente.


## Gate posterior v98 — PASS independiente host y APK

El source candidato v98 (`98`, `1.2.91-BOT-CONTINUITY`) pasa en host Full y Play **92 clases / 833 casos únicos por sabor**: 90/798 de continuidad más 2/35 de contexto protegido/preparado. Conserva el PASS P1 anterior como histórico separado. Una regresión adicional detectó metadata concurrente antes del proveedor; la corrección acotada y sus 12 casos deterministas están descritos en el [ledger de implementación](BOT_PORTABILITY_P1_IMPLEMENTATION.md). No extiende P2 ni permite replay. Firma, APK y delta recibieron PASS independiente sobre el source `a50dd3ab9b4029af3a88a5260e59183349bacc4e`; se reejecutaron 64 casos por sabor. [Artefacto exacto y límites](BOT_PORTABILITY_V98_DELIVERY.md). No es una entrega ni validación física.
