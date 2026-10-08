# Pendientes de Jarvys

Actualizado: 2026-10-08 (UTC).

Este archivo mantiene la cola vigente y el estado de cada etapa. Debe actualizarse con cada avance y publicarse en GitHub junto con los cambios. No sustituye las comprobaciones de código, pruebas y APK.

Modo de ejecución: continuar la cola completa. Al cerrar cada pendiente, verificar y publicar el código junto con este archivo y comenzar el siguiente. No esperar feedback ni prueba manual entre etapas; conservar las limitaciones de validación o configuración externa con su estado real. La entrega consolidada y el APK firmado corresponden al final de la cola, sin presentar entregas parciales como cierre total.

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

Estado: correcciones implementadas y revisión dirigida completada; validación agregada final de Full/Play en curso.

La primera pasada agregada ejecutó 1159 pruebas Full y 1075 Play. Detectó únicamente una expectativa antigua de la prueba de cancelación de iconos: la cancelación ahora espera al despacho final ya iniciado, igual que el controlador principal. Se actualizó esa prueba con comprobaciones más fuertes de orden, revisión e icono y se está repitiendo la suite completa, sin excluir pruebas ni cambiar esa protección.

- **Primer checkpoint:** eliminada la dependencia de hard links en identidad/journal y creación de archivos de Coding, operación prohibida por Android a las apps normales. Los registros privados se promocionan completos; los archivos nuevos se crean de forma exclusiva, sin sobrescribir rutas existentes, con comprobación de tamaño y SHA-256.
- Si una creación queda interrumpida, se conservan el destino, el staging y la evidencia de recuperación; se informa un resultado parcial o incierto y no se reejecuta automáticamente. Las actualizaciones de archivos existentes conservan el reemplazo atómico.
- Diez regresiones dirigidas de almacenamiento aprobadas en el host (SDK 34/Robolectric), incluidas colisiones, symlinks, competencia entre creadores, copia parcial, recuperación sin replay y contenido modificado. Revisión independiente completada. Esto no sustituye la validación real en teléfono ni confirma el fallback de API 24.
- **Segundo checkpoint:** detección de fallos repetidos por herramienta/argumentos, aunque el error incluya datos cambiantes; recuperación permitida cuando una acción correctiva aporta progreso real. No se añaden reintentos automáticos de escrituras.
- Corregidos IDs de aprobación inexistentes, acciones desde notificaciones/tarjetas caducadas, carreras con STOP/timeout y registro de callbacks de cancelación. La aprobación se consume una sola vez y los lanzamientos de permisos asíncronos permanecen pendientes hasta su resultado.
- Veintidós nuevas regresiones de bucles, aprobación y cancelación aprobadas, con revisión independiente. La segunda pasada dirigida completa fue de 164 pruebas Full sin fallos; continúan la restauración visual, creación de bots y validación final de ambos sabores.
- **Tercer checkpoint:** historial estructurado del agente principal con IDs y resultados de herramientas persistidos antes de mostrar el resultado, restauración sin replay, evidencia acotada y acceso a artefactos únicamente dentro de la conversación. La compactación conserva resultados incluso si terminan después del resumen y las referencias creadas durante el mismo turno son recuperables.
- Restaurados detalles expandibles, auditoría y vista previa tras recrear la Activity; las tarjetas de acciones interrumpidas distinguen resultado no confirmado y operación no iniciada. El estado en ejecución conserva prioridad frente a tarjetas sintéticas de recuperación. Duplicados de mensajes se eliminan por ID duradero, no por texto.
- La vista previa local ya no inicializa skills, MCP ni su almacén de credenciales para leer HTML. Se conservan las restricciones de rutas, URL, WebView y CSP. La prueba navega por la vista real, lee el HTML existente y confirma que no vuelve a escribirlo.
- Catorce regresiones de continuidad y siete de persistencia/reapertura cubren los casos anteriores; los fallos dirigidos encontrados durante revisión se corrigieron antes de la pasada agregada. Los detalles históricos que versiones antiguas nunca guardaron no se inventan; una vista previa antigua solo se reconecta al archivo actual verificado de ese chat.
- Código de preparación actualizado a versión 33 / 1.2.26-P0, sin entrega parcial. Se realizará la entrega consolidada al terminar la cola.

- Reproducir y diagnosticar los fallos repetidos de herramientas y la pérdida de continuidad observados en v32, contrastando la ejecución real con los contratos de las herramientas y el historial enviado al proveedor.
- Conservar resultados de herramientas y estado relevante entre turnos y reinicios, con límites claros de contexto y sin reejecutar automáticamente acciones externas ambiguas.
- **Reapertura de interfaz:** conservar también el detalle expandible de cada herramienta y la acción de vista previa web. Se ha confirmado que la versión anterior reconstruía solo las etiquetas al reabrir. Restaurar referencias verificadas del workspace, mostrar honestamente los datos que una versión antigua no guardó y no volver a ejecutar herramientas durante la recuperación.
- La aceptación requiere crear archivos/vista previa, reconstruir Activity y almacenamiento, abrir detalles y vista previa desde la conversación restaurada y comprobar que el siguiente turno del modelo recibe la misma evidencia.
- Revisar operaciones de archivos compatibles con las restricciones reales de Android, errores, cancelación, reintentos y controles de aprobación.
- Añadir pruebas de regresión dirigidas y validar en el dispositivo antes de afirmar que se ha corregido el comportamiento real.

### P0 UI. Corregir la presentación de Bots

Estado: corrección implementada y revisión visual dirigida completada; incluida en la validación agregada.

- Alinear cabecera, altura e insets con las vistas de Ajustes; evitar solapamiento con la barra de estado.
- Colocar Añadir como botón en la esquina superior derecha.
- Mostrar dos bots por fila, con icono arriba y nombre debajo, sin tarjetas/contenedores externos ni subtítulos de tipo “Built-in” o “Your reusable assistants”.
- Conservar inmutabilidad de plantillas, edición y deshabilitado desde el detalle, animación de trabajo real y accesibilidad.
- Diecinueve pruebas Compose y diez de capturas aprobadas; revisadas las dos columnas, cabecera, añadido y nombres en español/inglés, ambos temas y texto grande, incluida separación de palabras sin reducir la fuente.

### P0 Bots. Creación completa desde el chat

Estado: herramienta principal implementada y revisada; pruebas dirigidas aprobadas, validación agregada en curso.

- Permitir al agente principal crear y guardar un bot a partir de una petición natural del usuario, con nombre simple, instrucciones en inglés e icono generable y configurable.
- La versión anterior exponía consulta e iconos, pero la creación persistente estaba disponible únicamente desde la interfaz de Bots. Incorporar la herramienta y su registro real en el chat, sin fingir una creación completada.
- Mantener las plantillas protegidas y los permisos por capacidad; crear un bot no concede automáticamente acceso a conectores ni operaciones sensibles.
- Probar con un modelo simulado el recorrido de herramientas reales: creación de definición, generación/asignación de icono, persistencia y descubrimiento en el siguiente turno. Separar un icono fallido de una definición guardada y comunicar el resultado real.
- Creación persistente revisada con una clave estable por petición; repetir la misma petición no duplica el bot ni la generación. Cambiar el contenido aprobado con la misma clave falla de forma segura, incluido el prompt del icono.
- Herramienta excluida de todos los inventarios/ejecuciones de agentes hijos. Selección de skills alineada con el límite real existente de ocho por ejecución, evitando guardar configuraciones imposibles de lanzar.
- Catorce pruebas de creación aprobadas con herramientas/registro/repositorio reales y transporte de imagen simulado; no se ha afirmado una prueba de proveedor real. La vista previa y los límites de ámbito se volvieron a probar tras corregir los fallos detectados.

### UX23. Restaurar selección de texto en respuestas

Estado: fallo confirmado en dispositivo; pendiente después del P0 y antes de nuevas funciones.

- Restaurar selección mediante pulsación larga en los mensajes del agente; la selección actual de los mensajes del usuario debe conservarse.
- Contrastar con UX7/v28: selección por mensaje para respuestas, traducciones, Markdown y código; excluir adjuntos, acciones de pie y mensajes vecinos.
- Validar gestos reales de pulsación larga, selectores y copia a través de Markdown/código y desplazamiento, sin interceptar enlaces ni romper la selección del usuario.
- No incluir esta corrección en la entrega P0 sin revisión y comprobación completas.

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

### 3. UX17/UX18: reorganizar el panel lateral y Ajustes

Estado: requisitos de diseño actualizados; pendiente después de las correcciones P0.

- Cabecera con el título **Jarvys** y la lupa de búsqueda en la esquina superior derecha, junto al título.
- Orden de menús: **New chat**, después **Bots** y después **Tareas programadas**. Esta indicación sustituye la anterior que colocaba Bots primero.
- Bots y Tareas programadas conservan sus iconos; la futura pantalla de tareas debe representar honestamente su disponibilidad, sin simular un planificador operativo.
- Mostrar el encabezado **Pinned** únicamente cuando haya chats anclados. Encabezados de chats sin iconos.
- Quitar **Archived** del panel y situar **Chats archivados** en Ajustes; mostrar ese menú solo si existen conversaciones archivadas. Conservar restauración y acciones sobre conversaciones.
- Quitar el icono de recarga del panel lateral.
- Antes de implementar, revisar la referencia visual proporcionada. Aceptación: orden y cabecera en español/inglés, ambos temas y texto grande; estados con/sin chats anclados y archivados, búsqueda funcional y navegación correcta.

### 4. UX19: conectores robustos de Gmail y Drive

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

### 5. UX20: autenticación y capacidades de GitHub

- Diagnosticar el inicio de sesión y contrastar el flujo con documentación oficial y los requisitos reales de configuración.
- Preparar un conector robusto para trabajar con pull requests, commits, Discussions e issues, con herramientas y errores claros para el agente.
- Respetar repositorio, cuenta y permisos mínimos; separar lectura y acciones de escritura.
- Implementar estas capacidades no autoriza por sí mismo publicaciones, comentarios, commits, fusiones ni otros cambios externos: cada acción debe respetar la autorización correspondiente.

### 6. UX21: alineación de permisos en conectores

- Inspeccionar todas las vistas de conectores.
- Fijar los controles de permisos a la derecha, en una misma columna vertical y centrados respecto de su fila.
- Validar etiquetas largas, pantallas estrechas, tamaños grandes de texto y español/inglés, conservando áreas táctiles accesibles.

### 7. UX22: fluidez y gestos del preview web

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
