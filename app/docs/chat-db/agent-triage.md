# Triage de pruebas retiradas: agente personal

`ConversationRepositoryTest.rawRepositoryInsertsProvideEveryRequiredSchemaColumn` sigue auditando tablas Room reales; omite INSERTs de la tabla virtual FTS5, cuya sincronización se cubre en `PersonalMemoryFts5Test` con SQLite empaquetado porque el SQLite de Robolectric no incluye FTS5. La prueba de pantalla espera el aviso de guardado después de que Compose reponga la fila de perfil.

La migración del flujo activo de memoria a Room retira las pruebas que exigían la UX de `/memory/` o el reflection worker basado en archivos. Se sustituyen por cobertura del repositorio personal y de la pantalla nueva; las aserciones de privacidad, evidencia, captura y manejo de errores que siguen vigentes se conservan en pruebas del nuevo flujo.

| Prueba retirada | Motivo concreto | Cobertura sustituta |
|---|---|---|
| `MemoryNavigationComposeTest` | Navegaba la antigua pantalla/file store; ya no representa la pantalla de memoria activa. | `PersonalMemoryScreenTest.blankDatabaseShowsEmptyProfileAndUserCanSaveItFromTheNewScreen` cubre perfil vacío, escritura y recibo visible. |
| `MemoryUiProtectionComposeTest` | Sus protecciones estaban acopladas a controles y datos del `MemoryStore` antiguo. | `PersonalMemoryScreenTest` cubre el flujo de edición nuevo; `PersonalAgentBehaviorTest.scenario06_privateConversationHasNoGlobalFactsOrMemoryTools` prueba la barrera de privacidad del runtime. |
| `MemoryScopeReviewComposeTest` | La revisión visual de snapshots compartidos entre chats fue sustituida por la política de scope `user`/privado del nuevo modelo. | `PersonalAgentBehaviorTest.scenario06_privateConversationHasNoGlobalFactsOrMemoryTools` y `05_supersedeIsAtomicAndStaleRevisionCannotReplaceTheWinner`. |
| `MemoryScopeReviewSessionTest` | Probaba compartir archivos de memoria entre conversaciones y no los scopes Room actuales. | `PersonalAgentBehaviorTest.scenario06_privateConversationHasNoGlobalFactsOrMemoryTools` conserva la aserción vigente de que la sesión privada no lee ni escribe memoria global. |
| `MemoryReflectionTest` | Probaba la extracción histórica a `/memory/` mediante `MemoryReflectionCoordinator`; ese coordinador se retiró del camino activo para no leer archivos legacy. | `PersonalAgentBehaviorTest.scenario07_untrustedExternalTurnIsExcludedFromSilentFlush`, `09_secretsAreRejectedAndRedactedBeforeMaintenanceModelSeesTranscript` y `memoryJobLeaseUsesCompareAndSetForClaimAndCompletion` prueban las garantías pertinentes del flujo nuevo. |

Se conservan las pruebas de componentes legacy que aún verifican contratos de esas clases aisladas, por ejemplo `MemoryStoreTest` y `MemoryReflectionPreferencesMigrationTest`. No validan ni son invocadas por el nuevo flujo personal; cualquier limpieza de esas clases y sus tests queda fuera de este cambio. La validación por impacto terminó con `ALL DONE crashedBatches=0` y cero fallos nuevos.
