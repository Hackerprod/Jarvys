# Workspace como chat

## Objetivo y límites

La lista de workspaces debe compartir la fila, jerarquía y menú compacto de la lista de chats. Al abrir uno, su contenido debe parecer y comportarse como una conversación viva: mensajes, actividad de herramientas plegable, artefactos y compositor. El tablero y la información de bots quedan como destinos secundarios.

No se cambia el esquema Room, el modelo de Crew, las rutas públicas, los prompts ni la autoridad de los controles. Las entradas de la timeline principal y su comportamiento siguen bajo `ConversationReader`, `ConversationVisibility` y `ConversationTimeline`.

## Fuentes y flujo existente

- `WorkspaceIdentity.executionSessionId(workspaceId)` fija la sesión propietaria de la conversación. `WorkspaceProjection.chat` lee su página reciente con `LocalRunStore.readConversationTimelinePage`; la paginación anterior conserva el cursor del ledger.
- `ConversationReader` proyecta las tablas existentes `crew_missions`, `crew_messages` y `crew_message_activities` como un evento durable `crew_mission` que contiene el último `CrewMissionSnapshot` de cada misión. El snapshot conserva IDs y timestamps de mensajes, síntesis, bots y `ToolActivity`.
- En vivo, `AgentRunUiState` recibe el snapshot actualizado por sesión; `WorkspaceChanges` también invalida las proyecciones de la ruta. La pantalla puede volver a proyectar el evento de misión con su snapshot más reciente. Al reabrir, se reconstruye desde la misma página persistida.
- `CrewActivityTimeline.project(messages, bots)` agrupa las etapas de cada ejecución de herramienta sin modificar el registro canónico. `ConversationTimeline` aplica `ConversationVisibility`, agrupa actividad con `ToolGrouping` y usa los renderers comunes: mensajes, `ConnectorActivityLine`, Markdown e `PresentationArtifactCard`.
- La ruta ya usa `executionSessionId` como clave de conversación, lo que mantiene la cache Markdown aislada por workspace y conserva el pegado al final de `ChatMessageList`.

## Proyección Crew → timeline

La capa de workspace expande los eventos `crew_mission` a eventos estándar de `AgentRunUiEvent` antes de llamar a `ConversationTimeline`:

1. Proyectar mensajes por timestamp original y conservar su índice de origen como desempate estable, sin ordenar por ID. Mantener `CrewMessage.id` como `messageId`; derivar `id` y `timelineKey` determinísticamente de la identidad de workspace, misión y mensaje para que los refrescos vivos y la reapertura conserven la fila.
2. Un mensaje del usuario se convierte en `user`. El contenido de bots dirigido al usuario se convierte en `assistant`; también se muestran los `FINDING`, `CRITIQUE`, `QUESTION`, `BLOCKER`, `ANSWER` y `RESULT` de bots dirigidos al chief, y las respuestas del chief al usuario. Las instrucciones de despacho `TASK`, los estados `STATUS`, la coordinación con referencia causal tipada y el tráfico privado entre bots se excluyen.
3. Una fila con `ToolActivity` se convierte con `AgentRunUiEvent.activityEvent`, tras agrupar las etapas mediante `CrewActivityTimeline`. El renderer genérico conserva estado, detalle plegado y preview según la política del chat; no se interpretan JSON ni nombres concretos de herramientas en el adaptador.
4. La síntesis persistida de misión se muestra como respuesta de asistente si existe como contenido final y no coincide exactamente con otra respuesta ya proyectada. Las filas de artefactos ya registradas permanecen como eventos `artifact`, para que el chat muestre sus tarjetas descargables. No se fabrican mensajes de estado o recibos visuales.
5. No convertir `crew_mission` ni `workspace_result` en tarjetas de evidencia interna. `ConversationVisibility` ya oculta `workspace_result`; el adaptador no añade una representación visible equivalente.

Los eventos ordinarios de la sesión pasan por la regla común de `ConversationVisibility` sin habilitar preámbulos persistidos de modelo (`showAssistantProgress = false`). Así se mantienen visibles las respuestas, las herramientas y los estados de actividad que el chat considera conversacionales, mientras el razonamiento y los estados internos siguen ocultos.

La proyección mantiene el orden causal de mensajes dentro de cada snapshot. Al mezclarla con los eventos ordinarios de la sesión, usa el timestamp de origen y una clave estable como desempate. Una nueva copia del snapshot reemplaza filas con los mismos IDs, no añade duplicados.

## Entrada y controles

El destinatario del compositor es una asignación visible del workspace. Enviar texto invoca el control nativo `CORRECT` con workspace, asignación y revisión exactos; detener invoca `PAUSE` para la asignación seleccionada. Ambos usan `WorkspaceCoordinator.enqueueNativeControl`, que registra y aplica la autoridad mediante `WorkspaceControlOwners`, vuelve a validar la asignación y conserva los controles de seguridad existentes. El compositor no envía al runtime de chat ordinario ni obtiene permisos de Crew.

Las acciones de mensajes que no aplican a un workspace (traducir, voz, borrar, regenerar y respuestas proactivas) se excluyen mediante capacidades genéricas de timeline con defaults equivalentes al chat. No se infiere el comportamiento a partir del ID del workspace.

## Pantallas

- La lista usa una fila compartida con título, estado localizado y discreto, indicador de selección/actividad y overflow. El menú ofrece abrir; el registro actual no expone renombrar ni eliminar y no se ofrecen archivar o fijar.
- El detalle usa solo la timeline y el compositor. Archivos del tablero y bots se alcanzan desde el menú de acciones de la cabecera; las pestañas Debate/Board/Bots no forman parte de la vista principal.
- Los estados visibles y las acciones tienen recursos en inglés y español y usan colores/semántica del tema actual, con controles etiquetados para TalkBack.

## Comprobación

Los tests específicos cubren: orden y claves de la proyección; agrupación de herramientas; respuestas y artefactos persistidos; exclusión de tráfico interno; y actualización del snapshot vivo. Compose comprueba la fila y su menú, además de timeline, actividad plegada, compositor, parada y acceso secundario a archivos/bots. Se mantienen los tests de `ConversationReader`, `ConversationVisibility`, `ChatMessageList`, actividad de herramientas y bloques inline Markdown. Si un test existente dependía de pestañas en la pantalla principal de Workspace, se actualiza para verificar el destino secundario y se registra ese cambio aquí.
