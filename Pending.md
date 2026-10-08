# Pendientes de Jarvys

Actualizado: 2026-10-08 (UTC).

Este archivo mantiene la cola vigente y el estado de cada etapa. Debe actualizarse con cada avance y publicarse en GitHub junto con los cambios. No sustituye las comprobaciones de código, pruebas y APK.

## Estado actual

- **v31 completada:** reacciones locales del modelo, Markdown del usuario y lista de chats sin subtítulos restaurados. Pruebas: 994 Full y 910 Play. APK con identidad original entregado.
- **UX15 / v32 implementada, cierre de validación y APK pendiente:** pruebas completas aprobadas, 1089 Full y 1005 Play, sin fallos, errores ni omitidas. Revisión visual de los dos temas, español/inglés, texto grande y movimiento reducido completada. La entrega se cerrará después de la comprobación final de lint, compilación y firma.
- La recuperación es funcional; la paridad completa con el comportamiento de v28 en un teléfono sigue pendiente de validación.

## Cola en orden

### 1. UX15: cerrar Bots y entregar v32

- Menú **Bots con icono**, primero en el panel lateral, sin encabezado sobre los menús.
- Debajo, secciones de chats anclados y actuales/recientes con encabezados **sin iconos**.
- Catálogo en cuadrícula adaptable, con icono arriba y nombre debajo; tema azul y tamaños grandes accesibles.
- Crear bots personalizados mediante una descripción natural, revisar la configuración y guardarla de forma persistente. Permitir editar instrucciones, generar su icono y deshabilitarlos.
- Coding y Android-use son plantillas de runtime no editables. Conservar configuraciones anteriores como bots personalizados cuando corresponda.
- Generar y asignar iconos con un prompt temático libre mediante el backend de imágenes existente. Mantener los ámbitos de archivos, cancelación, revisión de versiones y permisos actuales.
- Mostrar destellos de esfuerzo y barrido de iluminación en el nombre únicamente cuando el bot esté trabajando realmente. Respetar ciclo de vida, visibilidad y movimiento reducido.
- Terminar la validación final y entregar el APK original firmado. No incorporar las tareas posteriores en esta versión.

### 2. APKFACTORY1: fábrica local de APK y skill de Coding

- Verificar la especificación y el contrato del skill antes de implementar.
- Validar la viabilidad en Android ARM64; preparar una plantilla segura WebView/HTML/CSS/JS con DEX nativo precompilado, puente, manifiesto, recursos, empaquetado y firma.
- Cargar el skill únicamente en Coding; mantener al agente principal con información breve de descubrimiento.
- Validar un MVP de notas offline con guardado y exportación, dos identificadores de paquete coexistentes y actualización con datos conservados.
- Entregar por subetapas verificadas, sin simular una compilación o una instalación que no se haya realizado.

### 3. UX16: adjuntos y descargas directas

- Entregar los archivos trabajados como adjuntos nativos del chat.
- Descargar archivos e imágenes a Downloads del sistema sin abrir un selector por archivo.
- Validar ámbitos, rutas, MIME, nombres, colisiones y archivos parciales; mostrar éxito/error y la acción Abrir.
- Mantener las autorizaciones existentes; no añadir instalación automática, subidas externas ni acceso amplio a archivos.

### 4. UX17: chats archivados en Ajustes

- Mover **Chats archivados** a un menú de Ajustes.
- Mostrar ese menú únicamente cuando exista al menos un chat archivado.
- Conservar archivado, restauración y acciones sobre conversaciones.

### 5. UX18: acceso a Tareas programadas

- Añadir **Tareas programadas con icono** inmediatamente debajo de Bots en el panel lateral.
- Preparar el acceso para la implementación futura y representar su disponibilidad de forma honesta, sin simular un planificador operativo.
- Mantener los encabezados de anclados y actuales/recientes sin iconos.

### 6. UX19: conectores robustos de Gmail y Drive

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

### 7. UX20: autenticación y capacidades de GitHub

- Diagnosticar el inicio de sesión y contrastar el flujo con documentación oficial y los requisitos reales de configuración.
- Preparar un conector robusto para trabajar con pull requests, commits, Discussions e issues, con herramientas y errores claros para el agente.
- Respetar repositorio, cuenta y permisos mínimos; separar lectura y acciones de escritura.
- Implementar estas capacidades no autoriza por sí mismo publicaciones, comentarios, commits, fusiones ni otros cambios externos: cada acción debe respetar la autorización correspondiente.

### 8. UX21: alineación de permisos en conectores

- Inspeccionar todas las vistas de conectores.
- Fijar los controles de permisos a la derecha, en una misma columna vertical y centrados respecto de su fila.
- Validar etiquetas largas, pantallas estrechas, tamaños grandes de texto y español/inglés, conservando áreas táctiles accesibles.

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
