# Pendientes de Jarvys

Actualizado: 2026-10-08 (UTC).

Este archivo mantiene la cola vigente y el estado de cada etapa. Debe actualizarse con cada avance y publicarse en GitHub junto con los cambios. No sustituye las comprobaciones de código, pruebas y APK.

Modo de ejecución: continuar la cola completa. Al cerrar cada pendiente o corrección, verificar y publicar el código junto con este archivo, entregar el APK firmado con la identidad original y comenzar el siguiente. No esperar feedback ni prueba manual entre etapas; conservar las limitaciones de validación o configuración externa con su estado real. Los checkpoints de código en curso no equivalen a una entrega terminada: el APK se comparte cuando su etapa queda validada.

## Estado actual

- **v31 completada:** reacciones locales del modelo, Markdown del usuario y lista de chats sin subtítulos restaurados. Pruebas: 994 Full y 910 Play. APK con identidad original entregado.
- **UX15 / v32 implementada y entregada:** APK original firmado de Jarvys, versión 32 / 1.2.26-UX15. Pruebas completas aprobadas: 1089 Full y 1005 Play, sin fallos, errores ni omitidas. Revisión visual y de seguridad completada. Lint sin errores nuevos; permanecen 75 errores heredados en Full y 66 en Play. La prueba real en teléfono ha detectado un incidente prioritario de fallos repetidos de herramientas y pérdida de continuidad. Las pruebas del host no demuestran que ese comportamiento esté resuelto.
- **P0 / preparación v33 cerrada en código y host:** correcciones de herramientas, continuidad, restauración visual y Bots completadas. Suites finales: 1159 Full y 1075 Play, cero fallos/errores/omitidas. Lint sin errores nuevos; deuda restante: 46 Full y 37 Play. Ambos APK sin firma compilados y verificados con paquete original, permisos sin cambios, CRC y alineación correctos.
- **UX23 / preparación v34 cerrada en código y host:** selección por mensaje restaurada para respuestas y traducciones. Suites finales: 1173 Full y 1089 Play, cero fallos/errores/omitidas. Lint sin cambios respecto al P0 (46 Full / 37 Play); ambos APK unsigned verificados. Se conserva el límite de validación física y el comportamiento heredado del enlace descritos abajo.
- **APKFACTORY1 / preparación v35 implementada y validada en host:** fábrica offline nativa, empaquetado sin Gradle por aplicación, firma con identidad por app y skill exclusivo de Coding. Suites: 1238 Full, 1154 Play y 17 runtime, sin fallos; nueve pruebas de SDK JavaScript. La aceptación física Android/ARM64 e instalación/actualización con datos conservados sigue pendiente.
- **UX16 / preparación v36 completada en host:** archivos nativos y descargas directas con 1289 pruebas Full, 1205 Play, 17 runtime y nueve JavaScript aprobadas. Aceptación física pendiente.
- **UX17/UX18 / preparación v37 completada en código y validación de host:** panel lateral, archivados en Ajustes y vista informativa de tareas. Suites: 1320 Full, 1236 Play, 17 runtime y nueve JavaScript aprobadas. Sin incidencias nuevas de lint; lectura conservada con el límite de reflujo descrito abajo.
- **UX19 / preparación v38 completada en código y host:** conectores de Gmail y Drive, con 1467 pruebas Full, 1251 Play, 17 runtime y nueve JavaScript aprobadas. Sin incidencias nuevas de lint; Google Cloud y aceptación física siguen pendientes.
- **UX20 / preparación v39 completada en código y host:** autenticación y capacidades de GitHub, más diagnóstico persistente del fallo de Google. Pruebas: 1600 Full, 1378 Play, 17 runtime y nueve JavaScript aprobadas; lint sin incidencias nuevas. Ambos APK originales sin firma verificados y preparados para firma y entrega. Los accesos reales de GitHub y Google siguen sin verificarse.
- **UX21 / preparación v40 completada en código y host:** permisos alineados en todas las vistas de conectores. Pruebas finales: 1643 Full, 1416 Play, 17 runtime y nueve JavaScript aprobadas. Lint sin incidencias nuevas; ambos APK originales sin firma verificados para firma y entrega. Validación física pendiente.
- **UX22 / preparación v41 completada en código y host:** gestos nativos del preview y apertura interna de HTML entregado. Suites finales: 1686 Full, 1459 Play, 17 runtime y nueve JavaScript aprobadas; lint sin incidencias nuevas. Ambos APK originales sin firma verificados para firma y entrega. La fluidez real de Chromium en teléfono sigue pendiente de aceptación.
- **UX24 / preparación v42 completada en código y host:** efecto de trabajo sobre icono y nombre e identidad visual compartida entre cuadrícula y misiones. Suites finales: 1701 Full, 1474 Play, 17 runtime y nueve JavaScript aprobadas; sin incidencias nuevas de lint. Ambos APK originales sin firma verificados para firma y entrega.
- **UX25 / preparación v43 completada en código y host:** contrato original de ingeniería, ejecución Full con aprobación por comando, modo de solo lectura y límites de jobs. Suites finales: 1725 Full, 1492 Play, 17 runtime y nueve JavaScript aprobadas; sin incidencias nuevas de lint. Ambos APK originales sin firma verificados para firma externa y entrega. Evaluación conductual con modelo real y aceptación física siguen pendientes.
- **Siguiente etapa:** UX26, contrato del agente principal, salvo nueva priorización explícita.
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

### Incidencia de Gmail en v38: diagnóstico visible

Estado: corrección acotada completada y validada en host para v39. Una prueba real de v38 mostró un fallo genérico de autorización; esa evidencia no permite identificar su causa. Se corrigieron el Toast truncado y la pérdida de fase/código, pero no se declara resuelto el inicio de sesión real.

- Conservar únicamente código numérico, fase permitida y categoría local; nunca el mensaje bruto, Intent, tokens o contenido de la cuenta.
- Mostrar el diagnóstico completo y persistente en la vista, con reintento y acceso a la información de configuración ya existente.
- Probar la presentación y clasificación con dobles de prueba. No declarar solucionado el acceso real a Gmail sin nueva evidencia del proveedor/dispositivo.
- Seis regresiones nuevas aprobadas, incluidas restauración del diagnóstico y reintento incremental READ+COMPOSE sin añadir SEND. Capturas nativas del componente revisadas en inglés/claro y español/oscuro a 320 dp y 200% de texto; no son consentimiento real ni validación física.

### 5. UX20: autenticación y capacidades de GitHub

Estado: completado en código, revisión independiente y validación de host para preparación v39 / 1.2.32-UX20. Suites finales: 1600 Full, 1378 Play, 17 runtime y nueve JavaScript, sin fallos, errores ni omitidas. Se incorporan 127 regresiones comunes de GitHub/MCP y seis de diagnóstico Google en Full. Se continúa con UX21 sin esperar una prueba manual; los límites externos permanecen explícitos.

- Fuente congelada: 756 archivos de app sin cambios durante pruebas, lint y compilación. Play requirió repetir la suite completa, sin modificar código ni aserciones, tras un fallo aislado de visibilidad inicial en una prueba heredada de imagen asíncrona; la repetición completa aprobó.
- Lint fresco sin incidencias nuevas: 46 errores y 275 advertencias heredados en Full, 37 y 273 en Play; runtime conserva cuatro advertencias. Lint no se presenta como libre de errores.
- Ambos APK de desarrollo sin firma conservan paquete `com.jarvys.agent`, nombre Jarvys y versión 39; CRC, alineación de 16 KiB, permisos sin cambios y contenido de la fábrica/skill idéntico a v38. Revisión independiente de los APK reales aprobada; preparados para firmar con la identidad original y entregar conforme al modo vigente.

- Corregida la carrera del intento de inicio de sesión: recomponer, cancelar, volver del navegador o recrear la Activity no permite que un callback antiguo guarde credenciales ni cancele un intento nuevo.
- Metadatos OAuth completos y cifrados, caducidad de refresh, identidad de autorización y scopes requeridos visibles. Repo y workflow se solicitan solo mediante selección explícita; reconectar no repite acciones pendientes.
- Contrato de merge contrastado con el servidor oficial: se exige `expectedHeadSha` anunciado por su esquema. `push_files` y `delete_file` se sustituyen por el commit nativo con SHA esperado; no se envían parámetros que el servidor ignoraría.
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

Estado: completado en código, revisión independiente y validación de host para preparación v40 / 1.2.33-UX21. La pasada final sobre fuente idéntica aprueba 1643 Full, 1416 Play, 17 runtime y nueve JavaScript, sin fallos, errores ni omitidas; XML frescos. Lint no añade errores ni avisos: quedan 46/37 errores y 274/272 avisos heredados en Full/Play. Ambos APK originales sin firma están verificados para firma externa con la identidad existente y entrega; se continúa con UX22.

- Inspeccionada la captura real de Calendar: el valor de lectura y el selector editable usaban tipografía, anchura y rellenos diferentes. Se unifican en una fila de presentación con columna derecha estable y espacio de flecha reservado también para valores informativos.
- Aplicado a permisos nativos, scopes y operaciones de Gmail/Drive, herramientas del catálogo incluido GitHub, herramientas MCP personalizadas y la política inicial del editor MCP. Los interruptores de Ajustes y permisos avanzados ya conservaban su columna derecha centrada y no se rediseñan.
- Etiquetas y descripciones conservan su espacio flexible; los permisos largos se ajustan a varias líneas, los controles mantienen un mínimo de 48 dp y los colores proceden del tema existente. No cambian los valores, opciones, callbacks, solicitudes OAuth, gates de escritura ni permisos por defecto.
- La revisión nativa detectó recorte de una letra por la forma de píldora al 200 % de texto. Una regresión de píxeles reproduce la pérdida frente al mismo texto informativo; se limita el radio del selector para conservar los glifos completos.
- La prueba de Gmail reprodujo un fallo heredado: al elegir Denegar, el valor se guardaba pero la pantalla seguía mostrando Preguntar. Se observa ahora la revisión de políticas para actualizar inmediatamente la etiqueta, sin modificar el almacenamiento ni las autorizaciones.
- Aprobadas 43 nuevas regresiones (81 ejecuciones Full/Play), incluida una matriz de 24 configuraciones: 320/393/800 dp, inglés/español, claro/oscuro y fuente 100/200 %. Se verifican borde de los glifos, columna y centro de la fila, texto sin recorte, objetivos de 48 dp, clics únicos, restricciones de permisos, búsqueda/editor MCP reales y cierre/reapertura del menú. Las 345 capturas frescas corresponden a ventanas Activity/Popup nativas de Robolectric; no certifican teléfono físico, TalkBack ni teclado real.
- La regresión nativa de píxeles reproduce 27 píxeles perdidos por la forma anterior y confirma cero tras la corrección. Los resultados previos y la corrección de estilo de Compose se conservaron; todas las suites, lint y artefactos se repitieron sobre la fuente final.
- APK Full y Play con paquete com.jarvys.agent, versión 40 / 1.2.33-UX21, nombre Jarvys, permisos sin cambios, CRC y alineación de 16 KB válidos. Reempaquetados con zipalign para eliminar espacio ZIP incremental sin modificar ninguna entrada; activos de la fábrica idénticos a v39. Son unsigned y debuggable hasta su firma externa. No se han concedido permisos reales de cuentas ni se afirma validación física.

- Inspeccionar todas las vistas de conectores.
- Fijar los controles de permisos a la derecha, en una misma columna vertical y centrados respecto de su fila.
- Validar etiquetas largas, pantallas estrechas, tamaños grandes de texto y español/inglés, conservando áreas táctiles accesibles.

### 7. UX22: fluidez y gestos del preview web

Estado: completado en código, revisión independiente y validación de host para preparación v41 / 1.2.34-UX22. Las suites finales aprueban con 1686 pruebas Full, 1459 Play, 17 runtime y nueve JavaScript; cero fallos, errores u omitidas. Los 773 archivos congelados permanecen idénticos durante la validación. Los APK Full y Play están verificados sin firma para la identidad original y entrega de esta etapa; no se afirma aceptación física.

- Confirmadas dos causas: el gesto global del panel competía con el WebView y una recomposición podía recargar index.html después de navegar. El panel deja de capturar gestos únicamente en las rutas de preview; el WebView conserva su flujo táctil nativo y libera la exclusión al terminar, cancelar o rechazar el gesto. No se sintetizan desplazamientos ni clics ni se cambia la aceleración por hardware.
- La URL inicial se carga una vez. Historial, URL y posición se conservan en un estado nativo acotado para cuatro previews, con un máximo de 48 KiB de historial por página y fallback de URL/scroll. Se pausa, restaura y libera la instancia con su ciclo de vida. No se promete conservar el heap JavaScript ni formularios arbitrarios después de morir el proceso.
- Los adjuntos HTML válidos muestran **Ver en Jarvys**, Descargar y Compartir. La página y sus recursos locales capturados son independientes de cambios, renombrados o borrados posteriores del proyecto. Cambiar solo una dependencia produce otro artefacto; los anteriores permanecen intactos. Los HTML antiguos pueden abrir su original inmutable con aviso de que sus recursos vinculados no se guardaron. APK y binarios con nombre HTML no se previsualizan como páginas.
- Captura por referencias estáticas, sin enumerar el repositorio: hasta 128 archivos, 1 MiB por archivo, 8 MiB totales, 2048 referencias y profundidad 16. CSS, JavaScript, módulos, imágenes y fuentes mantienen rutas relativas autorizadas; faltantes, recursos dinámicos y límites se comunican. preview_workspace sin argumentos conserva el index.html ordinario; con ruta explícita admite el proyecto de Coding mediante el mismo contrato de instantánea y entrega.
- Cada preview usa un origen HTTPS independiente y miembros exactos del manifiesto con hashes. Se rechazan rutas privadas, cruces de chat, symlinks y escapes codificados. No hay acceso file/content, puente Android, nuevas concesiones, instalación, ejecución de APK ni subida externa. Las descargas siguen guardando el archivo original en Downloads.
- Se conserva JavaScript interactivo y se restringen cargas URL, navegación externa, formularios, frames y workers. No se promete aislamiento absoluto de red: el posible egreso WebRTC heredado queda registrado para endurecimiento posterior y comprobación en Chromium real. Los previews de fuentes de la fábrica no reciben las capacidades nativas de sus APK.
- Tarjetas y tokens se recuperan desde sus registros duraderos sin reejecutar herramientas. También se reconstruye el token válido desde un resultado de herramienta persistido si falta la presentación UI. Un archivo explícito no disponible, resultado incompleto o token ajeno nunca se sustituye por otro index.html.
- Añadidas 43 regresiones; el subconjunto dirigido incluido en la agregada contiene 59 casos por sabor. Comprueba el recorrido Activity/AndroidView real, gestos verticales/diagonales/horizontales, cancelación, multitouch, retorno al chat, rotación, serialización de estado, identidad y bytes del artefacto. La prueba de sensibilidad reproduce ambas conductas antiguas y restaura después la fuente exacta. Las ocho capturas nativas EN/ES, claro/oscuro, 320 dp y texto al 200 % muestran todos los glifos y acciones sin recortes.
- La prueba P0 antigua suponía creación síncrona del WebView; ahora espera de forma acotada a la validación en IO y conserva todas sus aserciones. Las suites completas se repitieron sobre la corrección. La delegación de accesibilidad se comprueba exactamente una vez; se documenta y suprime solo el falso positivo de lint que pedía fabricar un clic desde el wrapper táctil.
- Lint regenerado sin errores ni avisos nuevos: permanecen 46 errores / 274 avisos Full y 37 / 272 Play. Runtime: cero errores y cuatro avisos heredados. Ambos APK conservan com.jarvys.agent, Jarvys, versión 41 / 1.2.34-UX22 y permisos idénticos a v40; CRC, ausencia de firma y alineación de 16 KB verificados. Reempaquetado con entradas byte a byte iguales a Gradle y activos de fábrica idénticos a v40. Los 19 archivos legacy privados permanecen intactos y excluidos del respaldo.
- Límite real: Robolectric no ejecuta Chromium. Scroll/fling/pinch efectivos, rendimiento, TalkBack, teclado y comportamiento de red aún necesitan aceptación física; el host no se presenta como esa comprobación. Se continúa con UX24 sin esperar feedback manual.

Diseño, límites y aceptación física: app/FILE_DELIVERY.md y app/WEB_PREVIEW_ACCEPTANCE.md. Los informes detallados, capturas y APK se conservan fuera del respaldo de código. Firma de la entrega con la clave original aprobada, sin generar otra identidad.

- Aislar la causa del desplazamiento por saltos antes de aplicar cambios.
- Mejorar scroll, fling y coordinación de gestos anidados del WebView, respetando quién controla cada gesto.
- Evitar que los desplazamientos verticales o hacia arriba dentro del preview abran el panel lateral de la app.
- Conservar navegación legítima, enlaces, zoom e interacción con el teclado.
- Añadir **Ver en Jarvys** al adjunto de una página HTML generada, junto a Descargar y Compartir. Distinguir una página HTML de un archivo APK; abrir la página dentro de la app mediante el preview seguro.
- Servir CSS, JavaScript, imágenes y rutas relativas únicamente desde los recursos autorizados de esa página y conservar la apertura tras reabrir el chat, sin acceso arbitrario a archivos privados ni puentes WebView inseguros.
- Validar rendimiento y gestos en dispositivo físico; las pruebas Robolectric no bastan para demostrar fluidez real.

### 8. UX24: coherencia visual de Bots

Estado: completado en código, revisión independiente y validación de host para preparación v42 / 1.2.35-UX24. Ambos APK sin firma quedan verificados para firma con la identidad original y entrega; la aceptación física sigue pendiente.

- Un único barrido y destellos abarcan el icono y el nombre completo, incluidos nombres multilínea. Se conservan las dos columnas, la cabecera de Ajustes y la ausencia de contenedores externos.
- Cuadrícula, tarjetas de misión, lista/detalle de bots, mensajes y atribución de aprobaciones comparten iconos escalables: terminal para Coding, teléfono para Android-use y el PNG privado generado del bot personalizado. La identidad se resuelve por el ID estable del perfil, nunca por el nombre, color o ID temporal de ejecución.
- Las actualizaciones del icono se observan en vivo y al reabrir una misión persistida; no se congela una referencia antigua que la generación siguiente pueda retirar. Los resultados de carga antiguos no sustituyen revisiones más recientes ni muestran imágenes de otro bot.
- Una vez resuelta la referencia del icono, su decodificación muestra un marcador neutro hasta terminar; imágenes ausentes, inválidas o ilegibles usan el fallback compartido. La lectura inicial de metadatos puede mostrar brevemente el icono genérico. Se conservan límites de tamaño y lectura exclusivamente en el almacén privado correspondiente al bot.
- El efecto se activa solo en ejecución real, con movimiento reducido, visibilidad y ciclo de vida respetados. La iluminación conserva contraste de texto e iconos en ambos temas. No cambia permisos, perfiles de ejecución, aprobación ni inmutabilidad de plantillas.
- **Validación final:** 1701 Full, 1474 Play, 17 runtime y nueve JavaScript aprobadas, sin fallos, errores ni omitidas. Las 15 regresiones nuevas y 84 casos dirigidos por sabor están incluidos en las agregadas. Se verifican igualdad exacta de píxeles de iconos a 28/42/72 dp, cambios y reapertura JSON, nombres duplicados, carga/fallback, atribución/detalle/debate, estados de ejecución, alcance visual y contraste.
- 178 capturas nativas frescas archivadas en total. Los pares de movimiento cambian independientemente el icono y el nombre; los estados inactivos y de movimiento reducido permanecen estáticos. La matriz español/inglés, claro/oscuro y texto al 200% conserva dos columnas sin recortes. Los PNG generados son fixtures sintéticos persistidos; no se atribuye una llamada real al proveedor ni aceptación física.
- Fuente congelada durante pruebas, lint y compilación. La sesión inicial de validación se interrumpió durante la compilación de Play; Full ya había terminado con XML frescos. Se conservaron esos resultados y solo se repitió la etapa pendiente de Play/runtime, comprobando de nuevo todos los hashes de fuente.
- Lint regenerado sin incidencias nuevas: persisten 46 errores y 274 avisos Full, 37 errores y 272 avisos Play; runtime sin errores y cuatro avisos heredados. Ambos APK son unsigned y debuggable, con paquete `com.jarvys.agent`, nombre Jarvys, versión 42, CRC y alineación verificados, permisos sin cambios y assets de fábrica idénticos a v41.
- Revisión independiente de fuente, resultados frescos, capturas y artefactos aprobada. Los 19 archivos legacy privados siguen intactos y excluidos de las publicaciones. No se declara rendimiento, TalkBack ni instalación/actualización física comprobados.

### 9. UX25: prompt de producción y harness de Coding

Estado: completado en código, revisión independiente y validación de host para preparación v43 / 1.2.36-UX25. Las suites finales aprueban 1725 Full, 1492 Play, 17 runtime y nueve JavaScript, sin fallos, errores ni omitidas. Los dos APK originales sin firma están verificados para firma con la identidad existente y entrega. No se afirma una evaluación con modelo/proveedor real ni aceptación física.

- Integrado un contrato original en inglés, exclusivo del perfil inmutable Coding v3. Define investigación del repositorio, planificación proporcional, implementación completa, pruebas reales, revisión, preservación de cambios ajenos, seguridad, recuperación causal y entregas con evidencia. El principal conserva únicamente la descripción breve y el contrato de delegación; los skills siguen cargándose cuando corresponden.
- El prompt tiene 15.316 caracteres, por debajo del límite adoptado de 16.000. Esa cifra no es un recuento de tokens del proveedor ni una garantía de contexto para cualquier modelo. No se afirma superioridad de producción por longitud ni por respuestas de un modelo simulado.
- Restaurada la selección efectiva de estado, ejecución y jobs para Coding integrado en Full mediante un resolver común de spawn, revalidación y resume. El backend Full ya existía para perfiles configurados; no fue eliminado ni reconstruido. Cada comando mantiene aprobación propia, aunque el principal esté en Allow, con revalidación de política, perfil, ámbito y cancelación. No se conceden permisos persistentes ni se amplía la delegación genérica. Play permanece sin backend Linux.
- Añadido `mission_access=read_only` explícito para misiones con proyecto: lista permitida de lecturas, ámbito READ, sin mutaciones del proyecto/tablero, comandos, fábrica ni delegación. Los mensajes solo pueden ir al capitán, evitando reactivar otro bot para actuar. La restricción se guarda, se recupera y permanece en seguimientos y reanudaciones; un modo explícito vacío, nulo o inválido se rechaza. La clasificación del lenguaje natural sigue siendo responsabilidad del agente: omitir el parámetro conserva el modo estándar por compatibilidad y no sustituye la autorización del usuario.
- Comandos con timeout de 900 segundos por defecto, hasta 3600 cuando se indica explícitamente; la aprobación muestra el límite real. Los recibos incluyen vista previa acotada del comando redactado, truncamiento, tamaño, estado, salida y evidencia del log. Un comando largo no consume el espacio necesario para leer o esperar el job. Se conservan cierre, lease, STOP y ausencia de replay de efectos inciertos.
- El default v2 exactamente original migra a v3 sin duplicado; configuraciones personalizadas se conservan. Un checkpoint de otra versión no se relabela ni reinicia automáticamente; una misión existente no gana herramientas por reanudar. Eliminar read_skill también elimina las selecciones de skills que ya no podrían cargarse.
- Añadidas 25 regresiones deterministas de ensamblado real, permisos, aislamiento, modos, migración, recuperación, timeout y recibos. Las 59 pruebas dirigidas Full y 53 Play forman parte de las agregadas finales. Los procesos/proveedores externos se simulan deliberadamente; esas pruebas no miden la calidad de ingeniería de un modelo real.
- Las primeras agregadas expusieron lecturas de geometría durante la resolución asíncrona de adjuntos en dos pruebas anteriores. El fixture copiaba bytes sin registrar su propiedad en la conversación. Se persiste ahora el adjunto real y se espera de forma acotada su botón disponible antes de medir. No cambia la UI ni se relajan aserciones. Pasan las 19 pruebas de reacciones por sabor y después se repiten ambas agregadas completas sobre la fuente final; los fallos previos quedan conservados como evidencia.
- Fuente congelada: 782 archivos idénticos durante pruebas, lint y compilación. Lint regenerado sin errores ni avisos nuevos: 46 errores / 274 avisos Full y 37 / 271 Play; runtime sin errores y cuatro avisos heredados. Eliminados los recursos obsoletos de «sin tiempo máximo», sin suprimir diagnósticos.
- APK Full y Play con paquete com.jarvys.agent, nombre Jarvys y versión 43 / 1.2.36-UX25; unsigned y debuggable hasta la firma externa. Permisos idénticos a v42, CRC y alineación de 16 KB válidos, activos de fábrica byte a byte iguales a v42. Reempaquetado sin modificar ninguna entrada. Los 19 archivos legacy privados siguen intactos y excluidos del respaldo.
- Quedan explícitas las limitaciones: las 18 evaluaciones emparejadas con modelo real no se han ejecutado; report_done sigue siendo texto libre y no certifica semánticamente pruebas ni vincula todos los checks a hashes finales. No hay resolución automática de todos los AGENTS.md ni aceptación Android/ARM64, instalación o actualización física. Las instrucciones no se presentan como implementación de esas protecciones.
- Revisada la colección pública solicitada, fijada por commit y con licencia declarada GPL-3.0, sin atribuir autenticidad o derechos comprobados a cada prompt. Contraste adicional con OpenCode oficial MIT. Solo se integra síntesis original; descargas, informes, capturas, credenciales e historial privado quedan fuera de las publicaciones.

Contrato, referencias y límites: app/CODING_AGENT.md. Código y Pending.md publicados directamente en master, conservando historial y comprobando los objetos remotos. Se continúa con UX26 tras la firma y entrega de esta etapa.

### 10. UX26: contrato del agente principal

Estado: completado en código, revisión independiente y validación de host para preparación v44 / 1.2.37-UX26. Las suites finales aprueban 1784 Full, 1551 Play, 17 runtime y nueve JavaScript, sin fallos, errores ni omitidas. Los APK Full y Play sin firma quedan verificados para firma con la identidad existente y entrega; no se afirma aceptación física ni evaluación de calidad con proveedor real.

- Sustituido el núcleo de cuatro frases por un contrato original del principal en inglés, de 6136 caracteres. Prioriza el idioma de la petición actual, con fallback de la configuración real de la app, también al normalizar el contexto en Android anterior a API 33. Esa cifra cuenta caracteres, no tokens ni calidad del modelo.
- El contrato define comprensión de intención, comunicación concisa, planificación proporcional, evidencia, diagnóstico causal, continuidad, privacidad y finalización honesta. Las secciones operativas se ajustan al registro final de herramientas del turno; los skills se cargan cuando hacen falta mediante read_skill. Los catálogos no introducen cuerpos completos de bots ni el prompt de Coding en el principal. Se conservan create_bot, su revisión e idempotencia, la entrega nativa de archivos y el preview ordinario/de proyecto de UX22.
- La delegación genérica conserva COMPLETED/PARTIAL/STOPPED/FAILED/UNKNOWN en un recibo acotado. COMPLETED solo indica que terminó el turno del bot; task_verified permanece falso. Un timeout, un bloqueo o texto de un adaptador antiguo no se convierten en éxito certificado. El principal conserva la responsabilidad de verificar y entregar.
- El texto natural junto a llamadas de herramientas aparece como progreso visible después de guardar la intención y antes del efecto. Se reconstruye desde el mismo registro duradero, con identidad estable y sin duplicar el mensaje en el contexto, alterar sus índices o activar la finalización/reflexión. No se muestran canales privados de razonamiento ni comentarios internos de otros bots.
- Añadido **Detener y enviar este mensaje** para texto en el mismo chat ordinario activo, manteniendo un botón Stop independiente. Primero se guarda una entrada pendiente, se cancela la generación anterior y sus aprobaciones, se espera el cierre del worker y se inicia otro turno con su historial y contexto de autorización nuevos. No es inyección en caliente ni rollback; un efecto anterior puede haberse producido y requiere comprobar su recibo/estado.
- Las entradas sin iniciar permanecen visibles y seleccionables para reenviarlas manualmente; nunca se ejecutan automáticamente tras reiniciar. Sus IDs impiden repetir el despacho. Se conservan las restricciones de adjuntos, chats gestionados, compactación y reflexión. El límite de la nueva entrada es 32000 caracteres; una operación nativa bloqueada puede retrasar el cierre previo.
- Los callbacks de progreso, compactación y finalización quedan ligados al chat y la generación correspondientes. Volver al chat recupera su estado vivo y las aprobaciones pendientes; los IDs numéricos de presentación se reconcilian para evitar colisiones con el historial. Otro chat no puede sustituir por accidente el trabajo activo. Los envíos rechazados conservan una explicación duradera en vez de perderse silenciosamente.
- Las reservas de ejecución en segundo plano se contabilizan por generación: el cierre de un run antiguo no libera al siguiente, y una reserva cancelada antes de iniciar o rechazada por el executor se retira correctamente. También se libera una ejecución que falla antes de inicializar el runtime. Detenido y Fallido permanecen diferenciados en UI y persistencia.
- Añadidas 59 regresiones deterministas por sabor, incluidas las rutas reales de loop, ledger, service, aprobación y UI con proveedores/herramientas simulados. Comprueban orden de checkpoints, no replay, estados parciales, interrupción, envíos rápidos, recarga, cambio de chat, fallo de arranque y denegación de una aprobación antigua. Las 20 capturas nativas finales cubren EN/ES, claro/oscuro y texto 100/200 %, con progreso, mensaje pendiente y controles legibles. No certifican teclado, TalkBack ni teléfono.
- Las primeras agregadas e intentos interrumpidos se conservaron y no se usan como evidencia final. Se corrigió la liberación de reservas expuesta por el fixture de ejecución; una aserción antigua de imágenes espera ahora su acción disponible tras la decodificación asíncrona antes de comprobar visibilidad, conservando todas sus aserciones. Después se repitieron ambas agregadas completas sobre exactamente la misma fuente.
- Fuente congelada: 787 archivos idénticos durante pruebas, lint y compilación. Lint regenerado sin errores ni avisos nuevos: persisten 46 errores / 274 avisos Full y 37 / 271 Play; runtime sin errores y cuatro avisos heredados. Los 19 archivos legacy privados permanecen intactos y excluidos del respaldo.
- APK Full y Play con paquete com.jarvys.agent, nombre Jarvys, versión 44 / 1.2.37-UX26; unsigned y debuggable hasta la firma externa. Permisos sin cambios, CRC y alineación de 16 KB verificados, activos de fábrica idénticos byte a byte a v43. El reempaquetado no altera ninguna entrada de Gradle.
- Contrastadas las referencias oficiales de OpenClaw fijadas por commit y con licencia MIT; solo se integra síntesis original adaptada a los contratos de Jarvys, sin importar su gateway, permisos ni autenticación. Las 24 evaluaciones de comportamiento con modelo real siguen sin ejecutarse. No se implementa un certificador universal de finalización semántica ni una reforma general de errores tipados de conectores.

Contrato, referencias y límites: app/MAIN_AGENT.md. Código y Pending.md respaldados directamente en master, sin sobrescribir historial. Se continúa con UX27 después de la firma y entrega de esta corrección.

### 11. UX27: títulos breves y detalle de tareas

Estado: completado en código, revisión independiente y validación de host para preparación v45 / 1.2.38-UX27. Las agregadas finales aprueban 1846 Full, 1613 Play, 17 runtime y nueve JavaScript, sin fallos, errores ni omitidas. Los APK Full y Play sin firma quedan verificados para firma con la identidad original y entrega; no se afirma aceptación física ni evaluación de calidad de nombres con un proveedor real.

- El agente genera task_title en la misma llamada crew_spawn: un nombre semántico de la misión completa, normalmente de tres a seis palabras en el idioma del usuario. No hay frases por tipo, detectores de palabras clave, resumen por prefijos ni llamadas extra para generar el título. La app solo valida presentación y un máximo de 60 puntos de código Unicode.
- El primer nombre válido queda estable para todos los bots y reintentos. Espacios se normalizan, Unicode válido se conserva y títulos vacíos, invisibles o demasiado largos usan una etiqueta neutral localizada. Un fallback puede recibir después su primer nombre válido; un título ya aceptado no se sustituye.
- El esquema 2 separa title, titleSource y originalInstructions y lee también el esquema 1 sin reescribir el historial. Conserva íntegros la petición original, las misiones de bots, sus mensajes y las instrucciones de ejecución. Los checkpoints antiguos sin petición original no inventan esa procedencia a partir de la misión del bot.
- La recuperación reconcilia metadatos válidos de checkpoints cuando el ledger todavía tenía fallback, preservando títulos y peticiones ya conocidos. La publicación ordenada evita que un callback antiguo sobrescriba un título aceptado después, sin esperar sobre bloqueos de los workers. Identidades de conversación/misión/bot siguen verificadas.
- Tarjeta, resumen terminado, notificación y cabecera de detalle comparten el título acotado. El interior prioriza la misión y el estado con el estilo azul de Jarvys; elimina el recorte de cabecera a un tercio de pantalla y traslada opciones globales/Coding a un diálogo secundario. Las instrucciones completas se abren en una vista desplazable y seleccionable.
- Pestañas legibles a texto grande, contador de ancho acotado, actividad compacta con emisor y destinatario conservados y controles con objetivos de 48 dp. Los tres diálogos usan el componente común de desplazamiento con cuerpo acotado, indicaciones de borde y acciones separadas. Se conservan navegación, referencias, permisos, el perfil Coding v3 y el ciclo de vida de UX26.
- Añadidas 62 regresiones por sabor: 23 de contrato, Unicode, persistencia, concurrencia, recuperación y flujo real de herramientas con proveedores simulados, y 39 de presentación/interacción. Las 120 capturas nativas finales cubren tarjetas y detalle a 320/360/412 dp, inglés/español, claro/oscuro y texto 100/200 %, además de ventanas cortas, diálogos, selección/copia íntegra y controles. La escala 2× se comprueba también en las ventanas de diálogo reales del host.
- La primera agregada detectó exclusivamente que los nuevos diálogos no usaban el helper común exigido por el proyecto. Se corrigió esa integración, se aprobaron las 40 pruebas dirigidas de diálogo y contrato de fuente y después se repitieron todas las agregadas sobre exactamente la fuente final. Los intentos anteriores y ajustes de fixtures se conservan como evidencia separada; no sustituyen los resultados finales.
- Fuente congelada: 794 archivos idénticos durante pruebas, lint y compilación. Lint regenerado sin errores ni avisos nuevos: persisten 46 errores / 274 avisos Full y 37 / 271 Play, con runtime sin errores y cuatro avisos heredados. Los 19 archivos legacy privados permanecen intactos y excluidos del respaldo.
- APK con paquete com.jarvys.agent, nombre Jarvys, versión 45 / 1.2.38-UX27, unsigned y debuggable hasta la firma externa. CRC y alineación de 16 KB comprobados, permisos sin cambios y activos de fábrica idénticos a v44. El reempaquetado no modifica ninguna entrada original de Gradle. La revisión independiente comprobó fuente, XML frescos, capturas y ambos APK reales.

Contrato y límites: app/CREW_TASKS.md. Código y Pending.md respaldados directamente en master, preservando historial. Se continúa con UX28 después de la firma y entrega de esta etapa.

### 12. UX28: miniaturas HTML y acciones de archivos

Estado: pendiente; solicitud del 8 de octubre de 2026, añadida a la cola después de UX27.

- Mostrar una miniatura real del contenido HTML entregado y abrir el preview al tocarla; no sustituirla por una imagen genérica o un diseño inventado.
- Eliminar el botón textual «View in Jarvys» de esa tarjeta y reorganizar sus acciones inferiores como iconos claros, con etiquetas de accesibilidad y objetivos táctiles adecuados.
- Mantener la apertura, identidad, propiedad y persistencia del archivo, sus referencias y los límites de seguridad del preview; no ampliar permisos ni acceso a archivos privados.
- Ajustar composición, jerarquía y espaciado a las dos capturas de referencia aportadas, dentro del estilo azul de Jarvys.
- Validar HTML ordinario y de proyecto, miniaturas en carga o fallidas, apertura al tocar, acciones, reapertura del chat, pantallas estrechas, texto grande, temas e idiomas. Una miniatura o prueba de host no certifica por sí sola interacción o rendimiento en dispositivo físico.

### 13. UX29: indicador nativo de puntos iluminados

Estado: pendiente; solicitud del 8 de octubre de 2026, añadida a la cola después de UX28.

- Sustituir únicamente el glifo de AgentPresenceIndicator por una implementación original en Canvas de Compose inspirada en la referencia visual https://reactbits.dev/c/micro/lattice-loader.
- Usar una cuadrícula fija de 3 × 3 puntos: ocho puntos exteriores se iluminan en secuencia y el punto central permanece tenue. Tomar como orientación visual puntos de unos 6 dp, separación de 2 dp, opacidad tenue cercana a 0,15 y ciclo de unos 864 ms, ajustándolo al componente nativo existente.
- Conservar el texto actual de «Pensando», sus colores y estados. No incorporar React, WebView ni un cronómetro para esta tarea.
- Respetar movimiento reducido con una representación estática y pausar la animación cuando no sea visible o su ciclo de vida no esté activo.
- Crear el dibujo y la animación de forma independiente; no copiar código de la referencia sujeto a MIT más Commons Clause.
- Validar integración, estados, temas, texto grande, contraste, movimiento reducido y visibilidad. Esta tarea se implementará por separado después de UX28, sin ampliar UX27.

### 14. UX30: densidad y claridad del menú lateral

Estado: pendiente; solicitud del 8 de octubre de 2026, añadida a la cola después de UX29.

- Reducir el espaciado vertical entre Nuevo chat, Bots y Tareas programadas, manteniendo objetivos táctiles accesibles y la jerarquía del menú lateral.
- Quitar el texto «Pinned» redundante dentro de las filas de chats anclados; conservar el encabezado de sección salvo que la revisión de la captura confirme otra necesidad.
- Presentar el menú de acciones de cada chat con tres puntos verticales.
- Ocultar detalles o secciones vacías de chats, incluida la sección de anclados cuando no contenga elementos, sin perder acceso a los chats existentes.
- Contrastar la composición con la captura aportada y validar estado vacío/con chats, chats anclados, acciones, navegación, temas, idiomas y texto grande.
- Implementar como tarea separada después de UX29; no ampliar el alcance de UX27 ni cambiar permisos o comportamiento de las conversaciones.

## Validaciones que siguen abiertas

- Preview web: endurecer y comprobar en Chromium real el posible egreso WebRTC heredado. Las restricciones de URL/CSP no equivalen a aislamiento absoluto de red; conservar el contrato HTML/CSS/JavaScript interactivo sin puentes privados ni permisos nuevos.

- Comprobar en dispositivo la recuperación de proyectos/archivos, imágenes y gestos, selección/copia, ejecución real y flujos de autenticación. Las pruebas del host no equivalen a una pasada completa en teléfono.
- Continuar el diagnóstico del error DNS en el intercambio OAuth del login de navegador. No hay una regresión demostrada frente a v28; el flujo de código de dispositivo sigue siendo una alternativa disponible.
- Android-use está limitado a conectores nativos del dispositivo registrados. No incorpora todavía el puente de accesibilidad heredado ni herramientas ADB/Python.
- La recuperación reanudable de misiones corresponde a perfiles versionados con proyecto de conversación; las misiones legacy no deben relanzarse automáticamente tras reiniciar.
- Mantener visible la deuda de lint heredada y evitar errores nuevos. Indicar el tipo de compilación y las comprobaciones realizadas en cada entrega.

## Entregas y respaldo

- Usar el paquete original `com.jarvys.agent` y el nombre **Jarvys** para las entregas normales.
- Compilar sin firma durante el desarrollo; al completar cada pendiente o corrección, firmar y entregar el APK validado con la clave aprobada existente. No generar otra clave como sustitución automática. Esta regla vigente sustituye el plan histórico de esperar al final de toda la cola.
- Publicar progresivamente el código y este **Pending.md**, y verificar el resultado remoto antes de afirmar que quedó respaldado.
- Mantener en este archivo requisitos y estado del producto, sin conversaciones privadas, credenciales ni datos de usuarios.
