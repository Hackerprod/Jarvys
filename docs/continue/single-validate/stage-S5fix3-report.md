# Correcciones S5: tercera revisión

Estado: terminado. Cambios S5 previos preservados. Sin commit, push ni APK; `docs/SINGLE_SESSION.md` no se modificó. Gradle se ejecutó secuencialmente con un solo daemon.

## Cambios por incidencia

1. **IMPORTANTE — paginación de historial:** `history_search` ordena por `seq DESC` con keyset (`highWater`, `beforeSeq`) y fingerprint de conversación, consulta y filtros. BM25 no es requisito de orden en `docs/SINGLE_SESSION.md` 7.3 ni en `docs/context/SPEC.md` 10; eliminé el score de la respuesta en vez de devolver una puntuación artificial. Mensajes nuevos quedan para una búsqueda nueva; mensajes ocultados, borrados o regenerados dejan de aparecer sin desplazar el cursor. El fallback LIKE aplica el mismo orden.
2. **IMPORTANTE — lectura completa por huella:** el cursor ya no relee ni calcula SHA de mensajes o partes. La prueba FTS5 confirma acceso por índice virtual, ausencia de `SCAN messages` y ausencia de `OFFSET`.
3. **MENOR — metadatos del upsert:** ya estaba corregido en el árbol: `unchanged` incluye layer, category e importance. B1 verifica cambio de metadatos con texto idéntico.
4. **MENOR — `memory_correct`:** ya estaba corregido: el recibo cuenta revisiones de `newId`. B1 compara el recibo con `memory_get` del reemplazo; el nuevo id queda con una revisión.
5. **MENOR — empate de compactación:** ya estaba corregido: el desempate estable por secuencia protege solo una compactación. B14 fuerza límites iguales y verifica la selección.
6. **Carrera de checkpoints confirmada:** el callback ahora mantiene el lock compartido por sesión desde el staging hasta el commit de las filas del checkpoint. B14 verifica que el callback espera por el lock y que los artefactos se publican después de liberarlo.

## Pruebas añadidas/adaptadas

- `MessageHistoryFts5Test`: cubre orden por secuencia, inserción de términos coincidentes entre páginas, cambios de visibilidad y plan de consulta sin barrer `messages`. Evita la dependencia de estadísticas BM25 globales.
- B2 en `SingleSessionMemoryRetentionTest`: cubre escrituras, ocultación, borrado y regeneración entre páginas mediante la ruta de herramienta/repositorio; mensajes posteriores al corte no entran en el cursor existente.
- B1/B14 ya cubrían metadatos sin cambio de texto, revisión de `memory_correct` frente a `memory_get` y empate de `first_kept`.
- Nuevo test B14 sobre bloqueo del callback para cubrir la ventana de retención detectada entre staging y commit.

## Compilación y pruebas

Desde `C:\Projects\Jarvys-wt\single\app`, con `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`, `ANDROID_HOME=$env:LOCALAPPDATA\Android\Sdk` y `ANDROID_SDK_ROOT=$env:ANDROID_HOME`:

1. `.\gradlew.bat :app:compileFullDebugKotlin :app:compileFullDebugJavaWithJavac :app:compilePlayDebugKotlin :app:compilePlayDebugJavaWithJavac :app:compileFullDebugUnitTestKotlin :app:compileFullDebugUnitTestJavaWithJavac --console=plain` — **BUILD SUCCESSFUL** (65 tareas).
2. `.\gradlew.bat :app:testFullDebugUnitTest --rerun --tests "*SingleSessionMemoryRetentionTest" --tests "*PersonalMemoryFts5Test" --tests "*MessageHistoryFts5Test" --tests "*PersonalAgentBehaviorTest" --tests "*M1bRepositoriesTest" --tests "*JarvysChatDatabaseM0Test" --tests "*MainChatToolContinuityTest" -x :app:prepareApkFactoryTemplate --console=plain` — **BUILD SUCCESSFUL**, 109 tests, 0 fallos.
3. `git diff --check` — sin errores.

Las primeras iteraciones detectaron y corrigieron: un contexto de uso inválido de `bm25()`, un bind sobrante del fallback LIKE al retirar la puntuación, una expectativa de revisión incorrecta en la prueba y datos de usuario ausentes en el fixture del checkpoint. El resultado final anterior corresponde al estado corregido.

## No verificado

No ejecuté la suite completa ni tests Play; Play sí se compiló. No probé en dispositivo el índice SQLite del framework Android, rendimiento con historiales reales, ENOSPC/presión del sistema, ni sincronización entre procesos distintos. La prueba del plan SQL y los tests Robolectric/SQLite incluido cubren la ruta de consulta y la intercalación dentro del proceso.

