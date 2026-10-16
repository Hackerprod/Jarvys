# Agente personal

Descripción del flujo implementado de memoria, perfil, bootstrap y mantenimiento. La correspondencia con OpenClaw está en [openclaw-mapping.md](openclaw-mapping.md).

## Memoria y perfil

La memoria personal vive en Room, en la misma base de datos que el historial de chat.

| Datos | Uso |
|---|---|
| `agent_profiles` y `agent_docs` | Identidad y textos de comportamiento versionados. |
| `user_directives` | Datos estables y preferencias activas del usuario, con revisiones. |
| `memory_entries` y `memory_fts` | Notas diarias, memoria curada y búsqueda de texto completo. |
| `memory_revisions` | Historial de cambios de documentos y entradas. |
| `agent_session_state` | Tipo de sesión, bootstrap y ciclos de flush. |
| `memory_jobs` | Cola con lease para mantenimiento y snapshot. |

Los alcances son `user`, `agent` y `conversation`. Las sesiones privadas no leen ni escriben memoria global. El perfil se proyecta al prompt personal al iniciar una sesión; la memoria curada relevante se inyecta en el chat principal y se puede consultar con búsqueda cuando falte contexto.

## Herramientas y evidencia

El modelo usa herramientas genéricas declaradas por la sesión: `user_profile_set`, `memory_save`, `memory_search`, `memory_get`, `memory_forget` y `memory_correct`. Para cambiar un dato, lee primero la entrada existente. Cada escritura devuelve `id`, `status`, `revision` y `scope`; ese resultado es la evidencia disponible para describir lo que quedó persistido.

El control de memoria está en las reglas breves del prompt y en el resultado de la herramienta. La regla enseña al modelo a escribir cuando el usuario pide que recuerde algo, leer antes de cambiarlo, saltarse secretos salvo petición explícita y decir solo lo que confirme el resultado.

## Prompt y continuidad

`CoreAgentRuntime` reconstruye la identidad, el `soul`, las reglas, el perfil, la memoria relevante y el texto de bootstrap al preparar una sesión. Los contextos de tareas y proactividad reciben el perfil permitido; los subagentes no reciben memoria personal. El prompt distingue datos de perfil de instrucciones del sistema.

Bootstrap prioriza el pedido actual y, cuando encaja, pregunta cómo dirigirse al usuario y qué tono prefiere. La respuesta se refleja en Room y en la UI de perfil y memoria.

## Mantenimiento y compactación

- Antes de resumir una conversación, `PersonalMemoryFlush` envía al modelo una vista privada de los mensajes reales del usuario y las herramientas de memoria disponibles. Un guardado se apoya en el resultado de la herramienta; si no hay nada durable, el modelo puede devolver `NO_REPLY`.
- La compactación continúa después del intento de flush y conserva el contexto reciente. Al volver a preparar la sesión, el perfil se inyecta de nuevo.
- El mantenimiento offline puede extraer notas de conversaciones inactivas y crear un snapshot al archivar. La consolidación programada agrupa notas diarias ya persistidas sin borrar sus fuentes.
- La limpieza de secretos en la vista de mantenimiento y las reglas de alcance privado se aplican en sus respectivas capas.

La pantalla de Memoria consulta y modifica los mismos documentos y entradas de Room que usan las herramientas. La memoria antigua basada en `/memory/` no se importa en este flujo.

## Escenarios de comportamiento

1. Una petición de guardar invoca una herramienta de escritura y la nota queda disponible con evidencia de `id` y revisión.
2. Sin una herramienta de escritura de memoria en la sesión, la base de datos no cambia.
3. El perfil aparece en una sesión posterior y sigue presente después del flush y la compactación.
4. Las sesiones privadas no exponen el perfil global ni las herramientas de memoria.
5. La UI muestra datos guardados en Room.
