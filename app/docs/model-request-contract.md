# Contrato de parámetros de las peticiones al modelo

## Esfuerzo seleccionado

`ProviderSettings.getSelectedCodexVariant()` lee `reasoning_variant_openai`; valida el valor contra las variantes del modelo activo y lo normaliza al valor predeterminado del catálogo si deja de ser válido. El selector conserva el orden publicado por `reasoning_options[type=effort]`, y `ModelEffortSlider` usa el último elemento como extremo superior.

Cada petición de texto que pasa por `OpenAICodexResponsesClient.buildRequest()` toma `reasoning.effort`, `reasoning.summary` y `text.verbosity` de `getSelectedCodexVariant()`. El adaptador no tiene un override de variante: las rutas `complete` y `completeConversation` aplican la preferencia seleccionada. Por eso la misma preferencia se aplica a cada iteración ReAct, las peticiones de resumen de compactación, reflexión de memoria, títulos, ejecuciones de crew, subagentes y tareas programadas, siempre que usen el proveedor Codex. Estas rutas comparten `CoreAgentModel` o el mismo adaptador de proveedor.

La variante solo es una preferencia de OpenAI Codex. OpenRouter, OpenAI API por clave y endpoints personalizados usan sus propias configuraciones de proveedor y no consumen `reasoning_variant_openai`. La generación de imágenes usa una petición de imagen separada, no una petición de texto Responses, y no lleva esfuerzo, resumen ni verbosidad.

## Catálogo y valores enviados

Revisé `https://models.dev/api.json` el 2026-10-19. GPT-6 Astra publica `reasoning_options` de tipo `effort` con `low`, `medium`, `high`, `xhigh` y `max`; la UI expone los cinco en ese orden y `max` es el último. El objeto también publica un modo experimental `pro` cuyo cuerpo solicita `reasoning.mode = "pro"`. Ese modo es distinto de un valor `effort`; no se convierte en una opción del slider.

El mismo catálogo contiene un modelo separado `gpt-5.5-pro`, con esfuerzos `medium`, `high` y `xhigh`. La política de catálogo excluye la entrada exacta `gpt-5.5-pro` y cualquier modelo cuyo `options.reasoningMode` sea `pro`. No se habilitó ninguna de esas rutas: el comentario de `CodexModelCatalog` dice que el filtro refleja la selección de modelos de OpenCode, y `git log -S` sitúa ambas reglas en el commit inicial `9d7064f1`; el historial no aporta una justificación más específica. Las pruebas existentes también fijan ambas exclusiones. No hay evidencia demostrable de que esas exclusiones sean un error, así que se conservan mientras se registra aquí la existencia de las opciones separadas.

El catálogo solo publica la lista de esfuerzos para GPT-6 Astra; no publica `reasoning.summary` ni `text.verbosity`. La app deriva el resumen como `auto` para esfuerzos normales y `detailed` para `high`, `xhigh` y `max`, y fija `text.verbosity` en `medium`. El esfuerzo máximo, por tanto, no eleva por sí mismo la verbosidad del texto.

## Diagnóstico local

El adaptador guarda un registro `model_request` en el almacén JSON privado de `LocalDiagnostics` cuando los parámetros efectivos cambian respecto al último registro escrito correctamente por esa instancia durante el proceso. El objeto conserva el modelo, esfuerzo, resumen, verbosidad, cantidad de herramientas efectivamente declaradas y si existe alguna declaración de categoría web. No conserva prompts, nombres de herramienta, argumentos, respuestas ni credenciales. Un turno con parámetros estables produce un solo evento; un cambio de esfuerzo u otro parámetro produce el siguiente. Así se puede verificar la configuración usada sin recorrer las 32 casillas ni sincronizar disco en cada petición, y sin desplazar diagnósticos por cada iteración ReAct. El almacén no tiene una vía asíncrona de escritura: `persist` sigue siendo síncrono, pero solo se invoca al iniciar con una configuración o cuando esta cambia. Un proceso nuevo vuelve a registrar su primera configuración. El almacenamiento acotado y la retención existentes siguen aplicándose a todos los tipos de diagnóstico; no modifica Room ni su esquema. Una repetición de transporte tras un 401 reutiliza la misma petición preparada y no duplica el evento lógico.

Referencias de implementación: `ProviderSettings.java:170-202`; `OpenAICodexResponsesClient.java:66, 114, 181-206`; bucle de peticiones `CoreAgentLoop.java:603, 782`; chat y agentes `CoreAgentRuntime.java:456-460, 1198, 2120`; compactación `CoreAgentModel.java:117-134`; título `MainActivity.kt:1508-1524`; reflexión `MemoryReflectionWorker.java:157`; tarea en segundo plano `AgentForegroundService.java:1523`; operaciones de plan, operador, resumen y percepción `ProviderAgentModel.java:59, 211, 273` y agentes que comparten ese adaptador. El catálogo y su parser están en `CodexModelCatalog.kt:58-59, 232-236, 290-294, 336-352, 434-450`; la selección de extremo de la UI está en `ModelEffortEffects.kt:32-38` y `ModelEffortSlider.kt:75-99`.

## Soul predeterminado

El texto de soul predeterminado no cambia automáticamente en instalaciones existentes; no se migran datos de usuario. El `CORE` del sistema ya impulsa respuestas más descriptivas al pedir explicaciones completas y estructuradas salvo que el usuario prefiera brevedad.
