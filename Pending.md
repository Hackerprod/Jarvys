# Pendientes de Jarvys

Actualizado: 2026-10-08 (UTC).

Este archivo mantiene la cola vigente y el estado de cada etapa. Debe actualizarse con cada avance y publicarse en GitHub junto con los cambios. No sustituye las comprobaciones de código, pruebas y APK.

Modo de ejecución: continuar la cola completa. Al cerrar cada pendiente, verificar y publicar el código junto con este archivo y comenzar el siguiente. No esperar feedback ni prueba manual entre etapas; conservar las limitaciones de validación o configuración externa con su estado real. La entrega consolidada y el APK firmado corresponden al final de la cola, sin presentar entregas parciales como cierre total. Solo se preparan versiones intermedias cuando el usuario las solicita expresamente.

## Estado actual

- **v31 completada:** reacciones locales del modelo, Markdown del usuario y lista de chats sin subtítulos restaurados. Pruebas: 994 Full y 910 Play. APK con identidad original entregado.
- **UX15 / v32 implementada y entregada:** APK original firmado de Jarvys, versión 32 / 1.2.26-UX15. Pruebas completas aprobadas: 1089 Full y 1005 Play, sin fallos, errores ni omitidas. Revisión visual y de seguridad completada. Lint sin errores nuevos; permanecen 75 errores heredados en Full y 66 en Play. La prueba real en teléfono ha detectado un incidente prioritario de fallos repetidos de herramientas y pérdida de continuidad. Las pruebas del host no demuestran que ese comportamiento esté resuelto.
- **P0 / preparación v33 cerrada en código y host:** correcciones de herramientas, continuidad, restauración visual y Bots completadas. Suites finales: 1159 Full y 1075 Play, cero fallos/errores/omitidas. Lint sin errores nuevos; deuda restante: 46 Full y 37 Play. Ambos APK sin firma compilados y verificados con paquete original, permisos sin cambios, CRC y alineación correctos.
- **UX23 / preparación v34 cerrada en código y host:** selección por mensaje restaurada para respuestas y traducciones. Suites finales: 1173 Full y 1089 Play, cero fallos/errores/omitidas. Lint sin cambios respecto al P0 (46 Full / 37 Play); ambos APK unsigned verificados. Se conserva el límite de validación física y el comportamiento heredado del enlace descritos abajo.
- **APKFACTORY1 / preparación v35 implementada y validada en host:** fábrica offline nativa, empaquetado sin Gradle por aplicación, firma con identidad por app y skill exclusivo de Coding. Suites: 1238 Full, 1154 Play y 17 runtime, sin fallos; nueve pruebas de SDK JavaScript. La aceptación física Android/ARM64 e instalación/actualización con datos conservados sigue pendiente.
- **UX16 / preparación v36 completada en host:** archivos nativos y descargas directas con 1289 pruebas Full, 1205 Play, 17 runtime y nueve JavaScript aprobadas. Aceptación física pendiente.
- **UX17/UX18 / preparación v37 completada en código y validación de host:** panel lateral, archivados en Ajustes y vista informativa de tareas. Suites: 1320 Full, 1236 Play, 17 runtime y nueve JavaScript aprobadas. Sin incidencias nuevas de lint; lectura conservada con el límite de reflujo descrito abajo.
- **UX19 / preparación v38 completada en código y host:** conectores de Gmail y Drive, con 1467 pruebas Full, 1251 Play, 17 runtime y nueve JavaScript aprobadas. Sin incidencias nuevas de lint; Google Cloud y aceptación física siguen pendientes.
- **Siguiente etapa:** UX20, autenticación y capacidades de GitHub. Después UX21, UX22, UX24 y las tareas críticas de prompts/harness UX25–UX26, salvo nueva priorización explícita.
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

Estado: completado en código y validación de host para preparación v36 / 1.2.29-UX16. La aceptación física permanece pendiente al finalizar la cola; no se entrega ningún APK parcial ni se requiere feedback para continuar con UX17/UX18.

- Añadido `deliver_file` al chat principal: entrega archivos reales de su workspace o de `/project/`, incluidos binarios y APK de la fábrica, mediante una copia independiente de hasta 256 MiB. Devuelve referencia estable, nombre, MIME, tamaño y SHA-256; conserva la tarjeta y el resultado de herramienta después de compactar/reabrir, sin repetir la ejecución. Repetir los mismos bytes/nombre no duplica el adjunto.
- Imágenes generadas, adjuntos recibidos y archivos entregados comparten descarga en segundo plano a la raíz de Downloads. Android 10+ usa MediaStore sin selector por archivo. Android 7–9 solicita únicamente el permiso de almacenamiento limitado a API 28; la denegación se comunica y permite Compartir. No hay permiso de acceso general a todos los archivos.
- Se mantienen previsualización y acciones de archivos no decodificables, cancelación, estado de descarga y Abrir tras confirmación real. Nombres seguros y recibos duraderos evitan colisiones y duplicados entre pulsaciones/reinicios. Los archivos ya publicados se conservan aunque cambien o el recibo quede incompleto. Los errores ambiguos no afirman falsamente que no se guardó nada.
- Fuentes aisladas por conversación, verificadas por ruta, descriptor, tamaño y checksum; copias fuera de los montajes editables del agente y proveedores no exportados con concesiones de lectura para referencias propias. Las herramientas de bots, tareas y procesos proactivos no reciben la capacidad de entrega. No se añaden subidas externas, ejecución ni instalación automática.
- **Validación final:** 1289 pruebas Full, 1205 Play y 17 del runtime aprobadas, sin fallos, errores ni omitidas; XML frescos y 711 archivos de fuente congelados sin cambios. Se añaden 51 regresiones; los 56 casos dirigidos incluyen cinco pruebas existentes de imágenes. El recorrido real de MainActivity abre la imagen, toca Guardar, verifica los bytes descargados y abre únicamente al pulsar Abrir. Nueve pruebas JavaScript y la verificación externa de seis APK de fábrica aprueban; plantilla y skill de fábrica idénticos a v35.
- Cuatro capturas nativas de host revisadas: archivos en ambos temas, imagen y archivo de imagen no decodificable. Las pruebas cubren permisos concedidos/denegados, errores de almacenamiento, cancelación, colisiones, recuperación, conversación extensa, cambios de chat/rotación, ámbitos y carreras de archivos. Los adaptadores del host no certifican MediaStore, diálogos, alias de rutas ni aplicaciones externas del teléfono.
- Lint final regenerado: cero errores o avisos nuevos; deuda heredada de 46 errores Full / 37 Play. Runtime: cero errores y cuatro avisos heredados. Ambos APK Jarvys v36 se compilaron sin firma, con paquete `com.jarvys.agent`, nombre Jarvys, CRC y alineación verificados. La única incorporación al manifiesto es `WRITE_EXTERNAL_STORAGE` con `maxSdkVersion=28`, sin conceder ese permiso en un dispositivo real.
- La primera pasada agregada encontró el contenedor desplazable del diálogo y dos expectativas del conjunto de permisos; ambos requisitos se corrigieron y las suites completas se repitieron con fuente congelada antes de registrar este resultado.

Diseño y límites: `app/FILE_DELIVERY.md`. Evidencia reproducible: `recovery/validation/ux16-file-delivery.json`. Firma final con la clave original existente al terminar toda la cola.

### 3. UX17/UX18: reorganizar el panel lateral y Ajustes

Estado: completado en código y validación acotada de host para preparación v37 / 1.2.30-UX17-UX18. Se continúa con UX19 sin esperar feedback ni entregar un APK parcial; aceptación física pendiente al finalizar la cola.

- Cabecera **Jarvys**, lupa arriba a la derecha y orden **New chat / Nuevo chat**, **Bots**, **Tareas programadas**. Búsqueda desplegable con consulta conservada, limpiar/cancelar y Atrás. Se retiran recarga y archivados del panel. Encabezados de chats sin iconos; **Pinned / Fijados** solo aparece con filas ancladas visibles, sin duplicarlas en actuales.
- **Chats archivados** aparece en Ajustes solo cuando existen. Se puede abrir, restaurar, renombrar o eliminar mediante las validaciones y confirmaciones compartidas; se conserva la protección de chats del sistema. Al retirar el último, la pantalla queda vacía y la entrada desaparece. Persistencia comprobada al recrear y relanzar la Activity.
- La nueva vista de tareas indica honestamente que todavía no está disponible. El gestor y runtime existentes permanecen intactos; no se crean tareas. Se corrigen la prioridad de Atrás, el cierre interrumpido por doble pulsación y la recreación de NavHost que perdía estado al salir del chat.
- **Pruebas finales:** 1320 Full, 1236 Play, 17 runtime y nueve JavaScript aprobadas, sin fallos, errores ni omitidas. También aprueban 72 casos dirigidos, incluidos siete recorridos nativos. Fuente congelada sin cambios y capturas revisadas en ambos temas, español/inglés, 320/360 dp y texto al 200%. Borradores, sesión, conversación, adjuntos y transferencias se conservan.
- La primera suite Full tuvo un timeout aislado en la lista de tareas existente. El caso aislado y la suite Full completa aprobaron después sin cambiar fuente, aserciones ni timeout. La causa exacta no está demostrada.
- **Límite de scroll:** en el host se conservan los mismos mensajes completamente visibles y el punto central de lectura, con un reflujo único de 42 px al primer regreso. El segundo queda exactamente estable, sin deriva acumulativa. Los tamaños apuntan al Markdown asíncrono heredado, pero es una inferencia. No se promete conservación exacta de píxeles ni se añade otro sistema de scroll; queda la aceptación física.
- Lint sin incidencias nuevas: persisten 46 errores Full / 37 Play; los avisos bajan a 281/273. Runtime: cero errores y cuatro avisos heredados. Revisión independiente aprobada para este alcance.
- Ambos APK v37 están compilados **sin firma**, con identidad, permisos, CRC y alineación verificados. Plantilla y skill de fábrica conservados. Los cinco grupos legacy privados siguen intactos y excluidos de las publicaciones.

Diseño y límites: `app/DRAWER_NAVIGATION.md`. Los registros detallados de validación y los artefactos se conservan localmente. No se firmó ni entregó ningún APK; firma final con la clave original al terminar la cola.

### 4. UX19: conectores robustos de Gmail y Drive

Estado: completado en código, revisión independiente y validación de host para preparación v38 / 1.2.31-UX19. Se continúa con UX20. La configuración de Google Cloud, el acceso real y la aceptación física permanecen abiertos; las pruebas simuladas no se presentan como login o envío reales.

- AuthorizationClient interpreta los datos devueltos antes de clasificar la cancelación. Corregida la colisión entre dominios de códigos de estado. Permisos incrementales basados en el conjunto realmente concedido, sin solicitar nuevo consentimiento desde llamadas REST ordinarias.
- Intentos de autorización y aprobaciones de escritura ligados a una generación de sesión/cuenta; cancelación, rotación, doble pulsación, cambio de cuenta y callbacks antiguos no pueden reactivar una sesión ni ejecutar una aprobación en otra cuenta.
- Desconexión local y revocación remota diferenciadas. La revocación solo se marca verificada tras confirmación de Google; una revocación fallida conserva la información de reintento cifrada. Olvidarla requiere advertencia explícita y no se presenta como revocación. Diagnóstico local de paquete/SHA-1 y configuración, sin fingir acceso a la consola.
- Gmail: búsqueda paginada, hilos/respuestas, borradores de lectura/creación/reemplazo/envío, MIME Unicode/charset y adjuntos. Destinatarios y confianza derivan de la sintaxis original de los encabezados; nombres visibles, comentarios y palabras codificadas no añaden destinatarios. Se conserva el formato correcto de Message frente a Draft y los encabezados de respuesta.
- Drive: filtros, paginación y unidades compartidas, metadatos/capacidades, descarga/exportación de Docs/Sheets/Slides, archivos y carpetas. Multipart usa la ruta de upload correcta. Escrituras limitadas a drive.file y archivos autorizados para la app; sin borrado, cambios de permisos ni ampliación automática de alcance.
- Transferencias acotadas y adjuntos nativos inmutables en el chat actual. Las cargas congelan bytes, destino y SHA-256 antes de aprobar; sin rutas arbitrarias, bytes base64 en el contexto ni acceso a archivos del chat desde agentes delegados/tareas de fondo. Límites y formatos: `app/GOOGLE_WORKSPACE.md`.
- Transporte cancelable, HTTPS y rutas restringidas, un refresh ante 401 y 403 accionables. Backoff acotado solo para lecturas. Un diario privado de hashes bloquea la repetición de escrituras inciertas tras errores o reinicios, sin guardar contenido ni credenciales. La reconciliación es manual.
- Los permisos de envío/borradores se comprueban antes de preparar y despachar la acción, para no bloquear como incierta una operación que nunca pudo enviarse. El diario se inicializa solo al usar una escritura; el catálogo permanece libre de acceso prematuro a esa persistencia.
- **Validación final:** 1467 Full, 1251 Play, 17 runtime y nueve JavaScript aprobadas, sin fallos, errores ni omitidas. Son 147 regresiones nuevas en Full y 15 comunes a Play; los 165 casos específicos están incluidos en la agregada. XML frescos y 743 archivos de fuente congelados sin cambios. Las dos capturas nativas comprueban el componente de diagnóstico en 320 dp, inglés/claro y español/oscuro al 200%; no son la hoja real de consentimiento de Google.
- La primera agregada detectó una inicialización prematura del diario durante el descubrimiento del catálogo. Se corrigió sin debilitar la prueba existente. Una comprobación final añadió el guard de permisos de escritura; se repitieron todas las suites sobre la fuente definitiva.
- Lint regenerado sin errores ni avisos nuevos: deuda heredada de 46 errores Full / 37 Play y 275/273 avisos. Se conserva apksig 8.13.2, fijado al contrato probado de la fábrica. La dependencia de los assets generados se declara también para lint, evitando fallos de orden al combinar tareas.
- Ambos APK v38 se verificaron sin firma y debuggable, con paquete/nombre originales, CRC y alineación correctos y permisos sin cambios. Plantilla y skill de fábrica idénticos a v37. Los 19 archivos legacy privados permanecen intactos y excluidos de publicación.
- Límite del proveedor: reemplazar un borrador y actualizar Drive sin ETag tiene comprobación previa, sin garantía atómica frente a otra edición. El envío de borrador usa exactamente el MIME revisado. No se probaron cuentas reales, nuevos grants ni operaciones reales de correo/archivos.

Código y este archivo se publican directamente en **master**, sin PR ni sobrescribir historial. Los informes detallados, capturas y APK se mantienen fuera de las publicaciones de código. Ningún APK de esta etapa se firma o entrega como cierre de la cola.

Referencias: [AuthorizationClient](https://developer.android.com/identity/authorization), [Gmail messages.send](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/send), [Drive uploads](https://developers.google.com/workspace/drive/api/guides/manage-uploads). Se adaptaron contratos y patrones, sin copiar implementaciones externas ni trasladar OAuth de servidor al APK.

### 5. UX20: autenticación y capacidades de GitHub

Estado: implementación y revisión independiente completadas; fuente congelada para validación agregada para preparación v39 / 1.2.32-UX20. Las 188 pruebas dirigidas de GitHub y MCP aprueban, incluidas 124 regresiones nuevas respecto de UX19. Todavía faltan las suites agregadas, lint y la verificación de ambos APK sin firma.

- Corregida la carrera del intento de inicio de sesión: recomponer, cancelar, volver del navegador o recrear la Activity no permite que un callback antiguo guarde credenciales ni cancele un intento nuevo.
- Metadatos OAuth completos y cifrados, caducidad de refresh, identidad de autorización y scopes requeridos visibles. Repo y workflow se solicitan solo mediante selección explícita; reconectar no repite acciones pendientes.
- Herramientas agrupadas y opt-in, con límite de contexto y permisos conservados. Escrituras revisadas por repositorio y método, con argumentos congelados y nueva comprobación de cuenta, permiso y generación al despachar o renovar.
- Puente nativo acotado para crear/actualizar Discussions, commits multifichero con SHA de base atómico y checks de un SHA exacto. El resto usa el MCP oficial; los comentarios/hilos identificados por nodo se vinculan previamente a su repositorio.
- Reintentos solo para lecturas seguras o un rechazo 401 definitivo. Una respuesta perdida, reanudación SSE fallida o ejecución GraphQL incierta conserva un marcador privado persistente y bloquea la repetición automática.
- Las pruebas incluyen flujo simulado issue → archivo → rama → commit → PR borrador → checks, cambios de cuenta/permisos, carreras de reconexión, paginación, cancelación, almacenamiento fallido y ausencia de replay. No se usaron cuentas reales ni se concedieron nuevos permisos. La configuración externa del OAuth App y el teléfono siguen sin verificarse.

- Diagnosticar el inicio de sesión y contrastar el flujo con documentación oficial y los requisitos reales de configuración.
- Preparar un conector robusto para trabajar con pull requests, commits, Discussions e issues, con herramientas y errores claros para el agente.
- Respetar repositorio, cuenta y permisos mínimos; separar lectura y acciones de escritura.
- Implementar estas capacidades no autoriza por sí mismo publicaciones, comentarios, commits, fusiones ni otros cambios externos: cada acción debe respetar la autorización correspondiente.

Diseño y límites de la implementación: `app/GITHUB_CONNECTOR.md`.

### 6. UX21: alineación de permisos en conectores

- Inspeccionar todas las vistas de conectores.
- Fijar los controles de permisos a la derecha, en una misma columna vertical y centrados respecto de su fila.
- Validar etiquetas largas, pantallas estrechas, tamaños grandes de texto y español/inglés, conservando áreas táctiles accesibles.

### 7. UX22: fluidez y gestos del preview web

- Aislar la causa del desplazamiento por saltos antes de aplicar cambios.
- Mejorar scroll, fling y coordinación de gestos anidados del WebView, respetando quién controla cada gesto.
- Evitar que los desplazamientos verticales o hacia arriba dentro del preview abran el panel lateral de la app.
- Conservar navegación legítima, enlaces, zoom e interacción con el teclado.
- Añadir **Ver en Jarvys** al adjunto de una página HTML generada, junto a Descargar y Compartir. Distinguir una página HTML de un archivo APK; abrir la página dentro de la app mediante el preview seguro.
- Servir CSS, JavaScript, imágenes y rutas relativas únicamente desde los recursos autorizados de esa página y conservar la apertura tras reabrir el chat, sin acceso arbitrario a archivos privados ni puentes WebView inseguros.
- Validar rendimiento y gestos en dispositivo físico; las pruebas Robolectric no bastan para demostrar fluidez real.

### 8. UX24: coherencia visual de Bots

Estado: pendiente; se abordará después de UX22 sin alterar la validación de la etapa actual.

- Extender el efecto de trabajo activo a todo el conjunto del bot, incluido icono y nombre, en lugar de limitarlo al texto.
- La tarjeta de trabajo debe resolver el mismo icono estable que la cuadrícula de Bots, tanto para bots personalizados con icono generado como para plantillas integradas.
- Conservar la identidad del icono al actualizar y reabrir; usar un fallback solo cuando no exista un icono válido.
- Mostrar el efecto únicamente mientras el bot esté ejecutando trabajo real y respetar ciclo de vida, visibilidad y movimiento reducido.

### 9. UX25: prompt de producción y harness de Coding

Estado: investigación y propuesta original preparadas; implementación después de UX24, salvo repriorización explícita.

- La auditoría detectó que Coding integrado no declara ejecución aunque el backend Full existe para perfiles personalizados. Alinear declaración y runtime bajo aprobación, sin activar permisos automáticamente.
- Preparadas 18 pruebas de aceptación para integrar el prompt, ejecución y recuperación.
- Redactar un prompt original y completo para Coding, contrastando referencias públicas con el comportamiento real del harness de Jarvys.
- Auditar herramientas disponibles, lectura/escritura, ejecución, planificación, verificación, recuperación, contexto y entrega. No prometer capacidades que el runtime no expone.
- Convertir el contrato en pruebas de comportamiento del agente y del harness, incluidas interrupciones, fallos, límites y continuidad; no limitar la tarea a cambiar un texto.
- Mantener la investigación y clones temporales fuera del proyecto y las publicaciones; respetar procedencia/licencias y no copiar instrucciones privadas o credenciales.

### 10. UX26: contrato del agente principal

Estado: investigación y contrato original preparados; implementación después de UX25, salvo repriorización explícita.

- Preparadas 24 pruebas de aceptación; corregir la entrega de resultados de delegación, el canal de progreso natural y la incorporación de indicaciones durante una ejecución activa.
- Contrastar referencias oficiales de OpenClaw y el runtime actual para definir un contrato original de comunicación, uso de herramientas, delegación y gestión de contexto.
- Alinear prompt y capacidades reales: decisiones, seguimiento, límites de autorización, resultados verificables y continuidad entre turnos.
- Probar el comportamiento real en conversaciones y herramientas, incluidos fallos y recuperación; conservar privacidad y separación de datos no confiables.

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
