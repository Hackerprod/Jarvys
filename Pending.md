# Pendientes de Jarvys

Actualizado: 2026-10-08 (UTC).

Este archivo mantiene la cola vigente y el estado de cada etapa. Debe actualizarse con cada avance y publicarse en GitHub junto con los cambios. No sustituye las comprobaciones de código, pruebas y APK.

Modo de ejecución: continuar la cola completa. Al cerrar cada pendiente, verificar y publicar el código junto con este archivo y comenzar el siguiente. No esperar feedback ni prueba manual entre etapas; conservar las limitaciones de validación o configuración externa con su estado real. La entrega consolidada y el APK firmado corresponden al final de la cola, sin presentar entregas parciales como cierre total.

## Estado actual

- **v31 completada:** reacciones locales del modelo, Markdown del usuario y lista de chats sin subtítulos restaurados. Pruebas: 994 Full y 910 Play. APK con identidad original entregado.
- **UX15 / v32 implementada y entregada:** APK original firmado de Jarvys, versión 32 / 1.2.26-UX15. Pruebas completas aprobadas: 1089 Full y 1005 Play, sin fallos, errores ni omitidas. Revisión visual y de seguridad completada. Lint sin errores nuevos; permanecen 75 errores heredados en Full y 66 en Play. La prueba real en teléfono ha detectado un incidente prioritario de fallos repetidos de herramientas y pérdida de continuidad. Las pruebas del host no demuestran que ese comportamiento esté resuelto.
- **P0 / preparación v33 cerrada en código y host:** correcciones de herramientas, continuidad, restauración visual y Bots completadas. Suites finales: 1159 Full y 1075 Play, cero fallos/errores/omitidas. Lint sin errores nuevos; deuda restante: 46 Full y 37 Play. Ambos APK sin firma compilados y verificados con paquete original, permisos sin cambios, CRC y alineación correctos.
- **UX23 / preparación v34 cerrada en código y host:** selección por mensaje restaurada para respuestas y traducciones. Suites finales: 1173 Full y 1089 Play, cero fallos/errores/omitidas. Lint sin cambios respecto al P0 (46 Full / 37 Play); ambos APK unsigned verificados. Se conserva el límite de validación física y el comportamiento heredado del enlace descritos abajo.
- **APKFACTORY1 / preparación v35 implementada y validada en host:** fábrica offline nativa, empaquetado sin Gradle por aplicación, firma con identidad por app y skill exclusivo de Coding. Suites: 1238 Full, 1154 Play y 17 runtime, sin fallos; nueve pruebas de SDK JavaScript. La aceptación física Android/ARM64 e instalación/actualización con datos conservados sigue pendiente.
- **Siguiente etapa:** UX16 y la cola restante. No se entrega un APK parcial ni se espera feedback entre etapas.
- La paridad completa con el comportamiento de v28 en un teléfono sigue pendiente de validación; la fiabilidad del agente es prioritaria antes de añadir funciones.

## Entregas previas

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

Estado: completado en código, revisión y validación de host; pendiente la comprobación final en el dispositivo al terminar la cola.

Validación final del código congelado: 1159 pruebas Full y 1075 Play aprobadas, sin fallos, errores ni omitidas; XML frescos y fuentes idénticas durante pruebas, lint y compilación. La anotación API 26 documenta el backend NIO ya protegido por comprobaciones de SDK. Lint no añade errores y resuelve 29 heredados por sabor: quedan 46 Full y 37 Play. Los dos APK de preparación son unsigned y debuggable; no constituyen una entrega firmada.

- **Primer checkpoint:** eliminada la dependencia de hard links en identidad/journal y creación de archivos de Coding, operación prohibida por Android a las apps normales. Los registros privados se promocionan completos; los archivos nuevos se crean de forma exclusiva, sin sobrescribir rutas existentes, con comprobación de tamaño y SHA-256.
- Si una creación queda interrumpida, se conservan el destino, el staging y la evidencia de recuperación; se informa un resultado parcial o incierto y no se reejecuta automáticamente. Las actualizaciones de archivos existentes conservan el reemplazo atómico.
- Diez regresiones dirigidas de almacenamiento aprobadas en el host (SDK 34/Robolectric), incluidas colisiones, symlinks, competencia entre creadores, copia parcial, recuperación sin replay y contenido modificado. Revisión independiente completada. Esto no sustituye la validación real en teléfono ni confirma el fallback de API 24.
- **Segundo checkpoint:** detección de fallos repetidos por herramienta/argumentos, aunque el error incluya datos cambiantes; recuperación permitida cuando una acción correctiva aporta progreso real. No se añaden reintentos automáticos de escrituras.
- Corregidos IDs de aprobación inexistentes, acciones desde notificaciones/tarjetas caducadas, carreras con STOP/timeout y registro de callbacks de cancelación. La aprobación se consume una sola vez y los lanzamientos de permisos asíncronos permanecen pendientes hasta su resultado.
- Veintidós nuevas regresiones de bucles, aprobación y cancelación aprobadas, con revisión independiente e incluidas en las suites finales de ambos sabores.
- **Tercer checkpoint:** historial estructurado del agente principal con IDs y resultados de herramientas persistidos antes de mostrar el resultado, restauración sin replay, evidencia acotada y acceso a artefactos únicamente dentro de la conversación. La compactación conserva resultados incluso si terminan después del resumen y las referencias creadas durante el mismo turno son recuperables.
- Restaurados detalles expandibles, auditoría y vista previa tras recrear la Activity; las tarjetas de acciones interrumpidas distinguen resultado no confirmado y operación no iniciada. El estado en ejecución conserva prioridad frente a tarjetas sintéticas de recuperación. Duplicados de mensajes se eliminan por ID duradero, no por texto.
- La vista previa local ya no inicializa skills, MCP ni su almacén de credenciales para leer HTML. Se conservan las restricciones de rutas, URL, WebView y CSP. La prueba navega por la vista real, lee el HTML existente y confirma que no vuelve a escribirlo.
- Catorce regresiones de continuidad y siete de persistencia/reapertura cubren los casos anteriores; los fallos dirigidos encontrados durante revisión se corrigieron antes de la pasada agregada. Los detalles históricos que versiones antiguas nunca guardaron no se inventan; una vista previa antigua solo se reconecta al archivo actual verificado de ese chat.
- Código de preparación actualizado a versión 33 / 1.2.26-P0, sin entrega parcial. Se realizará la entrega consolidada al terminar la cola.

- Causas de los fallos repetidos y la pérdida de continuidad identificadas mediante la evidencia del dispositivo, código y contratos de herramientas/proveedor; corregidas y cubiertas por regresiones.
- Resultados y estado relevante conservados entre turnos y reinicios, con límites de contexto y sin reejecución automática de acciones ambiguas.
- **Reapertura de interfaz:** conservar también el detalle expandible de cada herramienta y la acción de vista previa web. Se ha confirmado que la versión anterior reconstruía solo las etiquetas al reabrir. Restaurar referencias verificadas del workspace, mostrar honestamente los datos que una versión antigua no guardó y no volver a ejecutar herramientas durante la recuperación.
- Recorrido de aceptación aprobado en host: herramientas reales crean archivo/vista previa, se recrean Activity y almacenamiento, se abren detalles y WebView lee el HTML guardado, sin repetir la escritura; el siguiente turno del proveedor conserva la evidencia.
- Revisadas las restricciones Android de archivos, errores, cancelación, reintentos y aprobación. Las pruebas de host no se presentan como comprobación física del teléfono.
- Añadir pruebas de regresión dirigidas y validar en el dispositivo antes de afirmar que se ha corregido el comportamiento real.

### P0 UI. Corregir la presentación de Bots

Estado: completado; pruebas agregadas y revisión visual aprobadas en ambos sabores.

- Alinear cabecera, altura e insets con las vistas de Ajustes; evitar solapamiento con la barra de estado.
- Colocar Añadir como botón en la esquina superior derecha.
- Mostrar dos bots por fila, con icono arriba y nombre debajo, sin tarjetas/contenedores externos ni subtítulos de tipo “Built-in” o “Your reusable assistants”.
- Conservar inmutabilidad de plantillas, edición y deshabilitado desde el detalle, animación de trabajo real y accesibilidad.
- Diecinueve pruebas Compose y diez de capturas aprobadas; revisadas las dos columnas, cabecera, añadido y nombres en español/inglés, ambos temas y texto grande, incluida separación de palabras sin reducir la fuente.

### P0 Bots. Creación completa desde el chat

Estado: completado en código; revisión y pruebas agregadas aprobadas. El proveedor real de imágenes se verificará en el dispositivo al finalizar la cola.

- Permitir al agente principal crear y guardar un bot a partir de una petición natural del usuario, con nombre simple, instrucciones en inglés e icono generable y configurable.
- La versión anterior exponía consulta e iconos, pero la creación persistente estaba disponible únicamente desde la interfaz de Bots. Incorporar la herramienta y su registro real en el chat, sin fingir una creación completada.
- Mantener las plantillas protegidas y los permisos por capacidad; crear un bot no concede automáticamente acceso a conectores ni operaciones sensibles.
- Probar con un modelo simulado el recorrido de herramientas reales: creación de definición, generación/asignación de icono, persistencia y descubrimiento en el siguiente turno. Separar un icono fallido de una definición guardada y comunicar el resultado real.
- Creación persistente revisada con una clave estable por petición; repetir la misma petición no duplica el bot ni la generación. Cambiar el contenido aprobado con la misma clave falla de forma segura, incluido el prompt del icono.
- Herramienta excluida de todos los inventarios/ejecuciones de agentes hijos. Selección de skills alineada con el límite real existente de ocho por ejecución, evitando guardar configuraciones imposibles de lanzar.
- Catorce pruebas de creación aprobadas con herramientas/registro/repositorio reales y transporte de imagen simulado; no se ha afirmado una prueba de proveedor real. La vista previa y los límites de ámbito se volvieron a probar tras corregir los fallos detectados.

### UX23. Restaurar selección de texto en respuestas

Estado: completado en código, revisión y validación de host para preparación v34 / 1.2.27-UX23. Las 39 pruebas dirigidas y la repetición final de 1173 Full / 1089 Play aprueban sin fallos, errores ni omitidas. XML frescos y fuente idéntica durante pruebas, lint y compilación. El informe de lint regenerado confirma cero errores/avisos nuevos y la deuda heredada de 46 Full / 37 Play.

- Confirmada la omisión durante la recuperación de los dos contenedores de selección presentes en v28: cuerpo de respuesta y cuerpo de traducción. Restaurados por mensaje, sin envolver encabezados, sugerencias, acciones de pie, adjuntos ni vecinos; no se modifica la frontera táctil pasiva del compositor.
- Una regresión de pulsación larga reproduce el fallo en la fuente P0 antes de aplicar el cambio: no se abre la selección. Se añaden pruebas de gestos, selectores, arrastre, copia, Markdown/código, enlaces, traducciones, reacciones, temas y texto grande, scroll y foco/IME.
- La prueba existente de Tareas esperaba inmediatamente un menú recién insertado mientras la pantalla recargaba su snapshot en IO. Se añade una espera acotada a la fila y su posición, conservando clics y todas las aserciones; no cambia el producto. Sus cinco pruebas vuelven a pasar en Full y Play. El primer lint conserva exactamente la deuda P0 (46 Full / 37 Play), sin nuevos errores ni avisos.
- Catorce nuevas regresiones verifican selección y copia reales por puntero, arrastre de selectores, aislamiento, sugerencias, código, traducción, scroll, temas/fuentes y transición de selección activa a foco del compositor. Las pruebas distinguen el cursor del campo de los selectores de rango.
- Los toques ordinarios sobre enlaces siguen funcionando. Se aisló un comportamiento heredado de Compose 1.9: una pulsación larga directamente sobre la etiqueta de un enlace puede seleccionar y activar el enlace; se reproduce igual en el contenedor del usuario sin cambios. Esta restauración no reescribe los gestos de la dependencia.
- La validación del host sustituye únicamente la presentación del menú y la lupa; no certifica el teléfono, el menú nativo ni un teclado físico. Los dos APK se compilaron sin firma, sin entrega parcial ni clave nueva; paquete com.jarvys.agent, nombre Jarvys, versión 34 / 1.2.27-UX23, permisos sin cambios, CRC y alineación válidos.

- Paridad de los contenedores contrastada con el Java decompilado y smali de v28. Se mantiene la selección del usuario, los controles de copia y traducción y el ámbito por mensaje.
- Seis capturas nativas de host revisadas en ambos temas y tamaños de texto, incluida la transición al compositor con inset IME simulado. No muestran la lupa, barra flotante ni ventanas separadas de selectores; estos últimos se verifican mediante pruebas.
- Evidencia reproducible y límites de alcance: `recovery/validation/ux23-text-selection.json`. Se continúa con APKFACTORY1 sin esperar validación manual ni entregar v34 por separado.

### 1. APKFACTORY1: fábrica local de APK y skill de Coding

Estado: MVP offline completado en código, revisión y validación de host para preparación v35 / 1.2.28-APKFACTORY1. La aceptación física de Android ARM64, Keystore, WebView/SAF e instalación/actualización aún no se ha realizado; no se presenta como completada. Se continúa la cola independiente sin esperar feedback ni entregar un APK parcial.

- **A. Viabilidad:** generador Java/Kotlin dentro de Jarvys, sin Gradle, PRoot, compiladores nativos ni emulación x86 por aplicación. Reutiliza una plantilla DEX compilada una vez. La fábrica exige API 26 para acceso acotado a archivos; las apps generadas admiten API 24. También se verificó procedencia oficial de aapt/aapt2 y zipalign ARM64/glibc de Ubuntu Noble, sin ejecutar esos binarios ni instalar paquetes en el teléfono.
- **B. Runtime:** WebViewCompat con origen local exacto y frame principal, API Promise tipada y acotada; almacenamiento privado, exportación SAF, compartir, portapapeles con confirmación, háptica e información básica. El MVP offline declara cero permisos Android. Otras APIs requieren una actualización nativa revisada; no se conceden ni simulan capacidades ausentes.
- **C. Empaquetado y firma:** transformación binaria AXML/ARSC validada, identificadores/nombres/iconos independientes y resolución del icono desde su recurso real. Se conserva DEX exacto, fuente/procedencia por SHA-256, ZIP determinista y alineado, límites de archivos/JSON y escritura exclusiva sin sobrescribir. Las operaciones parciales conservan evidencia y no se reproducen automáticamente.
- Firma por aplicación con AndroidKeyStore bajo aprobación explícita y continuidad por certificado/versión. No hay clave debug compartida ni reutilización de la clave de Jarvys. Perder la clave, borrar datos o desinstalar Jarvys puede impedir futuras actualizaciones; no se promete copia o transferencia de claves. Se verificó criptografía real en host con claves efímeras en RAM, no con el proveedor físico Android.
- **D. Skill:** `com.jarvys.apk-factory`, 11 516 bytes, integrado en SkillRepository/read_skill exclusivamente para Coding; el principal solo recibe descubrimiento breve. Deshabilitarlo conserva Coding ordinario y revoca misiones existentes con fábrica. Coding v2 conserva la identidad/evidencia de misiones v1 sin relanzarlas silenciosamente. Diseño y procedimiento detallado: `app/APK_FACTORY.md`.
- **E. Validación:** 1238 pruebas Full, 1154 Play y 17 del runtime aprobadas, sin fallos/errores/omitidas, XML frescos y fuente sin cambios. Nueve pruebas JavaScript aprobadas. AAPT2, zipalign y apksigner verificaron seis APK de prueba: dos IDs y una versión 2 con la misma clave en cada sabor, DEX idéntico, cero permisos y firmas v2/v3 válidas. No se instalaron ni se atribuye conservación real de datos a esta inspección.
- Revisión independiente cerrada: revocación durante firma/publicación, almacenamiento concurrente, iconos optimizados, límites de directorios/relecturas, PNG normalizados y JSON estricto frente a entradas adversariales. El runtime restringe navegación/red/archivos y explicita exclusiones de backup/transferencia. La prueba visual de navegador no se completó por restricciones del entorno; no sustituye la aceptación nativa.
- Lint regenerado sin errores ni avisos nuevos en Jarvys; permanecen 46 errores Full / 37 Play. El módulo runtime tiene cero errores y cuatro avisos documentados: target 35, icono de plantilla y comprobaciones conservadoras de WebView/backup.
- Ambos APK Jarvys v35 se compilaron por la ruta de desarrollo predeterminada, sin firma, con paquete/nombre originales, permisos sin cambios, CRC y alineación verificados. Eliminada la firma debug automática heredada; la entrega final continúa con el firmador externo y la clave original aprobada. No se ha entregado ningún APK parcial.

Evidencia reproducible y límites: `recovery/validation/apkfactory-v1.json`. La instalación coexistente, exportación y actualización con datos conservados se comprobarán en el dispositivo al terminar la cola.

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
