# S5 fix 4

## Resultado

Se conservaron todos los cambios S5/correcciones que ya estaban sin commit. No hice commit, push ni APK y no edité `docs/SINGLE_SESSION.md`.

## Cambios por incidencia

1. **Cursor e invalidación de fuentes:** agregué `conversations.history_revision` durable (schema v3, default `0`). El productor único de mutaciones incrementa la revisión dentro de la misma transacción al actualizar/finalizar el cuerpo de un mensaje existente, ocultar/borrar o regenerar. Los inserts de appends no la incrementan. El cursor codifica la revisión y el repositorio la compara dentro de la misma transacción de lectura; si difiere, devuelve `history_cursor_invalid`. No se vuelve a hashear ni leer el historial para validar.
2. **Checkpoint y expulsión concurrente:** la prueba pausa el checkpoint justo después de staged de filas de artefacto y antes de confirmar el journal. Un segundo productor intenta archivar bajo presión; se verifica que queda bloqueado en el lock compartido hasta liberar la confirmación. Añadí un hook de prueba en `MainChatTranscriptStore` para controlar ese punto.
3. **`memory_forget`:** B14 crea una llamada/artefacto distinto y un episodio cuya única evidencia apunta a esa llamada. Tras olvidar el episodio, aplica presión y comprueba que su evidencia aún protege el artefacto y que el nuevo contenido queda unavailable.
4. **Plan FTS:** la prueba crea los índices representativos de `messages`, exige búsqueda por el índice y rechaza barridos de los alias `m` y `p` sin confundir el `SCAN message_fts` virtual con un barrido de mensajes.

Actualicé `JarvysChatDatabaseM0Test` y schema v3. El hash final exportado es `e227967aad0ac2a83f3a446f79970255`.

## Tests añadidos/adaptados

- **B2:** append-only conserva el cursor anterior y excluye IDs posteriores por `highWater`; regeneración y edición entre páginas rechazan explícitamente el cursor anterior.
- **B14 checkpoint:** ahora reproduce la espera entre staging y confirmación; una implementación que suelte el lock antes de confirmar falla porque el segundo productor no quedará bloqueado.
- **B14 forget:** separa el episodio/call olvidado de las evidencias que sobrevivieron al supersede anterior.
- **MessageHistoryFts5Test:** usa índices y alias equivalentes al SQL/esquema real.
- **JarvysChatDatabaseM0Test:** comprueba que la columna de revisión esté en el schema de la base real y en el export.
- Incluí `ConversationRepositoryTest` porque añadí bumps de revisión en ese productor.

## Verificación ejecutada

- `:app:compileFullDebugKotlin :app:compileFullDebugJavaWithJavac :app:compilePlayDebugKotlin :app:compilePlayDebugJavaWithJavac :app:compileFullDebugUnitTestKotlin :app:compileFullDebugUnitTestJavaWithJavac --console=plain` — **BUILD SUCCESSFUL** (9 s; 65 tareas, 3 ejecutadas, 62 actualizadas).
- `:app:testFullDebugUnitTest --rerun --tests "*SingleSessionMemoryRetentionTest" --tests "*PersonalMemoryFts5Test" --tests "*MessageHistoryFts5Test" --tests "*PersonalAgentBehaviorTest" --tests "*M1bRepositoriesTest" --tests "*JarvysChatDatabaseM0Test" --tests "*MainChatToolContinuityTest" --tests "*ConversationRepositoryTest" -x :app:prepareApkFactoryTemplate --console=plain` — **BUILD SUCCESSFUL** (43 s; 152 tests, 0 fallos, 0 errores).
- `git diff --check` — sin errores.

## No verificado

No ejecuté la suite completa. Tampoco probé Android/dispositivo real, ENOSPC real, rendimiento en dispositivo, navegación visual completa, ni concurrencia entre procesos. No generé APK.
