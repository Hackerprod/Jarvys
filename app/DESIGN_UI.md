# Jarvys UI — especificación original para X1

## Intención de producto

**Persona:** alguien que lleva Jarvys en su teléfono y alterna entre conversar, observar una acción del dispositivo y revisar tareas preparadas. Abre la app con una intención concreta: escribir/pedir ayuda, entender qué hizo el agente o ajustar una capacidad conectada.

**Acción principal:** formular una intención, seguir su rastro hasta la respuesta/acción y decidir el siguiente paso sin perder el contexto de la conversación.

**Sensación:** una mesa de trabajo de campo, tranquila y fiable: señales legibles, procedencia visible y controles discretos. Debe sentirse como Jarvys en Android, no como una consola web ni como una copia visual de ChatGPT.

**Territorio del producto (conceptos):** intención, contexto de dispositivo, conversación activa, procedencia, permisos/conectores, ejecuciones, cadencia temporal, historial verificable, capacidades/skills y decisión humana.

**Mundo cromático:** tinta de pizarra para el trabajo nocturno; papel mineral para lectura prolongada; vidrio de botella para la identidad; cobre oxidado para una acción disponible; latón mate para tiempo/atención; arcilla roja para errores/destrucción. El color comunica estado y procedencia; no es decoración.

**Firma:** el **rastro de intención**: un marcador lineal propio que enlaza una solicitud con eventos, una respuesta y, cuando aplica, su siguiente ejecución. Aparece en el encabezado de conversación, en eventos operativos y en el historial de tareas. No altera ni simplifica los datos de run existentes.

**Defaults que se rechazan y sustitución:**

1. AppBar genérico encima de pantallas desconectadas → una cabecera de conversación con contexto/sesión y transición de lugar coherente con el resto del app.
2. Todo convertido en tarjetas grandes con icono-izquierda → estructura editorial por secciones; las tarjetas se reservan para avisos, decisiones y superficies que de verdad se elevan.
3. Acento violeta/Material You aplicado a todo → vidrio/verdigrís como identidad, latón solo para referencias temporales y terracota solo para error/acción destructiva.

## Sistema visual

### Tokens de color propios

Todos los colores de componentes salen de estos tokens, mapeados a `ColorScheme` claro/oscuro en `JarvysUiKit.kt`:

| Token | Claro | Oscuro | Uso |
|---|---|---|---|
| `JarvysCanvas` | `#F3F0E7` | `#101A1D` | fondo de lectura |
| `JarvysSurface` | `#FFFCF5` | `#182528` | superficie base, diálogo |
| `JarvysRaised` | `#E8EEE8` | `#203236` | menú, superficie elevada, input inset |
| `JarvysInk` | `#18312F` | `#E5EFEB` | texto principal |
| `JarvysSecondaryInk` | `#536563` | `#AABBB7` | soporte/metadata |
| `JarvysGlass` | `#276F68` | `#7BC3B2` | acción principal/foco |
| `JarvysBrass` | `#916319` | `#E0B75F` | hora, ejecución próxima, atención |
| `JarvysClay` | `#A83F37` | `#F09889` | error y acción destructiva |
| `JarvysRule` | `#D1D8D1` | `#34474A` | separador, límite de superficie |

La jerarquía de texto tiene cuatro niveles: ink, secondary, tertiary y muted; la misma escala mantiene contraste en modo oscuro. No se añaden gradientes ni sombras dramáticas. Las elevaciones se comunican principalmente con un cambio corto de luminosidad y un borde tenue.

### Tipografía, forma, espaciado y movimiento

- Fuente del sistema Android (`SansSerif`), sin fuentes nuevas. Título de lugar: 21sp/semibold; encabezado de sección: 15sp/semibold; texto largo: 16sp con interlineado cómodo; metadata y estados: 12–13sp con peso medio. El tamaño no será el único diferenciador jerárquico.
- Base de espaciado: 6dp; escala `6/12/18/24/30`. Alineaciones asimétricas solo cuando indican jerarquía, por ejemplo, el rastro de intención en el borde del transcript.
- Radios: 8dp para control compacto, 14dp para superficie de trabajo, 20dp para diálogo/hoja. Controles táctiles mantienen al menos 48dp.
- `JarvysMotion` concentra curvas y duraciones de Compose: feedback corto 120ms, expansión 190ms, llegada de mensaje 190ms, cambio de presencia 220ms y cambio de lugar 250ms; las órbitas activas son lentas (ciclos de 1.6–1.8s). Sin librerías de animación nuevas, bounces ni animaciones obligatorias para entender un estado.
- Accessibility: nombre de cada acción, rol correcto, foco visible, estados escritos además de codificados por color, compatibilidad con fontScale 2 y scroll para contenido largo.

## Inventario funcional y expresión nueva

### Chat shell y conversación

**Datos/contratos Jarvys que se conservan:** `goal`/`onGoalChange`, `onSubmitMessage`, `onStopRun`, `AgentRunUiSnapshot`, `historyEvents`, `runHistory`, `selectedHistoryRunId`, `conversationSessionId`, `conversationTitle`, `showingNewChat`, `showAgentEvents`, `proactiveTargetMessageId`, `selectedSkillIds`, `availableSkills`, proveedores/modelo, `CrewMode`, memoria/reflexión, callbacks de traducción, regeneración, voz, aprobaciones/decisiones, contexto y navegación.

**Shell nuevo:** una cabecera de orientación muestra lugar (chat/tarea/ajustes), título de la sesión y estado del run. En chat, el área central es el timeline con el rastro de intención continuo; agrupaciones de eventos no convierten cada mensaje en una tarjeta. Las decisiones de E1 y aprobaciones existentes siguen siendo accionables y mantienen exactamente sus callbacks/IDs. Barra inferior fija para redactar; el botón de envío cambia de estado a detener cuando hay run.

**Conversaciones:** el drawer se convierte en un índice propio de sesiones: crear conversación, filtro inmediato, run activo, historial, selección de sesión y perfil/ajustes. El filtro conserva su estado local; seleccionar una conversación sigue llamando al mecanismo Jarvys de historial y actualiza el `NavController` a `CHAT`. Sin resultados/vacío tienen estados diferenciados.

**Composer:** conserva borrador y envío; presenta modelo y capacidades en un borde de controles compacto y una bandeja de contexto separada. Los botones de enviar/detener y los de modelo mantienen su habilitación de acuerdo al estado de run.

**Acciones de contexto/adjuntos:** bandeja propia agrupada por «Contexto», «Capacidades» y «Adjuntar». Mantiene captura de pantalla contextual, selector de skills de run, importar skill, Crew y sus tres modos. Camera/Photos/Instruction Injection conservan su estado explicativo de no disponible, no simulan una acción. Abrir Settings, MCP, skills, conectores y proveedores sigue navegando a las rutas existentes.

### Ajustes

Índice de control en secciones de texto/filas, no panel de tarjetas clonadas. Conserva Home/Preferences, idioma, tema, modo de eventos, timeout, memoria/reflexión, Proactivo, proveedores, MCP, skills, conectores y accesibilidad. El único punto de X1 en el archivo derivado es el slot condicionado para `TaskSettingsEntry`; la pantalla original del entry vive bajo `tasks/ui` y aparece solo con al menos una tarea viva.

### MCP

**Datos/callbacks a preservar:** `McpServerRepository`, `McpConnectionManager`, `McpOAuthManager`, configuración del endpoint/alias/auth, estado de conexión, info del servidor, esquema/herramientas, enable/disable, políticas read/write, autorizaciones, edición, eliminación y callbacks de navegación.

**Expresión nueva:** índice de endpoints como registro de conexión: una línea de estado/host y conteo, con foco/expansión que muestra su alcance. El detalle organiza conectividad, herramientas de lectura, herramientas de escritura/política y procedencia/metadatos del endpoint en una ficha técnica con fronteras tipográficas. Búsqueda aparece cuando el inventario es suficiente; estados vacío, cargando, error, OAuth y desconectado son explícitos. No se altera transporte, seguridad, alcance de tool ni flujo de autonomía.

### Skills

**Datos/callbacks a preservar:** `SkillRepository.skills`, metadata, origen bundled/imported, validación, habilitar/deshabilitar, borrar solo importadas, búsqueda (cuando el catálogo lo justifica), importación markdown/archivo/GitHub, selector de skills y herramientas permitidas.

**Expresión nueva:** catálogo de capacidades con nombre, resumen y estado en una fila amplia; detalles expandibles muestran ID, etiquetas, herramientas, explicación, validación y cuerpo con scroll legible, sin truncar funcionalidad. Importar ofrece tres fuentes; éxito/error, vacío, filtro sin coincidencia y diálogo destructivo son estados explícitos.

### Tareas programadas — integración ST3

Se conserva intacta la implementación original de `tasks/ui` de ST3: listado, detalle, kill-switch, salud, acciones y deep links. En X1 solo se adapta su superficie/tokens a `JarvysUiKit`, sin cambiar rutas, contratos, historial, lógica de pausa, texto literal ni la conversación `jarvys-tasks`.

## Movimiento y estados

`JarvysMotionPolicy` lee los tres ajustes globales de animación de Android una vez en el provider raíz y comparte `LocalReducedMotion`; no requiere permisos. `rememberMotionEnabled` combina actividad del estado, visibilidad en la ventana y lifecycle iniciado. Las animaciones infinitas solo existen dentro del branch activo/visible; se desmontan al terminar, salir del viewport, detenerse el lifecycle o activar movimiento reducido. En movimiento reducido no hay ciclos ni transiciones de entrada: la geometría, el color y el texto estáticos comunican el estado.

### Presencia y avatares vivos

- `AgentPresence.from(AgentRunUiSnapshot)` proyecta `IDLE`, `THINKING`, `WORKING`, `WAITING_USER`, `ERROR` o `DONE`. El núcleo/anillo de Canvas usa una transición de 220ms entre estados; THINKING hace una órbita de 1.6s, WORKING mantiene dos marcadores lentos, y la espera, el error y el fin son geométricos y legibles sin movimiento. La etiqueta es texto es/en y forma parte del árbol semántico.
- Los bots de Crew usan cabezas poligonales deterministas por rol y tonos de su `colorKey`, con ojos y antena en Canvas. QUEUED/RUNNING/WAITING usan pulsos/orientación de baja frecuencia mientras están activos y visibles; DONE/ERROR/INTERRUPTED son estáticos. `BotVisualState.from(status, waitingReason)` es una proyección pura, separada de `CrewManager`.
- Una sola llegada breve (fade + desplazamiento mínimo, 190ms) se aplica al último evento nuevo de una sesión viva; la restauración del historial y el scroll no vuelven a dispararla. El caret de streaming pulsa a 760ms, se pausa al salir de pantalla y conserva una descripción accesible. El control enviar/detener cambia con feedback de 120ms sin retrasar el toque ni mover su área táctil.

Transiciones de pantalla sutiles y breves; expansión de secciones con el token `JarvysMotion.expand`; foco y cambios de estado también accesibles sin animación. Loading, error recuperable, vacío, filtrado sin coincidencias, desconectado, run activo, éxito y acción destructiva tienen tratamiento explícito. En layouts compactos/densos se priorizan scroll y agrupación antes que reducir tipografía; en fontScale 2, botones y textos pueden crecer/ocupar varias líneas.


### Actualización UX29: presencia de puntos nativa

THINKING y WORKING usan una cuadrícula de 3 × 3 puntos fijos dentro del área original de 34 dp. Cada punto mide 6 dp y la separación es de 2 dp. Una única fase de 864 ms desplaza una onda suave de opacidad por los ocho puntos del perímetro; el centro conserva una tinta tenue. Es un modelo matemático original dibujado con Canvas, sin dependencia de React, WebView, reloj mostrado ni código de terceros.

La etiqueta existente, semántica, colores y cursor de streaming permanecen sin cambios. Los símbolos de reposo, espera, error y fin conservan su geometría. El glifo mide su propia visibilidad; si está recortado fuera de pantalla, el propietario deja STARTED o se activa movimiento reducido, se desmontan tanto el ciclo como la transición entre estados. En reposo de movimiento, el perímetro es uniforme y estático. Los cambios de opacidad se leen durante dibujo, sin cambiar layout ni recomponer la etiqueta por fotograma.

Las pruebas nativas de host verifican píxeles, estados, clipping por scroll y preferencias; no certifican fluidez, batería, TalkBack ni instalación en un dispositivo físico.
