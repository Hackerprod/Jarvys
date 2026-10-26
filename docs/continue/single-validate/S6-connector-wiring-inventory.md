# S6 — inventario de wiring de conexión

Estado: **inventario read-only; wiring no implementado ni validado**. Fecha: 2026-10-26.
Base: checkpoint `912474ff`; líneas orientativas del código inspeccionado.
Contrato: [S6-connector-state-contract.md](S6-connector-state-contract.md), especialmente §9 y su adenda.
Este mapa no amplía permisos, políticas ni capacidades.

## 1. Estado y lifecycle

Rutas relativas a `app/app/src/`:

| Archivo | API / ubicación | Comportamiento actual |
|---|---|---|
| `main/java/com/jarvys/agent/connectors/ConnectorRegistry.kt` | `ConnectorConnectionPreferences`, 165–168 | `isConnected`, `setConnected`, `hasConnectionChoice`; falsos de tests conservan interfaz simple. |
| mismo | `ConnectorStateStore`, 171–190 | Namespace `jarvys_connectors`; `apply()` de elección; evento Room sólo false→true. |
| mismo | `connect`, 337 | Resuelve definición actual y exige readPermissions antes de activar. |
| mismo | `connectFromSystemSettings`, 343 | Sólo notification-listener settings; permite seleccionar antes de que Android conceda acceso. |
| mismo | `activate`, 351 | Guarda true antes de `runtime.connect`; rollback false ante excepción. |
| mismo | `disconnect`, 363 | Guarda false antes de cleanup; fallo del store puede impedir cleanup. |
| mismo | `connectIfUnselected`, 371 | Sólo ausencia de elección permite conexión por defecto; false explícito debe conservarse. |
| mismo | `unregister`, 375 | Guarda false y limpia runtime bajo registry lock; requiere separar reserva/CAS de IO. |
| mismo | `calculateState`, 625 | Elección local + readPermissions + `connectionAccessGranted`; elección no equivale a permiso. |

**Settings es una diferencia intencional:** `connectFromSystemSettings` puede dejar selección local
con estado `PERMISSION_REVOKED`. No convertirlo en el flujo ordinario ni inventar autorización Android.
Bootstrap de true legado debe ser `LEGACY_OBSERVED`, sin backfill de `runtime.connect` ni handshake.
Defaults necesitan CAS de ausencia; una lectura previa seguida de escritura no protege una baja concurrente.

## 2. Callers y afinidad de hilo

| Caller | Entradas a migrar |
|---|---|
| `main/.../connectors/ConnectorsScreen.kt` | Callback de permisos 149; settings 165; altas directas 176/185; baja+navegación 197. Todos UI. |
| `main/.../modules/ModulesScreen.kt` | `LaunchedEffect` 55 instala/conecta defaults; sigue en main sin dispatcher explícito. Switch 135 conecta/desconecta. |
| `main/.../modules/ModuleRegistry.kt` | `installAndConnectUnselected` 99–107 llama `connectIfUnselected` para módulos sin permisos. Preservar elección explícita. |
| `main/.../CoreAgentRuntime.java` | 2575: instalación/conexión de módulos en ensamblaje del runtime. Conservar ruta síncrona para caller worker. |
| `main/.../tasks/ScheduledTaskProcessor.kt` | 213: instalación/conexión de módulos en worker. |
| `full/.../flavor/FullRemoteServicesPanel.kt` | OAuth callback alta 276; bajas de scopes 287/288; desconexión local 316/317; cambio identity mode 443; revoke 473/474; guardar configuración 502. |

Las operaciones de remote-services MCP (`connections.connect`) y HTTP `connection.disconnect()` no
son llamadas a `ConnectorRegistry`; no migrarlas por coincidencia textual.

### Superficie compatible propuesta

- Mantener APIs síncronas de registry para workers/test adapters; persistencia bloqueante nunca en main.
- UI usa requests tipados de conexión/baja/unregister, propiedad del coordinador fuera de composición,
  IO dedicado y callbacks en main. No asumir que lanzar una coroutine mueve IO fuera de main.
- Capturar **identidad/generación de definición y revisión de elección antes** de permiso u OAuth.
  La referencia sola no impide que un callback antiguo reconecte la misma definición tras una baja.
- Device ya conserva `ConnectorDefinition` pendiente, pero `canConnect` sólo valida permisos.
  GoogleServiceCard recibe sólo connectorId: debe capturar snapshot al iniciar autorización, no al volver.
- Baja aplica deny overlay inmediatamente para la identidad/revisión vigente; cleanup y retry se ligan
  a ese epoch/runtime handle, nunca a una alta posterior. No sostener registry lock durante IO/Room/runtime.
- Success toast y navegación siguen resultado honesto, no enqueue ni `runCatching(connect)` ignorado.
  No reenviar autorización OAuth, mostrar excepciones crudas ni cancelar operación al salir de composición.

## 3. Lectores directos de preferencias

El grep de producción Main/Full identifica dos lectores externos al store:

1. `main/.../connectors/NotificationBuffer.kt:222`: `connected_notifications`.
   Sustituir por snapshot confirmado + deny overlay compartido. **Preservar** la rama anterior
   `ProactiveNotificationDispatch` (209–219): su autoridad es independiente de la captura del conector.
2. `full/.../connectors/GmailConnector.kt:678`: lambda de elección de Contacts para acceso a contactos.
   Sustituir por la misma fachada compartida, manteniendo validación del permiso OS. Evitar inicialización
   recursiva de `ConnectorRegistry` desde la creación de sus propias definiciones.

No crear snapshots independientes por fachada. El namespace también recibe escrituras de políticas y
`permission_asked_*`: un `commit(false)` puede cambiar memoria y otra escritura persistir el candidato.
El protocolo de fases/outbox del contrato, no el retorno booleano aislado, debe impedir altas falsas.

## 4. Proyección y evidencia

`ChatDatabaseBridge.recordConnectorStateEventSync`, `PersonalAgentEntities`, `Migrations` y
`PersonalAgentRepository` son superficies coordinadas aparte. La query actual elige sólo connected=true,
ordena timestamp/UUID y añade autorización allow: no representa correctamente revocación ni autoridad.

El wiring debe transportar snapshot exacto de elección y secuencia/revisión al gate/recorder; Room es
proyección idempotente. Lag nunca autoriza enlazar una alta anterior. Evento = `local_choice`, no recibo de
WRITE ni permiso OS/OAuth. Respetar generation/reset y replay definidos por contrato §9/adenda.

## 5. Criterios de revisión antes de habilitar wiring

- Callbacks tardíos: misma definición tras baja y definición reemplazada con mismo id.
- Ausencia vs false explícito; LEGACY_OBSERVED sin efectos de reconexión.
- Disconnect con store/Room fallando, overlay visible en ambos lectores, cleanup sin bloquear baja.
- Commit fallido seguido de writer ajeno y restart; proyección atrasada/reset sin grant obsoleto.
- Main/Full UI sin IO bloqueante ni éxito ficticio; callbacks main y operación independiente de composición.
- Proactividad independiente intacta y permisos/gates actuales preservados.

No se modificaron producción ni tests para este inventario. Las garantías se verifican contra el parche
real y sus pruebas; este documento no declara PASS de implementación.

## Precisión de revisión independiente

`ConnectorStateStore.init` enlaza las preferencias de política por defecto y elimina las claves
retiradas `connected_email` / `connected_sms_draft` mediante `apply`. Integrar este mantenimiento en
bootstrap sin reimportar ni reactivar conectores retirados. La API `unregister` existe, pero el
inventario no encontró un caller productivo: no crear una nueva superficie UI para satisfacerla.
La revisión cruzada de Main/Full/Play no encontró otro lector directo de elección fuera de los dos
señalados; esto es evidencia de inventario del checkout, no garantía de futuras incorporaciones.
