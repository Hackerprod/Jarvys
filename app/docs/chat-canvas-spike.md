# Spike del lienzo de chat (fase 0)

## Estado y decisión

El lienzo funcional está detrás del ajuste global de desarrollador **Lienzo de chat (experimental)**, apagado por defecto. Compose conserva el compositor, el teclado y el resto de la pantalla; al activar el ajuste, solo la lista de mensajes pasa a un WebView local. La lista Compose sigue disponible al apagarlo. Ambas vistas reciben el mismo timeline ya cargado por la aplicación.

El spike compila y tiene pruebas JVM para el contrato del puente, la serialización, la política CSP y el modelo de parches en JavaScript. No hay dispositivo Android conectado en esta sesión: no se midieron el TCL ni el S23 y no se capturaron pantallas. Por tanto, la puerta de rendimiento y accesibilidad sigue **pendiente de medición en ambos dispositivos**; no se recomienda decidir una migración por estos resultados de escritorio.

## Arquitectura

- `ConversationTimeline` lee la preferencia y elige entre la vista existente `ChatMessageList` y `ChatCanvasTimeline`. No se cambia el esquema ni lo que se persiste.
- Kotlin serializa los `AgentRunUiEvent` actuales. `id` se conserva como `seq`; se conserva también el orden entregado por el lector, sin volver a ordenar por un campo aproximado. La serialización y el diff de eventos se preparan fuera del hilo principal.
- El JavaScript conserva un modelo por `seq`, aplica parches a los eventos modificados y virtualiza los nodos visibles con espaciadores medidos. El lienzo mantiene su ancla al cambiar alturas o anteponer una página y muestra **Saltar al final** al alejarse del final.
- El borde superior solicita una página antigua a Kotlin. La lectura sigue usando la paginación keyset existente; el lienzo no accede a Room ni administra un segundo historial.
- Markdown se construye escapando primero el texto; los bloques de código también se escapan. No se inserta HTML del modelo ni del módulo. Las imágenes de adjuntos, imágenes generadas y miniaturas de artefactos se sirven por rutas locales con propiedad validada para la conversación activa. Los visores completos continúan abriéndose en el anfitrión.
- El WebView carga solo los tres assets locales del lienzo y recursos autorizados de esa conversación. Desactiva acceso a archivos y contenido, bloquea cargas de red y reutiliza `PreviewResponsePolicy` (incluido `connect-src 'none'`). El puente ofrece únicamente copiar, reintentar, abrir visor, descargar, compartir, resolver decisión, responder y solicitar página anterior; Kotlin vuelve a validar cada acción contra el evento vigente y las capacidades de esa conversación.

## Cómo ejecutar el benchmark

1. Abre **Ajustes → Desarrollador**.
2. Pulsa **Ejecutar benchmark**. El test crea 5 000 eventos variados en memoria (Markdown, código, imagen sintética, tarjeta de herramienta y artefacto con miniatura local); no inserta mensajes en Room ni altera una conversación real.
3. La misma carga se muestra primero en la lista Compose actual y luego en el lienzo. El benchmark hace un desplazamiento programático largo, modifica un mensaje como parche de streaming y antepone una página sintética, midiendo si se conserva el ancla.
4. Pulsa **Copiar informe** y pega el texto para conservar las cifras del dispositivo.

El informe incluye tiempo hasta el primer pintado de cada vista, media y p95 de intervalos entre frames, tiempo de pintado del parche, tiempo de pintado de la página, estado del ancla, eventos cargados/nodos DOM, PSS del proceso y PSS de procesos renderer cuando Android los expone. Compose usa `Choreographer`; el lienzo usa `requestAnimationFrame`. Las comparaciones de tiempo usan el valor observado de Compose en esa misma ejecución como umbral. La virtualización pasa si se cargan los 5 000 eventos y el DOM contiene menos nodos de mensaje que el total cargado. La comparación de memoria total queda **NO MEDIDO** si Android no expone PSS del renderer en alguno de los dos pasos; no se sustituye por una estimación.

El benchmark de parches usa una mutación sintética para poder medir el render incremental sin hacer una llamada al proveedor. El flujo actual de proveedores acumula la respuesta antes de publicarla como mensaje final; por eso el informe del spike aún no demuestra deltas de tokens del proveedor en tiempo real. El modelo JS y el camino de parche están cubiertos por la prueba JVM; falta conectar una fuente de deltas de texto reales antes de dar por cumplido ese criterio de la puerta.

## Pruebas manuales necesarias en TCL y S23

- Activar el lienzo y abrir un chat real largo; comprobar que mensajes, tarjetas de herramientas, miniaturas de módulos, imágenes y visor coinciden con Compose.
- Desplazarse hacia arriba hasta cargar varias páginas. Verificar que el mismo mensaje permanece anclado y que **Saltar al final** vuelve al último mensaje.
- Mantener pulsado texto de usuario y de asistente, seleccionar un fragmento y copiarlo; comprobar el portapapeles.
- Con TalkBack activo, recorrer mensajes, etiquetas, imágenes y botones. Confirmar que lee remitente/contenido y anuncia acciones con etiquetas comprensibles.
- Ejecutar y copiar el benchmark en ambos dispositivos. Revisar especialmente scroll, PSS total, parpadeo en la actualización de texto y conservación del ancla.

## Riesgos y límites

- El scroll usa alturas estimadas hasta medir cada fila. Diferencias grandes en contenido con imágenes o tarjetas pueden corregir la posición; el benchmark móvil debe confirmar que la corrección no produce saltos.
- Android puede no exponer el PSS del proceso aislado de Chromium. En ese caso la memoria conjunta no se declara aprobada.
- TalkBack, selección/copia y comportamiento del teclado no se pueden confirmar sin dispositivo.
- El spike no añade deltas reales del proveedor. Las actualizaciones de eventos existentes se envían como parches por `seq`; el benchmark simula una actualización incremental. El cableado del token streaming queda como trabajo pendiente para poder medir la condición completa.
- El ajuste es global para desarrolladores, no por conversación. Sigue siendo opt-in y Compose continúa como valor predeterminado.
- No se modificó Room ni otro esquema. El historial sintético vive únicamente mientras está abierta la pantalla de benchmark.

La decisión de mantener el lienzo, ajustarlo o descartarlo queda pendiente de los informes automedidos y las pruebas manuales en TCL y S23.
