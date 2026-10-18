# Factory lean: resultados, permisos y límites

## Cambios

- `apk_factory.inspect` devuelve estado, conteos, campos del spec, límites, advertencia del alcance derivado y evidencia. Se quitaron inventarios de métodos, recursos, DEX y permisos del template.
- `test` sigue ejecutando `FactoryRuntimeContractTests.run` completo. Devuelve `total_checks`, `failed_checks` y solo observaciones cuyo resultado no sea `passed`; conserva el estado y un `evidence_limit` breve.
- `preview` conserva metadatos/estado y una nota breve. `preview_status` cuenta todos los eventos y devuelve solo los grupos de advertencia o fallo. `build`, `sign` e instalación ya devolvían recibos/estados acotados.
- `ApkFactoryTools` no tenía argumento para pedir todas las observaciones exitosas. No se añadió uno. La respuesta de `apk_factory` sigue siendo JSON real de la operación, sin texto sintético.
- La vista Debate y el detalle de actividad ya mostraban el resultado dentro del acordeón genérico cerrado por defecto: `ToolActivityUi.kt:55-85`, `ConversationTimeline.kt:764-817`; prueba existente: `ToolActivityComposeTest.kt:65-87`. No se necesitó un renderizador especial ni tocar el UI.
- En `factory.json`, omitir `capabilities` elige las 33 capacidades compiladas. Para satisfacer las políticas exactas que exigen una configuración cuando esa lista está seleccionada, también genera `incomingShare:{mimeTypes:["*/*"],multiple:true}` y `bluetooth:{version:1,scanPrivacy:"full"}`. Una lista explícita, incluida `[]`, mantiene la selección anterior y sus validaciones cerradas.
- El APK generado conserva `factory-app.json` schema 1 y escribe explícitamente la lista efectiva. El manifiesto sigue derivándose del catálogo y las políticas seleccionadas. No se tocaron Room, Binder/UID, identidad appId+certificado, recibos, firma, comparación de alcance ni los grants de Android.

## Medidas de salida

Bytes UTF-8 de `JSONObject.toString()`, antes en el servicio original de la rama y después con el servicio compacto:

| Acción | Antes | Después | Reducción |
|---|---:|---:|---:|
| `inspect` | 3.933 | 2.581 | 34,3 % |
| `test` | 10.618 | 926 | 91,3 % |
| `preview` | 955 | 846 | 11,4 % |
| `preview_status` | 898 | 891 | 0,8 % |

Se midió el JSON serializado con el mismo fixture. Para medir `test`/preview en Robolectric, el helper temporal marcó como publicado el recibo de prueba antes de invocar el harness; Robolectric había dejado ese recibo en `staged`. El helper se retiró del árbol. `FactoryPreviewRegistryTest` sigue comprobando que el runner visita los 134 métodos y las 3 observaciones adicionales (`FactoryPreviewRegistryTest.kt:195-280`); la compresión solo afecta lo que se devuelve.

El test de servicio normaliza el recibo como publicado solo en su fixture Robolectric, que a veces lo deja en `staged` tras la publicacion del APK; luego sigue invocando el runner completo y conserva todas las aserciones de conteo y detalle.

## Skill y lecturas repetidas

Medición del texto que `read_skill` devuelve hoy, incluidos sus encabezados, con la construcción de `LoadSkillTool.java:99-105`:

| Lectura | Caracteres | Bytes UTF-8 |
|---|---:|---:|
| Core APK Factory | 4.720 | 4.724 |
| Referencia `basics` | 5.620 | 5.638 |
| Core + `basics` | 10.340 | 10.362 |
| Mayor referencia (`durable-native`) | 9.402 | 9.454 |

Las 14 referencias suman 65.420 de 65.536 bytes. El core ocupa 4.679 bytes de 8.192; el límite individual de referencia sigue en 16.384 y el parser de Markdown en 256 KiB. Los presupuestos no cambiaron. El core ya era una entrada breve con referencias bajo demanda; se hizo explícito que el agente debe reutilizar una referencia mientras siga en contexto y releerla solo después de compactación.

El loader no expande automáticamente las 14 referencias: `LoadSkillTool.java:84-105` devuelve el core o únicamente el recurso solicitado. `CoreAgentLoop.java:810-888,1140-1174` ejecuta llamadas pedidas por el modelo y agrega sus resultados al transcript. `ToolLoopDetector.java:25-37` detecta repeticiones de fallos, no llamadas exitosas repetidas. Así que las 18 lecturas medidas fueron llamadas explícitas de la secuencia del agente, no relecturas automáticas del loader; el motivo de cada llamada no queda guardado en esos contadores. No se añadieron cache, topes, cambios al presupuesto 16.384 ni cambios al prompt principal.

## Auditoría de consultas, bloqueos y ruido

Las clases significan: **(1)** la autoridad de la aplicación ya permite avanzar por defecto; **(2)** queda una consulta/acción del host que depende de una decisión del dueño; **(3)** es una interacción o grant real de Android y se conserva. Los controles de integridad del host se señalan aparte: no son consultas de permiso.

| Punto | Clase | Evidencia y efecto |
|---|---|---|
| Permisos de operaciones nativas de la APK | (1) | La guía versionada dice que `Settings → Factory permissions` comienza en `ALLOW_ALWAYS`; solo una configuración explícita del usuario a `ASK`/`DENY` cambia eso. `app/APK_FACTORY.md:77-96`; matriz de operaciones: `:79-95`. Un `ASK` guardado es una revisión exacta por operación; no se repite si queda `ALLOW_ALWAYS`. |
| Omitir o declarar un subconjunto de capacidades | (1) | Omitir selecciona todo el catálogo; una lista declarada sigue siendo un límite cerrado y los métodos no declarados se rechazan. `FactorySpec.kt:95-113`; `CapabilityCatalog.java:166-171`; el runner contractual cubre resultados por método. Las filas `passed`, incluidas las pruebas de rechazo correctas para un subconjunto, ya no llenan el resultado del agente. |
| Efectos del manifiesto cuando se omiten capacidades | (1) | El scope total puede incluir USE_BIOMETRIC, INTERNET con cleartext, POST_NOTIFICATIONS, permisos de ubicación, RECORD_AUDIO, ACTIVITY_RECOGNITION, HIGH_SAMPLING_RATE_SENSORS, NFC/feature opcional, permisos/features BLE, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED, dos package queries Jarvys y consultas TTS/reconocimiento de voz. `app/APK_FACTORY.md:152,180`; derivación: `ManifestPlan.java:140-150,169-199,205-230`; `CapabilityCatalog.java:123-171`. Declarar permisos no concede los grants de ejecución de Android. |
| Política exacta al omitir todo | (1) | Se usan filtros incoming share `*/*` con múltiples elementos y BLE `full` para no fallar las validaciones cerradas: `FactorySpec.kt:104-113`. Puede aparecer la app como destino de cualquier tipo MIME; `full` usa el perfil BLE más amplio. El agente puede acotarlos declarando subset y configuración explícita. |
| Notificaciones programadas | (1) | No son una pregunta al usuario: el método está compilado, pero la política separada `notifications:{scheduling,exactAlarms}` continúa opcional y ausencia significa scheduling desactivado (`durable-native.md:13-14`, `FactoryConfig.java:83-85`). El agente debe declararla para un producto que programe notificaciones; no se activó silenciosamente ese comportamiento para toda APK omitida. |
| Build, inspección y prueba | (1) + integridad | `apk_factory` despacha directamente a la operación (`ApkFactoryTools.kt:23-50`). Build requiere scope `WRITE` y writer lease (`FactoryProjectService.kt:99-104`); test/preview exigen recibo publicado del mismo proyecto, hash y versión de scope (`:317-392`). Son controles de workspace e integridad, sin diálogo de permiso de la APK.
| Firma y claves | (1) + integridad | La clave app-specific se crea en AndroidKeyStore en la primera firma; la guía dice que no hace falta una elección interactiva salvo que el dueño configure antes una identidad recuperable (`app/APK_FACTORY.md:199`). Se comprueban recibo exacto, proyecto, hash, versionCode y manifest antes de firmar (`FactoryProjectService.kt:165-203`). El scope firmado conserva capacidades/permisos/features/queries/recursos (`FactorySigningScope.kt:23-49,82-87`). |
| Desajuste de policy `factory.sign`/`factory.install` | Revisión del dueño | La documentación afirma que ambos obedecen `ALLOW_ALWAYS/ASK/DENY` (`app/APK_FACTORY.md:71,191,252,256`) y el prompt de Coding repite la policy de firma (`CoreAgentRuntime.java:2129-2132`). En el dispatch de `ApkFactoryTools.kt:34-50` y los caminos `FactoryProjectService.signOwned`/`installationOwned` (`FactoryProjectService.kt:165-203,257-312`) no aparece una comprobación de esa policy guardada; instalación sí comprueba la disponibilidad del permiso por origen de Android. No se cambió ni se ocultó esta discrepancia porque implementar/eliminar una policy afectaría seguridad y requiere decisión del dueño.
| Petición explícita antes de instalar | (2) Revisión del dueño | El prompt principal exige “separate explicit user request” aunque la policy guardada sea `ALLOW_ALWAYS`: `CoreAgentRuntime.java:2129-2132`. El dueño autorizó la creación de APK y prohibió cambios al prompt principal, por lo que esta consulta permanece para decidirse; no es una confirmación de Android.
| Entrega del APK a Downloads | (2) Acción del host fuera de `apk_factory` | No existe acción `deliver` en el enum del tool (`ApkFactoryTools.kt:23`). El flujo independiente `deliver_file` requiere que el usuario toque Download (`CoreAgentRuntime.java:2114-2119`). Se conserva porque es una transferencia de archivo del host, no un permiso Android de la APK, y su instrucción está en el prompt principal que no se modificó.
| Selector de documentos/fotos/contactos | (3) Android | Se lanzan `ACTION_OPEN_DOCUMENT`/`ACTION_CREATE_DOCUMENT` (`FactoryDocumentCoordinator.kt:118`), el selector de fotos (`FactoryPhotoActivity.kt:320-323`) y `ACTION_PICK` de contactos (`FactoryContactSelection.kt:92`). Android muestra sus pickers y el usuario elige contenido; ALLOW_ALWAYS elimina el ASK de Jarvys, no la elección del selector.
| Grants de Android, biometría y notificaciones | (3) Android | Los grants de ubicación, micrófono, BLE y POST_NOTIFICATIONS siguen siendo decisiones del OS/usuario; `RUNTIME.md:363,451`, `NativeScheduledNotifications.java:47,92-93`. BiometricPrompt es operado por una persona; la policy de la APK no lo sustituye (`RUNTIME.md:363`). El permiso del manifiesto no concede esos grants.
| Instalar o fijar una app | (3) Android/launcher | Si Jarvys no es instalador permitido, `FactoryInstallActivity.kt:54-56` abre Settings para `REQUEST_INSTALL_PACKAGES`; después `PackageInstaller` puede devolver consentimiento del sistema. `FactoryInstallCoordinator.kt:363-378` pide `USER_ACTION_NOT_REQUIRED` donde existe, pero comenta que Android aún decide; los callbacks de usuario se conservan (`:283-300`). Android/launcher también posee el pin de widgets (`app/APK_FACTORY.md:32`).
| Pareado BLE y alarmas exactas | (3) Android | BLE/GATT puede abrir emparejamiento/autenticación del sistema; `app/APK_FACTORY.md:31`. Exact alarms abren `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (`NativeScheduledNotifications.java:47`). No se intenta simular ni pulsar esas pantallas.

Los controles de UID Binder, package/certificado/hash/version/capacidad de la APK instalada y live lease permanecen en `FactoryOperationAuthorizationProvider.kt:38-116`; la validación de recibo y scope también permanece en `FactoryProjectService.kt:173-180,270-304,329-369`. Son barreras del host, no permisos consultados al crear el APK.

## Aserciones y cobertura

- `FactoryProjectServiceTest.compactPublicReceipts...`: comprueba `state=published` en los recibos compactos de build y firma sin invocar Android Keystore ni instalar.
- `FactoryProjectServiceTest.contractTestSummary...` y `previewStatusSummary...`: comprueban el conteo total y adverso, retención solo de resultados fallidos/advertencias y evidencia breve.
- `FactorySpecTest`: nuevo caso prueba omisión de `capabilities`, lista completa de 33 en runtime config schema 1 y las políticas exactas incomingShare/Bluetooth; los casos de subset/vacío existentes quedan intactos.
- `FactoryProjectServiceTest.factoryTestBindsActualBuild...`: añade conteo total, cero fallos, ausencia de filas exitosas y `evidence_limit`; no sustituye ni reduce la ejecución completa del runner.
- `FactoryProjectServiceTest.previewStatus...`: añade conteo total/adverso y conserva el límite de evidencia y el recibo de scope exacto.
- `FactoryProjectServiceTest.inspectExposes...`: deja de requerir arrays grandes `resourceBindings`/`componentDex` en `inspect`; inspecciona el template directamente y mantiene las aserciones de IDs/tipos/rutas y DEX. Añade conteos y comprueba que el inventario detallado no se serializa.
- `FactoryProjectServiceTest.localUi...`: lee la procedencia de librerías del APK publicado en vez de `inspect`; conserva versión, `capabilityRequired:false`, hashes/archivos y determinismo.
- `FactoryProjectServiceTest.omittedCapabilities...`: verifica lista efectiva/config schema 1 y cada permiso, feature, query, cleartext y hash derivados. El manifiesto observable enumera solo capacidades que generan nodos (15); la lista de 33 se verifica por separado en el config.
- `FactoryGuidanceBundleTest` y `ApkFactorySkillScopeTest`: actualizan solo literales de versión o redacción del core; comprueban que la misma regla de seguridad, referencias y modo `READ_ONLY` sigan descubiertos.
- No se tocó `FactoryPreviewRegistryTest`: sigue enumerando métodos, familias y validaciones del runner. No se modificó `ToolActivityComposeTest` porque el acordeón genérico ya empieza cerrado.

## Verificación

Un test aislado de firma en Robolectric falla al confirmar `FactorySignedArtifactHistory` (`Signed artifact history commit was not verified`). No se cambio el historial de artefactos ni se debilito la comprobacion; el test no forma parte de la seleccion enfocada que pasa.

La última selección ejecuta solo tests de `FactorySpecTest`, `FactoryPreviewRegistryTest`, `FactoryGuidanceBundleTest`, los contratos relevantes de `ApkFactorySkillScopeTest` y las pruebas de manifiesto/inspect de `FactoryProjectServiceTest`; todos pasan. No se usó emulador ni se ejecutó la suite completa.
La cobertura de resultados incluye `compactPublicReceipts`, `contractTestSummary`, `previewStatusSummary` y `factoryTestBindsActualBuild` con el runner contractual completo.

Un intento anterior que incluía la clase completa de servicio y migración legacy detectó fallos de fixture ya presentes en Robolectric: recibos que el fixture lee como `staged` después de publicación y el error `Stored bot did not match the saved revision` de Crew. No se cambiaron sus controles de recibo ni persistencia. El helper temporal de medición normalizó el recibo en su fixture para medir test/preview, y se eliminó al terminar.
