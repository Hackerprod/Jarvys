# Factory lean: resultados, permisos y límites

## Cambios

- `apk_factory.inspect` devuelve estado, conteos, campos del spec, límites, advertencia del alcance derivado y evidencia. `scopeWarning` deriva las capacidades predeterminadas y manifiesto; el test detecta entradas del plan que falten.
- `test` sigue ejecutando completo `FactoryRuntimeContractTests.run`; compacta solo las filas `passed`, por lo que los fallos siguen visibles.
- `preview_status` devuelve todos los grupos observados ya agregados y acotados, incluidos `page:started`, `page:finished` y los éxitos de métodos. La anterior diferencia de 898→891 bytes ya no es una medida aplicable.
- La UI genérica Debate/detalle presenta las respuestas dentro de un acordeón cerrado por defecto: `ToolActivityUi.kt:55-85`, `ConversationTimeline.kt:764-817`; cobertura existente: `ToolActivityComposeTest.kt:65-87`.
- Omitir `capabilities` selecciona las 33 capacidades y el manifiesto derivado. `FactorySpec.runtimeConfig()` mantiene schema 1 y escribe la lista efectiva en `factory-app.json`; los subconjuntos explícitos mantienen su alcance. El perfil completo también agrega el proveedor y receiver genéricos de widgets.
- Se restauró en el skill la carga de `/factory-sdk.js` desde `https://app.jarvys.invalid` antes de JS local separado, la prohibición de scripts inline y el dueño único de insets nativos. El core ya se inserta en el system prompt de Coding; `read_skill` obtiene solo referencias bajo demanda.
- No se relaja la autenticación Binder/UID/certificado del host para documentos y handoffs. Los efectos propios de la app usan autorización local; se mantienen identidad, firma, recibos, historial, límites de Android y prompts de producción.

## Medidas de salida

Bytes UTF-8 del JSON serializado para las mediciones anteriores con el mismo fixture. `preview_status` se omite porque ahora conserva todos sus grupos.

| Acción | Antes | Después | Reducción |
|---|---:|---:|---:|
| `inspect` | 3.933 | 2.581 | 34,3 % |
| `test` | 10.618 | 926 | 91,3 % |
| `preview` | 955 | 846 | 11,4 % |

El runner contractual visita todos los métodos; la compactación de `test` solo omite resultados exitosos de la respuesta. `preview_status` mantiene las observaciones del vocabulario finito de operación/resultado y no recoge argumentos, datos de almacenamiento ni mensajes arbitrarios.

## Skill y lecturas repetidas

El core de APK Factory se inyecta en el system prompt de Coding (`CoreAgentRuntime.java:1102-1103,1159-1171`). La herramienta `read_skill` entrega el core o una referencia solicitada; no carga automáticamente las 14 referencias (`LoadSkillTool.java:84-105`). El texto ahora indica que el agente ya tiene el core y debe pedir referencias solo cuando las necesite. Las 18 lecturas observadas fueron llamadas del agente, no relecturas automáticas del loader; el motivo de cada llamada no se conserva en esos contadores.

Los límites de `FactoryGuidanceBundle` son: core 8.192 bytes, cada referencia 16.384, referencias juntas 65.536 y hasta 16 recursos. El cuerpo parseado del core ocupa 5.087 bytes; las 14 referencias suman 65.532 bytes y la mayor ocupa 9.374. La salida completa de `read_skill` para core + `basics` ocupa 10.641 bytes incluyendo ambos encabezados. `manifest.json` usa UTF-8 sin BOM, versión `factory-guidance-v90` y hashes verificados antes de exponer cada referencia.

## Auditoría de autorización y límites

La auditoría se basa en el código. **(1)** indica el alcance de la app/operación en el código; **(2)** una instrucción pendiente del prompt principal; **(3)** una interacción genuina de Android/launcher. Los controles de integridad del host no son pantallas de aprobación.

| Punto | Clase | Evidencia y efecto |
|---|---|---|
| Crear, probar, firmar e instalar con `apk_factory` | (1) + integridad | `ApkFactoryTools.kt:23-50` despacha las acciones declaradas. Build/sign/install comprueban proyecto, scope, artefacto y recibos (`FactoryProjectService.kt:99-104,196-281,288-343`); el código no consulta una política de permiso `ALLOW/ASK/DENY` para esas acciones. `FactoryOperationAuthorizationProvider.kt:35-116` autentica llamadas nativas hechas por las APK instaladas; valida UID/package, caller, capacidades, origen y lease vivo. |
| Firma e instalación de APK | (1) + (3) | `signOwned` requiere el recibo de build exacto y usa la identidad AndroidKeyStore de esa app. `installationOwned` exige el artefacto firmado exacto; Play devuelve `unavailable_in_play` (`FactoryProjectService.kt:196-281,288-343`). Full envía el APK a PackageInstaller; Android puede requerir el ajuste por origen y mostrar su confirmación (`FactoryInstallCoordinator.kt:209,376`). No hay pantalla de permiso por operación de Jarvys. |
| Omitir o declarar capacidades | (1) | Omitir `capabilities` selecciona todo el catálogo; una lista explícita conserva el subconjunto cerrado (`FactorySpec.kt:95-113`). `FactorySpec.runtimeConfig()` serializa la lista efectiva en schema 1. Los métodos no declarados se rechazan. |
| Alcance del manifiesto con todas las capacidades | (1) + (3) | `ManifestPlan` deriva permisos, consultas, componentes y cleartext desde el catálogo/políticas (`ManifestPlan.java:119-255`; `CapabilityCatalog.java`). Omitir la lista incluye permisos declarados para biometría, red, ubicación, audio, pasos, NFC, BLE, notificaciones y jobs, además de queries del host/engines. También registra el proveedor/receiver de widgets (`ManifestPlan.java:119,251,255`), por lo que puede aparecer en el selector del launcher. Las declaraciones no conceden grants de ejecución de Android. `FactoryProjectServiceTest.omittedCapabilitiesDeriveFullCompiledManifestScopeAndExplicitSchemaOneRuntimeConfig` verifica el plan, defaults y config. |
| Selectores, grants y autenticación Android | (3) | Android controla los grants de ejecución, selectores de documentos/fotos/contactos/recipientes y BiometricPrompt. El launcher controla pin/colocación de widgets. Full usa PackageInstaller; Play no instala desde esta función. Ningún lease del host concede grants del sistema. |
| Texto del prompt principal que requiere decisión | (2), pendiente del dueño | `CoreAgentRuntime.java:2129-2132` aún dice que el runtime local está “offline”, que la firma sigue una política guardada SIGN Allow/Ask/Deny y que instalar exige una petición explícita aparte. El código del Factory no implementa esa política guardada; la capacidad de red puede añadir `INTERNET`, y la petición separada de instalación sigue siendo una instrucción del prompt. Se deja intacto el prompt y se registra aquí la discrepancia para decisión del dueño. |

## Aserciones y cobertura

- `FactoryProjectServiceTest.previewStatusSummaryCountsAllEventsAndRetainsSuccessfulAndFailureGroups` comprueba callbacks de página y grupos exitosos, simulados y fallidos en `preview_status`.
- `FactoryProjectServiceTest.omittedCapabilitiesDeriveFullCompiledManifestScopeAndExplicitSchemaOneRuntimeConfig` verifica permisos, features, queries, componentes, defaults, hash y `factory-app.json` schema 1; `scopeWarning` debe contener las entradas del manifiesto.
- `FactoryGuidanceBundleTest` comprueba versión, hashes, recursos y límites de bytes del core/referencias. `FactorySpecTest` y `ApkFactorySkillScopeTest` cubren la selección omitida/subset, esquema y contrato del skill.
- `FactoryPreviewRegistryTest` sigue comprobando la ejecución contractual completa. No se modificó el runner ni el acordeón genérico de UI.

## Verificación

La primera ejecución filtrada de `FactoryProjectServiceTest`, `FactorySpecTest`, `FactoryGuidanceBundleTest` y `ApkFactorySkillScopeTest` compiló y completó 99 tests: 19 fallaron. Diecisiete fallos fueron de `FactoryProjectServiceTest`: varios flujos alcanzaron `signOwned` sin recibo de build completado; los demás fueron aserciones de cancelación/interrupción y una prueba de symlink que Windows rechazó por falta de privilegio. En `ApkFactorySkillScopeTest`, el fixture de migración no pudo verificar el bot guardado y una aserción literal de la frase de identidad no admitía la nota de claves debug insertada dentro de ella. Se reordenó la nota conservando intacta la frase contractual y se actualizó el hash. La repetición enfocada de `FactorySpecTest` (19), `FactoryGuidanceBundleTest` (13) y el test del asset del skill (1) pasó: 33/33. También pasaron los tests dirigidos de resumen `preview_status` y deriva del manifiesto (2/2). `:factory-contract:test` terminó correctamente como `NO-SOURCE`; no hay tests Java declarados en ese módulo. No se usó emulador ni la suite completa.

En `699ee411137c9278d5d5c38d0c914452278b88fb` se reprodujo el fallo aislado sin cambios: `FactorySignedArtifactHistoryTest` falla al verificar el commit de historial con `Signed artifact history commit was not verified; do not publish` (`FactorySignedArtifactHistory.kt:158`), consistente con `AtomicFile.renameTo` sobre archivo existente en Robolectric/Windows. También `FactoryProjectServiceTest.currentArtifactSignsVerifiesAndPublishesWithEphemeralIdentity` falla porque el recibo de build queda en `staged` y `signOwned` no acepta un recibo sin publicar. No se alteraron esas rutas ni el test aislado.
