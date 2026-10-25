# Informe de correcciones S5

Estado: correcciones implementadas. Sin commit, push ni APK. `docs/SINGLE_SESSION.md` y otros worktrees no se tocaron.

## Cambios por incidencia

1. **Cierre protegido y evidencia retirada.** `ArtifactRetentionRepository` calcula el cierre desde todas las filas de `memory_evidence`, sin filtrar por estado de la entrada. B14 ahora comprueba que `memory_save(supersedes_id=episodio)` y `memory_forget` no liberan los artefactos referenciados.
2. **Compactaciones.** Solo se protege el artefacto de la compactación con el menor `first_kept` posterior al mensaje del lote. B14 crea dos compactaciones y verifica que la segunda queda expulsable.
3. **Enlace al guardar sin cambios.** El upsert de topic existente escribe `supersedes` antes de retornar `already_active`. Prueba B1 nueva.
4. **Consulta de puntuación con filtro.** Si la puntuación no produce términos y hay filtro, la búsqueda ejecuta la consulta solo por filtro. Prueba B5 nueva.
5. **Candidatos de solapamiento.** Usa los términos normalizados de `SearchTerms`, en consultas individuales para mantener bajo el límite de variables SQLite, sin recortar el texto con un máximo arbitrario. Prueba B1 nueva con más de 490 términos.
6. **`expand=source`.** Divide el presupuesto entre todas las filas de evidencia y reutiliza una instancia de `LocalRunStore`/`MainChatTranscriptStore`. B14 verifica dos extractos dentro del presupuesto compartido.
7. **Migración de reglas.** El inicializador reconoce el contenido exacto del V3 anterior y lo migra al V3 actual. Prueba B1 nueva verifica contenido y revisión.
8. **Regla F34.** El prompt instruye plantear en la siguiente respuesta al usuario conflictos entre decisiones o arquitectura relacionados por `supersedes` o `conflicts_with`.

## Compilación y pruebas

- En `C:\Projects\Jarvys-wt\single\app`, con `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`, `ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`, `ANDROID_SDK_ROOT=%ANDROID_HOME%`: `.\gradlew.bat :app:compileFullDebugKotlin :app:compileFullDebugJavaWithJavac :app:compilePlayDebugKotlin :app:compilePlayDebugJavaWithJavac :app:compileFullDebugUnitTestKotlin :app:compileFullDebugUnitTestJavaWithJavac --console=plain` → **BUILD SUCCESSFUL** (65 tareas; 4 ejecutadas, 61 al día).
- `.\gradlew.bat :app:testFullDebugUnitTest --rerun --tests "*SingleSessionMemoryRetentionTest" --tests "*PersonalMemoryFts5Test" --tests "*MessageHistoryFts5Test" --tests "*PersonalAgentBehaviorTest" --tests "*M1bRepositoriesTest" --tests "*JarvysChatDatabaseM0Test" --tests "*StorageWorkspaceComposeTest" -x :app:prepareApkFactoryTemplate --console=plain` → **BUILD SUCCESSFUL** (88 tests, 0 fallos, 0 errores).
- `git diff --check` → sin errores.
- No ejecuté la suite completa. No se generó APK.

## Limitaciones conocidas

- **Hallazgo 9:** una referencia textual del hilo puede conservar `state=retained` después de expulsar el archivo; `read_conversation_artifact` y los metadatos muestran el estado vigente.
- **Hallazgo 7 del juez:** el fallback LIKE no ignora diacríticos cuando no hay FTS5, por la SQLite disponible en minSdk.
- **Hallazgo 8 del juez:** la pantalla de Almacenamiento lee el aviso una vez.

## No verificado

No probé en dispositivo la presión real del sistema ni ENOSPC; Play se compiló pero no ejecuté tests Play; no comprobé visualmente la pantalla de Ajustes en un dispositivo; tampoco ejecuté el resto de la suite ni comparé fallos conocidos contra el export de HEAD. Los tests de esta sesión se limitaron a las clases listadas arriba.
