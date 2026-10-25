# Correcciones S5: segunda revisión

Estado: completado. Se conservaron los cambios S5 previos. Sin commit, push ni APK; `docs/SINGLE_SESSION.md` no fue modificado. Gradle se ejecutó de forma secuencial, con un daemon activo a la vez.

## Cambios por incidencia

1. **Deduplicación y relaciones:** la rama `already_saved` ahora persiste `supersedes` y `related_ids` sobre el id deduplicado antes de retornar, dentro de la misma transacción. B1 repite el mismo texto tras crear candidatos y verifica ambos enlaces y la retirada del objetivo sustituido.
2. **Repetición de artefacto expulsado:** si el hash corresponde a un artefacto ya expulsado y continúa la presión, se conserva su fila, ruta, hash, tamaño y estado `expelled`; se devuelve una referencia coherente sin intentar registrar metadatos vacíos `unavailable`. B14 cubre guardar A, expulsar A y volver a guardar A bajo presión.
3. **Ownership de dos productores:** al guardar, el productor registra los metadatos del artefacto recién escrito sin verificar el mapa completo de archivos de una instancia obsoleta. B14 crea dos productores, expulsa A desde el segundo y comprueba que el primero puede guardar C.
4. **Revisión del cursor:** el cursor contiene una huella de las fuentes hasta su `highWater` (identidad, visibilidad, rol, fecha y partes de texto). Si cambian durante la paginación, se rechaza con `history_cursor_invalid`; mensajes nuevos posteriores al corte no mueven la página. B2 cubre ocultar durante la paginación y ocultar después de terminarla.
5. **Límite de `history_search`:** `PersonalMemoryTools` reduce el `limit` solicitado según el presupuesto de salida disponible y el tamaño serializado de metadatos mínimos por resultado. El `LIMIT max+1` del repositorio usa `Long`, evitando overflow de `Int.MAX_VALUE`.
6. **Presupuesto de `expand=source`:** el presupuesto por evidencia ahora se reparte entre `output` y cada `artifacts[].text`; los extractos emitidos comparten una bolsa por fuente. B14 suma conjuntamente todas esas cadenas al validar el presupuesto.
7. **Migración de reglas:** añadida la huella V3 exacta de HEAD al conjunto de textos migrables. B1 siembra esa versión, comprueba que migra y que una segunda inicialización no cambia la revisión.
8. **Términos FTS:** `%` y `_` ya no cuentan como términos buscables; junto a filtros estructurados activan la búsqueda solo por filtro. B5 cubre ambos símbolos y puntuación.
9. **Lectura de emoji:** `history_read` garantiza que una página de al menos un carácter avanza por punto de código y normaliza offsets que caigan dentro de un par sustituto. B2 verifica un emoji con `limit_chars=1` y la página siguiente.

**Pruebas adicionales adaptadas:** B14 comprueba el cierre de retención tras supersedes aislando su referencia y reaplicando presión; M1b conserva el artefacto de 16 MiB y añade una escritura de 1 byte que antes cruzaba la cuota retirada, exigiendo éxito.

## Builds y pruebas

Desde `app`, con `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`, `ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk` y `ANDROID_SDK_ROOT=%ANDROID_HOME%`:

1. `.\gradlew.bat :app:compileFullDebugKotlin :app:compileFullDebugJavaWithJavac :app:compilePlayDebugKotlin :app:compilePlayDebugJavaWithJavac :app:compileFullDebugUnitTestKotlin :app:compileFullDebugUnitTestJavaWithJavac --console=plain` — **BUILD SUCCESSFUL** (65 tareas).
2. `.\gradlew.bat :app:testFullDebugUnitTest --rerun --tests "*SingleSessionMemoryRetentionTest" --tests "*PersonalMemoryFts5Test" --tests "*MessageHistoryFts5Test" --tests "*PersonalAgentBehaviorTest" --tests "*M1bRepositoriesTest" --tests "*JarvysChatDatabaseM0Test" --tests "*MainChatToolContinuityTest" --tests "*StorageWorkspaceComposeTest" -x :app:prepareApkFactoryTemplate --console=plain` — **BUILD SUCCESSFUL**, 109 tests, 0 fallos/errores.
3. `.\gradlew.bat :app:testFullDebugUnitTest --rerun --tests "*MainAgentInteractionTest" -x :app:prepareApkFactoryTemplate --console=plain` — worktree y export HEAD: cada uno, 51 tests y 21 fallos; los nombres coinciden exactamente, sin fallos exclusivos.
4. `.\gradlew.bat :app:testFullDebugUnitTest --rerun --tests "*MemoryScopeRuntimeTest" --tests "*MemorySearchToolTest" -x :app:prepareApkFactoryTemplate --console=plain` — worktree y export HEAD: cada uno, 12 tests y 11 fallos; los nombres coinciden exactamente. Los fallos de aserción `possibleSecretDocumentsAreReturnedWithoutAnyContentFragments` e `invalidZonesAndLimitsFailAndSearchCannotTraverseOrReadOutsideFiles` también fallan en HEAD; no se atribuyen a rename/symlink.
5. `git diff --check` — **sin errores**.

## No verificado

No ejecuté la suite completa ni tests de la variante Play. No hice pruebas en dispositivo, no provoqué ENOSPC/presión real, no medí rendimiento con historiales grandes y no validé visualmente la navegación completa a Almacenamiento.

