# Pendientes de Jarvys

Actualizado: 2026-10-08 (UTC).

Este archivo mantiene la cola vigente y el estado de cada etapa. Debe actualizarse con cada avance y publicarse en GitHub junto con los cambios. No sustituye las comprobaciones de código, pruebas y APK.

## Estado actual

- **v31 completada:** reacciones locales del modelo, Markdown del usuario y lista de chats sin subtítulos restaurados. Pruebas: 994 Full y 910 Play. APK con identidad original entregado.
- **UX15 / v32 implementada y entregada:** APK original firmado de Jarvys, versión 32 / 1.2.26-UX15. Pruebas completas aprobadas: 1089 Full y 1005 Play, sin fallos, errores ni omitidas. Revisión visual y de seguridad completada. Lint sin errores nuevos; permanecen 75 errores heredados en Full y 66 en Play. La prueba real en teléfono ha detectado un incidente prioritario de fallos repetidos de herramientas y pérdida de continuidad. Las pruebas del host no demuestran que ese comportamiento esté resuelto.
- **Siguiente etapa:** P0 de herramientas y continuidad del agente; después, APKFACTORY1 y la cola restante.
- La paridad completa con el comportamiento de v28 en un teléfono sigue pendiente de validación; la fiabilidad del agente es prioritaria antes de añadir funciones.

## Última etapa completada

### UX15: Bots y v32

- Menú **Bots con icono**, primero en el panel lateral, sin encabezado sobre los menús.
- Debajo, secciones de chats anclados y actuales/recientes con encabezados **sin iconos**.
- Catálogo en cuadrícula adaptable, con icono arriba y nombre debajo; tema azul y tamaños grandes accesibles.
- Crear bots personalizados mediante una descripción natural, revisar la configuración y guardarla de forma persistente. Permitir editar instrucciones, generar su icono y deshabilitarlos.
- Coding y Android-use son plantillas de runtime no editables. Conservar configuraciones anteriores como bots personalizados cuando corresponda.
- Generar y asignar iconos con un prompt temático libre mediante el backend de imágenes existente. Mantener los ámbitos de archivos, cancelación, revisión de versiones y permisos actuales.
- Mostrar destellos de esfuerzo y barrido de iluminación en el nombre únicamente cuando el bot esté trabajando realmente. Respetar ciclo de vida, visibilidad y movimiento reducido.
- Implementación entregada con pruebas, APK original firmado y código respaldado. El incidente P0 observado en el teléfono sigue abierto; las tareas posteriores no están incluidas en v32.
- Generación de configuraciones e iconos verificada con dobles de prueba; la comprobación en vivo con la cuenta/proveedor del dispositivo sigue pendiente.

## Cola en orden

### P0. Herramientas y continuidad del agente

Estado: causas confirmadas y corrección en curso, antes de APKFACTORY1.

- **Primer checkpoint:** eliminada la dependencia de hard links en identidad/journal y creación de archivos de Coding, operación prohibida por Android a las apps normales. Los registros privados se promocionan completos; los archivos nuevos se crean de forma exclusiva, sin sobrescribir rutas existentes, con comprobación de tamaño y SHA-256.
- Si una creación queda interrumpida, se conservan el destino, el staging y la evidencia de recuperación; se informa un resultado parcial o incierto y no se reejecuta automáticamente. Las actualizaciones de archivos existentes conservan el reemplazo atómico.
- Diez regresiones dirigidas de almacenamiento aprobadas en el host (SDK 34/Robolectric), incluidas colisiones, symlinks, competencia entre creadores, copia parcial, recuperación sin replay y contenido modificado. Revisión independiente completada. Esto no sustituye la validación real en teléfono ni confirma el fallback de API 24.
- Diagnóstico de continuidad confirmado: el historial de herramientas del agente principal no se restauraba entre turnos, aunque la interfaz conservaba sus tarjetas. La persistencia estructurada, la compactación, los reintentos sin progreso y las carreras de aprobación siguen en revisión y pruebas; no se declara una entrega lista.

- Reproducir y diagnosticar los fallos repetidos de herramientas y la pérdida de continuidad observados en v32, contrastando la ejecución real con los contratos de las herramientas y el historial enviado al proveedor.
- Conservar resultados de herramientas y estado relevante entre turnos y reinicios, con límites claros de contexto y sin reejecutar automáticamente acciones externas ambiguas.
- Revisar operaciones de archivos compatibles con las restricciones reales de Android, errores, cancelación, reintentos y controles de aprobación.
- Añadir pruebas de regresión dirigidas y validar en el dispositivo antes de afirmar que se ha corregido el comportamiento real.

### P0 UI. Corregir la presentación de Bots

Estado: corrección prioritaria junto al incidente del agente.

- Alinear cabecera, altura e insets con las vistas de Ajustes; evitar solapamiento con la barra de estado.
- Colocar Añadir como botón en la esquina superior derecha.
- Mostrar dos bots por fila, con icono arriba y nombre debajo, sin tarjetas/contenedores externos ni subtítulos de tipo “Built-in” o “Your reusable assistants”.
- Conservar inmutabilidad de plantillas, edición y deshabilitado desde el detalle, animación de trabajo real y accesibilidad.
- Revisar capturas y geometría con español/inglés, ambos temas y texto grande antes de entregar.

### 1. APKFACTORY1: fábrica local de APK y skill de Coding

- Verificar la especificación y el contrato del skill antes de implementar.
- Validar la viabilidad en Android ARM64; preparar una plantilla segura WebView/HTML/CSS/JS con DEX nativo precompilado, puente, manifiesto, recursos, empaquetado y firma.
- Cargar el skill únicamente en Coding; mantener al agente principal con información breve de descubrimiento.
- Validar un MVP de notas offline con guardado y exportación, dos identificadores de paquete coexistentes y actualización con datos conservados.
- Entregar por subetapas verificadas, sin simular una compilación o una instalación que no se haya realizado.

### 2. UX16: adjuntos y descargas directas

- Entregar los archivos trabajados como adjuntos nativos del chat.
- Descargar archivos e imágenes a Downloads del sistema sin abrir un selector por archivo.
- Validar ámbitos, rutas, MIME, nombres, colisiones y archivos parciales; mostrar éxito/error y la acción Abrir.
- Mantener las autorizaciones existentes; no añadir instalación automática, subidas externas ni acceso amplio a archivos.

### 3. UX17: chats archivados en Ajustes

- Mover **Chats archivados** a un menú de Ajustes.
- Mostrar ese menú únicamente cuando exista al menos un chat archivado.
- Conservar archivado, restauración y acciones sobre conversaciones.

### 4. UX18: acceso a Tareas programadas

- Añadir **Tareas programadas con icono** inmediatamente debajo de Bots en el panel lateral.
- Preparar el acceso para la implementación futura y representar su disponibilidad de forma honesta, sin simular un planificador operativo.
- Mantener los encabezados de anclados y actuales/recientes sin iconos.

### 5. UX19: conectores robustos de Gmail y Drive

Estado: pendiente de implementación; diagnóstico e investigación realizados.

- Mantener AuthorizationClient en Android y leer la respuesta del proveedor antes de clasificar una salida como cancelación. Cubrir retorno a la app, rotación, doble pulsación, cambio de cuenta, revocación y permisos parciales.
- Verificar proyecto, APIs habilitadas, consentimiento/usuarios de prueba y coincidencia de paquete y SHA-1 del APK instalado. Esta configuración externa sigue pendiente de comprobar.
- Corregir contratos REST defectuosos también presentes en v28: `messages.send` recibe `raw` en la raíz de Message; `drafts.create` conserva el contenedor `message`; la subida multipart de Drive usa `/upload/drive/v3/files`.
- Gmail: búsqueda paginada con continuación, hilos/respuestas, MIME y charset, descarga de adjuntos, borradores y envío con revisión y autorización.
- Drive: búsqueda paginada y filtros, carpetas/unidades compartidas, descarga/exportación, creación y actualización con transferencias acotadas.
- Resiliencia: un refresh ante 401, errores 403 accionables, backoff acotado con jitter/Retry-After ante 429/5xx, cancelación efectiva y protección contra envíos/subidas duplicados tras resultados ambiguos. No registrar tokens ni contenido privado.
- Adaptar clientes y patrones de herramientas respetando sus licencias. No copiar flujos OAuth de servidor ni reintentos de escritura sin ajustarlos al runtime Android.
- Aceptación: comprobar método, ruta, headers y esquema en tests; cubrir login bloqueado/cancelado, caducidad, revocación, permisos parciales, paginación, MIME/adjuntos, cargas, cancelación y deduplicación. Después, realizar una prueba manual autorizada en el APK firmado; los mocks no prueban el acceso real.
- Mantener permisos mínimos y no crear ni ampliar autorizaciones persistentes sin aprobación.

Referencias: [AuthorizationClient](https://developer.android.com/identity/authorization), [Gmail messages.send](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/send), [Drive files.create](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/create), [cliente oficial Java](https://github.com/googleapis/google-api-java-client), [Google Workspace CLI](https://github.com/googleworkspace/cli), [Google Workspace MCP](https://github.com/taylorwilsdon/google_workspace_mcp).

### 6. UX20: autenticación y capacidades de GitHub

- Diagnosticar el inicio de sesión y contrastar el flujo con documentación oficial y los requisitos reales de configuración.
- Preparar un conector robusto para trabajar con pull requests, commits, Discussions e issues, con herramientas y errores claros para el agente.
- Respetar repositorio, cuenta y permisos mínimos; separar lectura y acciones de escritura.
- Implementar estas capacidades no autoriza por sí mismo publicaciones, comentarios, commits, fusiones ni otros cambios externos: cada acción debe respetar la autorización correspondiente.

### 7. UX21: alineación de permisos en conectores

- Inspeccionar todas las vistas de conectores.
- Fijar los controles de permisos a la derecha, en una misma columna vertical y centrados respecto de su fila.
- Validar etiquetas largas, pantallas estrechas, tamaños grandes de texto y español/inglés, conservando áreas táctiles accesibles.

### 8. UX22: fluidez y gestos del preview web

- Aislar la causa del desplazamiento por saltos antes de aplicar cambios.
- Mejorar scroll, fling y coordinación de gestos anidados del WebView, respetando quién controla cada gesto.
- Evitar que los desplazamientos verticales o hacia arriba dentro del preview abran el panel lateral de la app.
- Conservar navegación legítima, enlaces, zoom e interacción con el teclado.
- Validar rendimiento y gestos en dispositivo físico; las pruebas Robolectric no bastan para demostrar fluidez real.

## Validaciones que siguen abiertas

- Comprobar en dispositivo la recuperación de proyectos/archivos, imágenes y gestos, selección/copia, ejecución real y flujos de autenticación. Las pruebas del host no equivalen a una pasada completa en teléfono.
- Continuar el diagnóstico del error DNS en el intercambio OAuth del login de navegador. No hay una regresión demostrada frente a v28; el flujo de código de dispositivo sigue siendo una alternativa disponible.
- Android-use está limitado a conectores nativos del dispositivo registrados. No incorpora todavía el puente de accesibilidad heredado ni herramientas ADB/Python.
- La recuperación reanudable de misiones corresponde a perfiles versionados con proyecto de conversación; las misiones legacy no deben relanzarse automáticamente tras reiniciar.
- Mantener visible la deuda de lint heredada y evitar errores nuevos. Indicar el tipo de compilación y las comprobaciones realizadas en cada entrega.

## Entregas y respaldo

- Usar el paquete original `com.jarvys.agent` y el nombre **Jarvys** para las entregas normales.
- Compilar sin firma durante el desarrollo; firmar la entrega validada con la clave aprobada existente. No generar otra clave como sustitución automática.
- Publicar progresivamente el código y este **Pending.md**, y verificar el resultado remoto antes de afirmar que quedó respaldado.
- Mantener en este archivo requisitos y estado del producto, sin conversaciones privadas, credenciales ni datos de usuarios.
