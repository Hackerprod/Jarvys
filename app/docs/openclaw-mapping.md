# Correspondencia con OpenClaw

Auditoría del texto y el flujo de memoria del agente personal frente al checkout local `C:\Users\danil\AppData\Local\Temp\openclaw-ref`.

## Referencias de OpenClaw

- `docs/reference/templates/AGENTS.md`: arranque de sesión, memoria, “Write It Down” y mantenimiento de memoria.
- `docs/reference/templates/USER.md`: directivas duraderas del usuario.
- `docs/reference/templates/SOUL.md`: identidad y continuidad.
- `docs/reference/templates/BOOTSTRAP.md`: ritual de primer arranque.
- `docs/reference/templates/TOOLS.md`: indica que las notas de herramientas viven en `AGENTS.md`.
- `extensions/memory-core/src/flush-plan.ts` y `src/auto-reply/reply/memory-flush-session.ts`: plan y ejecución del flush antes de compactar.
- `src/agents/bootstrap-cache.ts` y `src/agents/system-prompt.ts`: carga de archivos de inicio y ensamblado del prompt.
- `docs/reference/session-management-compaction/compaction.md`: resumen y retención de contexto al compactar.

## Correspondencia

| OpenClaw | Agente personal | Adaptación |
|---|---|---|
| `AGENTS.md` enseña dónde guardar notas y pide leer antes de escribir. “Write It Down” convierte una petición de recuerdo en una acción de escritura. | `agent_docs.core_rules` dirige al modelo a usar una herramienta de memoria disponible y leer el dato antes de cambiarlo. | Se conserva una regla breve de comportamiento; no hay análisis de frases de la respuesta.
| `USER.md` contiene preferencias y datos estables; `MEMORY.md`, decisiones y hechos duraderos. | `user_directives` y `memory_entries` guardan esos dos tipos de información en Room. El perfil y la memoria relevante se inyectan al iniciar cada sesión personal. | Se mantiene el perfil reinyectado y el recall con búsqueda cuando la respuesta depende de contexto previo.
| La herramienta genérica `write` hace persistentes las notas; Memory Core ofrece búsqueda y lectura. | `user_profile_set`, `memory_save`, `memory_search`, `memory_get`, `memory_forget` y `memory_correct` son herramientas del agente. | Las escrituras devuelven `id`, `status`, `revision` y `scope`; el texto guía al agente a informar solo lo que respalda el resultado.
| El mantenimiento de `AGENTS.md` revisa notas recientes y conserva directivas/factos duraderos en sus archivos. | El mantenimiento de Jarvys procesa mensajes de usuario y consolida notas ya persistidas; los prompts de extracción, snapshot y consolidación describen la tarea concreta. | Se redujeron las instrucciones repetidas y los recordatorios defensivos; se conserva el mantenimiento que usa las herramientas de memoria.
| OpenClaw ejecuta el memory flush antes de resumir una sesión próxima a compactarse. | `PersonalMemoryFlush` corre antes de la compactación, usa una vista privada de mensajes del usuario y permite un no-op cuando no hay nada durable. | Se conserva el orden flush → resumen y el recibo de escritura como evidencia de la herramienta.
| Los archivos de inicio se cargan al abrir sesión; `BOOTSTRAP.md` guía una conversación breve en un workspace nuevo. | `CoreAgentRuntime` reconstruye identidad, reglas, perfil y memoria al abrir sesión; bootstrap guía el primer contacto y la UI refleja los datos de Room. | Se conserva la reinyección y se elimina el recordatorio duplicado de bootstrap que volvía a exigir un recibo.
| `TOOLS.md` está retirado; las notas locales de herramientas se integran en `AGENTS.md`. | Las descripciones de herramientas se entregan junto a las declaraciones disponibles. | Las descripciones explican el uso y el resultado verificable sin añadir un segundo protocolo de memoria.

## Cambios de la auditoría

Se retiraron el detector de afirmaciones basado en vocabulario inglés/español, la llamada adicional al modelo con un borrador marcado como rechazado, y el fallback que reescribía la respuesta final. OpenClaw guía la conducta con sus archivos de prompt y confía en el resultado de la herramienta; no inspecciona el texto final con listas de expresiones ni lo reemplaza.

También se quitaron las instrucciones repetidas sobre recibos en bootstrap y sesiones de fondo. La regla corta vive en el prompt de comportamiento; la herramienta sigue devolviendo evidencia estructurada. Los mensajes necesarios para delimitar el acceso de sesiones privadas, tareas y subagentes se mantienen porque expresan alcance, no validan afirmaciones.

Se conservaron las herramientas genéricas con evidencia, el perfil reinyectado, bootstrap, el flush previo a compactar, el mantenimiento de memoria y la UI. No cambia el esquema Room: los tipos retirados eran documentos versionados, no tablas.
