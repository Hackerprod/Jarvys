# Long chat performance (idle)

## Síntoma y alcance

El problema reportado es que un chat largo puede quedarse bloqueado al abrirlo, desplazarse por él o dejarlo abierto en reposo. Sucede sin streaming ni ejecución activa del agente. No había ANR en el historial de salidas. Esta ronda conserva el APK debug y no cambia la interfaz ni el esquema Room v1.

Datos comunicados desde la base del móvil:

- El chat más problemático tiene 985 `conversation_items` y 86 `tool_results`; cada resultado mide como máximo unos 8 KB.
- Hay mensajes de asistente de unos 21 KB (incluye bloque SVG y tabla Markdown) y 19 KB (incluye tabla).
- Dos chats de workspace tienen 724 y 494 `conversation_items`.

Estos son datos aportados por el dueño del móvil; no se volvió a leer su base durante esta tarea.

## Causas verificadas y cambios

1. **Medición de tablas Markdown.** `AssistantMarkdownTable` creaba opciones de anotación nuevas al componer. En la versión 0.37.0, `annotatorSettings()` construye un `DefaultAnnotatorSettings` nuevo, cuya igualdad por identidad invalida la medición recordada. Cada columna volvía a filtrar las celdas de las filas y a medir texto y segmentos. Ahora las entradas reales del anotador se recuerdan, las celdas se agrupan por fila una sola vez y la medición persistente se identifica por texto de tabla, ancho disponible, estilos, densidad, escala de fuente y dirección. `AssistantMarkdownSuccessCache`, ya asociado a `remember(conversationKey)` en `ChatMessageList`, conserva las medidas mientras esa conversación está en memoria y se libera con su composición. No se introduce un límite artificial.

2. **SVG y LaTeX.** El render SVG con AndroidSVG y la validación con `LatexParser` ocurrían durante composición. SVG y la validación de LaTeX ahora se preparan en `Dispatchers.Default` y se cachean por fuente (SVG también por color de tema). En LaTeX, `LatexAutoWrap` sigue parseando y renderizando durante composición; solo la validación previa sale del hilo principal. Mientras se prepara se muestra un marcador de altura mínima estable; los resultados inválidos siguen mostrando `AssistantCodeFallback`.

3. **Proyección de eventos.** La visibilidad analizaba el JSON de los resultados de herramienta en cada pasada. Cada evento inmutable ahora conserva su análisis de detalle una sola vez; un `copy` crea un análisis nuevo. La ruta de chat consume snapshots inmutables de timeline publicados por sesión y recuerda la selección y el filtrado de eventos, sin `eventsForSession()` ni su `synchronized(lock)` dentro de composición. La raíz recoge una proyección compacta: resúmenes para adjuntos, mensajes, misiones de crew y el estado mínimo que necesita el indicador de presencia. El árbol raíz ya no observa la lista completa de eventos. La creación de esa proyección recorre los eventos cuando cambia la identidad de la lista; cada snapshot de streaming crea una lista nueva, por lo que esa publicación todavía puede costar O(n) por frame. No se optimizó esa pasada en esta ronda. `ConversationTimeline` ya recordaba sus proyecciones de visibilidad, agrupación e índices.

4. **Lectura de páginas de historial.** La ruta de timeline ya no carga el snapshot completo del transcript del modelo. Para la página solicitada selecciona referencias por `seq`, recupera cabeceras y llamadas por `batch_id`, y lee `tool_results.output` solo para resultados de la página y referencias de actividad que necesitan el texto. Las supresiones y emparejamientos que cruzan ventanas consultan por conversación la existencia y los metadatos mínimos de actividad tipada, presentación y marcador de reflexión para las claves mencionadas en la página; las llamadas pendientes también consultan la existencia y el ciclo de vida del resultado. Así se conserva la proyección completa sin cargar esos payloads para filas fuera de página. No lee `tool_calls.arguments_json`, `model_provider_outputs.items_json`, `tool_batches.provider_output_items_json` ni `model_artifacts` al pintar una página. Decisiones, interrupciones, artefactos y filas auxiliares de timeline se consultan por secuencia; reacciones por identidad estable y snapshots de crew por las misiones mencionadas en la ventana. Se preservan invalidaciones de conversación completa porque una invalidación posterior debe ocultar elementos de páginas anteriores.

No se cambió el esquema Room v1 ni se añadieron índices o migraciones.

## Cobertura añadida

- Compose: una tabla de 40×6 se mide una vez tras recomposiciones forzadas y reentrada, y vuelve a medirse al cambiar el texto.
- SVG/LaTeX: el test registra el hilo de preparación (incluida la validación de LaTeX) y comprueba que reentrar con la misma fuente reutiliza el resultado; `LatexAutoWrap` continúa ejecutándose durante composición.
- Visibilidad: 120 resultados se analizan una vez por evento en dos proyecciones; cambiar un evento añade un único análisis.
- Estado/rutas: pruebas de publicación cubren el snapshot de timeline, la primera composición de Chat para su sesión mientras Workspace sigue visible, la prioridad de los eventos vivos en Workspace y que el estado compacto preserve el indicador de presencia.
- Base de datos: se comparan páginas hacia arriba con la proyección completa para varios turnos y un turno de más de 500 elementos, con actividad tipada, presentación, reflexión, resultado e invalidaciones cruzando la frontera; también se mide el número de filas/bytes de payload leídos frente a 350 batches históricos. Los tests de BD usan su propio executor, porque la guarda de hilo principal está desactivada en unit tests.

## Pendiente y límites de la medición

- No se midieron tiempos reales de apertura, scroll, recomposición, frames ni memoria en el móvil. Los contadores y el test de BD verifican el trabajo realizado por las rutas; no predicen por sí solos los milisegundos del dispositivo.
- La memoria acumulada de `sessionSnapshots` y de los flujos de timeline publicados por sesión sigue pendiente. La caché Markdown/SVG/LaTeX vive con la composición de la conversación; no se añadieron límites numéricos ni un manejador `onTrimMemory`.
- El executor `chatdb-java-bridge` sigue serializado. No se modificó en esta ronda; la contención de su cola queda pendiente de medición.
- No se cambió el streaming por frame ni la representación de bloques Markdown, los locks de admisión, el perfil baseline, ni la variante debug entregada.
- La lectura de invalidaciones conserva una agregación por conversación para mantener la semántica entre ventanas. Si el log del dispositivo muestra coste significativo en esa consulta, medirla por separado antes de reducir su alcance.

## Cómo medirlo en el móvil

Pedir al dueño que repita siempre el mismo recorrido: abrir el chat de 985 items, dejarlo quieto unos segundos, desplazarse lentamente hasta la mitad y volver arriba; repetirlo con los chats workspace de 724 y 494 items. Usar el mismo APK debug y anotar cold/warm open por separado. Antes de reproducir, limpiar y luego guardar el log del proceso y los framestats:

```sh
adb shell dumpsys gfxinfo <package> reset
adb logcat -c
# Reproducir el recorrido y dejar el chat abierto en reposo.
adb shell dumpsys gfxinfo <package> framestats > long-chat-gfxinfo.txt
adb logcat -d -v threadtime > long-chat-logcat.txt
```

Compartir los archivos completos y señalar la hora aproximada de cada apertura/scroll. Si existe una traza de perfil de Android Studio, incluirla; no hace falta activar streaming ni iniciar una ejecución del agente.

Si se puede exportar una copia de la base, sustituir `:conversation_id` por cada ID real y compartir los resultados de estas consultas (solo agregados y tamaños, no el texto de salida):

```sql
SELECT conversation_id, COUNT(*) AS item_count
FROM conversation_items
WHERE conversation_id = :conversation_id
GROUP BY conversation_id;

SELECT COUNT(*) AS result_count,
       MAX(length(CAST(output AS BLOB))) AS largest_output_bytes,
       SUM(length(CAST(output AS BLOB))) AS total_output_bytes
FROM tool_results
WHERE conversation_id = :conversation_id;

SELECT m.seq, m.role, m.message_id,
       SUM(length(CAST(p.payload AS BLOB))) AS text_bytes
FROM messages AS m
JOIN message_parts AS p
  ON p.conversation_id = m.conversation_id AND p.message_id = m.message_id
WHERE m.conversation_id = :conversation_id AND m.role = 'assistant' AND p.kind = 'text'
GROUP BY m.conversation_id, m.seq, m.role, m.message_id
ORDER BY text_bytes DESC
LIMIT 20;

SELECT item_kind, COUNT(*) AS rows_per_kind
FROM conversation_items
WHERE conversation_id = :conversation_id
GROUP BY item_kind
ORDER BY rows_per_kind DESC;

EXPLAIN QUERY PLAN
SELECT batch_id, call_id, seq, tool_name, lifecycle
FROM tool_results
WHERE conversation_id = :conversation_id AND batch_id IN ('<batch-id-from-page>');
```

La última consulta ayuda a confirmar que SQLite usa el índice disponible por conversación/batch; no copiar `arguments_json`, `items_json` ni `output` al informe. Comparar los bytes/filas de página con los totales y conservar el `EXPLAIN QUERY PLAN`.
