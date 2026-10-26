# S6 — próximo bloque nativo WorkspaceTools / router

2026-10-26. **Propuesta, sin implementación ni PASS.** Complementa `S6-tool-inventory.md` y `S6-admission-contract.md`. El siguiente bloque depende del cierre de revisión de Google/gates. No autoriza SDK, build, desktop, VPS ni APK. Se inspeccionó el árbol concurrente; releer interfaces antes de editar.

## 1. Objetivo y límite del bloque

Instrumentar `WorkspaceTools.WriteFile`, `EditFile`, `DeleteMemoryFile` y el transporte de `CodingWorkspaceRouter`, con B3 sobre `WorkspaceStore` real. No ampliar clasificación general, Linux, Crew ni APK Factory. No elevar `CoreToolResult.success` a prueba genérica de efecto.

El bloque mínimo coherente incluye el cableado de `persistenceEffect(arguments)` descrito en §4. Si solo se autorizan los dos archivos de herramientas/router, la exclusión MEMORY mixta queda abierta: solo pueden cerrarse DeleteMemoryFile y vistas memoryOnly. No ocultar esa limitación marcando mutaciones de memoria READ ni toda la familia MEMORY.

`EffectInvocation` liga receipts a una identidad privada; receipt de otro contexto se resuelve UNCERTAIN. El propietario exterior es el único que resuelve/finaliza/sella. En la implementación inspeccionada `CoreTool.execute4` admite nativo pero no convierte legacy success en OK; conservar esa prudencia.

## 2. Productores write/edit/delete y frontera de éxito

Añadir execute4 en WriteFile/EditFile, con helper interno compartido con execute legacy (contexto nulo). Elegir primero ruta cursor/Coding/workspace/memory, validar argumentos requeridos y comprobar cancelación. Para escritura externa nativa, admitir antes de entrar en cualquier operación que pueda mutar; no admitir en el wrapper y después con otro snapshot en el productor Coding.

Conservar `MemoryToolActions.run` y su autoridad existente. Ejecutar `workspace.writeWithRevision` o `workspace.edit`; mantener actualización del índice y presentación. Solo al retornar normalmente la operación real y finalizar normalmente el método se adjunta `invocation.receipt(OK)`. La prueba es el retorno del productor local, no success/texto/revisión serializada:

- `WorkspaceStore.writeWithRevision` usa projectLock; retorna después de write/flush/close, `writeSkillFile` o revisión MemoryStore. Revisión null es normal fuera de memoria.
- `WorkspaceStore.edit` usa projectLock; valida coincidencia única, llama write y retorna después de esa escritura.
- `WorkspaceStore.deleteMemoryFile` solo admite archivo bajo la zona explícita memory y retorna después de `MemoryStore.delete` con revisión. DeleteMemoryFile debe declarar MEMORY y no emitir un episodio OK: su recibo propio de memoria es otra cosa.
- Fallo/cancelación después de admisión conserva UNCERTAIN si no existe resultado explícito suficiente. Un fallo del índice posterior a escribir no prueba ausencia de efecto. No introducir rollback ni lectura posterior fuera del lock para certificar contenido: podría observar una escritura concurrente.

**Precisión pendiente de frontera:** las validaciones profundas están dentro de WorkspaceStore. Admitir antes de su método puede dejar started ante una validación interna que rechaza sin mutar: evidencia conservadora, no prueba de efecto. Si se exige NOT_EXECUTED exacto para ese caso, se necesita callback/overload del store, fuera de los dos archivos iniciales. Debe preceder también creación de directorios y temporales, no solo output.write, y preservar locks/STOP/autoridad. No anunciar cobertura más fuerte que la implementada.

## 3. Namespace MEMORY y recuperación de cursor

Usar semántica de `WorkspaceStore.isMemoryPath(path)`, no prefijos caseros. Mapa inspeccionado:

- `isMemoryPath` llama `memoryRelativePath(normalizePath(path))`.
- `normalizePath` verifica raíz delegada mediante `ensureDelegatedRoot` (comprobación, no creación), recorta espacios y reconoce `/memory`, `/memory/`, `/memory/<suffix>`; normaliza suffix con `normalizeRelativePath`.
- `memoryRelativePath` devuelve null si no hay memoryStore; devuelve raíz vacía para `/memory/`; rechaza `skills`/`skills/...` dentro de memory sin distinguir mayúsculas. No convertir esa zona prohibida en permiso.
- `memoryStoreForNativeAction` vuelve a comprobar ruta, memoryAccessAllowed y store habilitado. `normalizedNativeMemoryPath` fija la ruta para la revalidación de `MemoryToolActions`/`MemoryNativeActions`. Clasificación no sustituye estas comprobaciones.
- `memoryOnly` impide rutas fuera de memory; `codingProjectScope` rechaza memoryOnly, zonas privadas delegadas y falta de conversationId. `/project` se detecta mediante `isCodingProjectPath`, no la normalización de memoria.

La clasificación debe ser total para argumentos inválidos: sin String path o normalización rechazada, retornar clasificación conservadora NONE (salvo una herramienta completamente MEMORY), dejando al execute rechazar sin efectos. Hoy registry calcula metadatos antes de su try; no dejar que el clasificador lance fuera del manejo normal. No guardar path/contenido en hechos de admisión.

WriteFile/EditFile deben declarar READ para recuperación con `result_cursor` String, y no emitir receipt de efecto anterior en esa nueva llamada. El productor Coding interpreta primero result_cursor, incluso si se incluyen campos de mutación.

**Corrección tras inspección exacta:** `CodingProjectTools.optionalString` usa `containsKey ? string(...) : default`; cursor null o de tipo incorrecto lanza, no continúa mutando. Aun así, preferir `instanceof String` para clasificar la rama de lectura real, no `containsKey` a ciegas. Cubrir null/tipo erróneo explícitamente en tests, sin asumir semántica permisiva.

## 4. API path-sensitive y consumidores exactos

Añadir en `CoreTool` conservando compatibilidad:

```java
default PersistenceEffect persistenceEffect(Map<String, Object> arguments) {
    return persistenceEffect();
}
```

WriteFile/EditFile: MEMORY para vista memoryOnly o ruta memory válida según store; NONE para mutación externa. El overload no-arg puede devolver MEMORY para vista enteramente memoryOnly. DeleteMemoryFile: no-arg MEMORY. Conservar `effectClass` WRITE para mutaciones de memoria; exclusión por persistence, no por falsa lectura.

| Archivo / método | Cambio coherente requerido |
|---|---|
| `CoreTool.execute(arguments, token, progress, invocation)` | Consultar persistenceEffect(arguments) antes de admisión nativa. |
| `CoreToolRegistry.invoke` | recordsEffect usa persistenceEffect(safeArguments). |
| `ScheduledTaskProcessor.TaskTrackedTool.recordsEpisode(arguments)` | Exclusión usa argumentos exactos. |
| `CoreAgentRuntime.ScheduledTaskBoundTool` | Delegar overload no-arg y nuevo overload con argumentos. |
| `ScheduledTaskProcessor.TaskTrackedTool` | Delegar ambos overloads. |
| Wrapper de `ProactiveAgentProcessor` | Delegar ambos overloads. |
| Wrapper anónimo de `MemoryReflectionWorker` | Delegar ambos overloads. |
| `PersonalMemoryTools.MemoryTool` | Su override no-arg sigue suficiente para familia homogénea; nuevo default delega en él. |
| `PersonalMemoryFlush` selección de herramientas | No tiene argumentos: conservar selección no-arg para herramientas enteramente MEMORY; no cambiarla a una llamada con mapa vacío que simule una invocación. |

Repetir grep de consumidores antes de implementar: el árbol sigue cambiando. El recorder depende de la decisión del registry; no añadir interpretación de rutas en EpisodeRecorder.

## 5. CodingWorkspaceRouter: transporte sin cierre anidado

Añadir overload invoke que reciba progress y el mismo EffectInvocation. Su registry privado se usa para obtener el handler. Para mutación seleccionada, llamar directamente `handler.execute(scopedArguments, token, progress, invocation)` y devolver el mismo resultado, conservando metadatos. No crear contexto nuevo, reatar receipt, resolver ni sellar.

**No usar `registry.invoke(... suppliedInvocation)` anidado:** actualmente siempre llama resolve/seal incluso para suppliedInvocation; cerraría el contexto antes del propietario exterior. El registry privado del router no tiene guard ni observer; comprobar esto de nuevo al implementar. Los controles del handler y del scope permanecen. Manejar ausencia de handler como fallo, sin fallback a otra herramienta.

Para cursor, usar la ejecución legacy de lectura del handler (o API explícita de lectura equivalente) sin admisión. El default execute4 de CodingProjectTools todavía ve WRITE para todas sus operaciones; usarlo sin discriminación produciría falso started. No hace falta cambiar clasificación global Coding en este bloque.

**No falseCodingOK:** CodingProjectTools todavía devuelve success legacy. Router no puede promover ese resultado a OK por flag, texto, hash o número de operaciones. Conservar receipt auténtico futuro si ya pertenece al mismo contexto; sin él, /project/ mutation sigue UNCERTAIN. Certificar APPLIED/PARTIAL/FAILED/CONFLICT/CANCELLED exige parche posterior en productor Coding sobre `ProjectMutationService.Result`, no heurística en router. Lista applied vacía tampoco es prueba general de no-dispatch.

## 6. Pruebas focales y condiciones de aceptación

1. **B3 nativo real:** `WorkspaceTools` sobre `WorkspaceStore` temporal real, registrado con EpisodeRecorder/Room y binding de llamada comprometido. Write y edit verifican contenido real y receipt OK; started durable debe existir antes de la operación. Si hace falta punto de observación, introducir seam estrecho que no sustituya el store real por fake CoreTool. No llamar PASS B3 a un test del receipt aislado.
2. AdmissionWriter que falla: cero cambios de archivo. Cancelación antes de admisión: cero efecto. Fallo/cancelación posterior: UNCERTAIN y no replay.
3. MemoryStore real o fixture existente: write/edit `/memory/` y delete no crean episodio; revisiones/authority existentes siguen funcionando. Añadir disabled, raíz memory, skills bajo memory, alias/espacios/path inválido y vista memoryOnly. No ampliar grants para pasar tests.
4. Cursor válido/inválido/null/tipo erróneo y combinado con path/content: ninguna invocación clasificada READ puede mutar. Recuperación no emite episodio ni reutiliza receipt de otra llamada.
5. Router conserva identidad de receipt y flags; no sella antes de que termine el owner externo ni añade observer duplicado. Un resultado legacy Coding success continúa UNCERTAIN.
6. Wrappers de tareas/proactivo/reflexión conservan overload con argumentos y exclusión; snapshot de metadatos no altera política.
7. Tests puros de clasificación/receipt pueden ejecutarse sin SDK cuando proceda, pero no sustituyen Room/Robolectric/compilación Full/Play. SDK/licencias pendientes: Android y B3 integrado permanecen **no ejecutados**, nunca PASS implícito.

Sin implementación en este documento. Confirmar ownership del cableado compartido antes del siguiente bloque.
