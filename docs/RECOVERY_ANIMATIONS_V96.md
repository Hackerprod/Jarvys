# Recuperación y reparación de animaciones v96

## Procedencia
Base remota: c485fe4a966c8a9bf529238d381b9602bed8ff47, árbol d20a8695f27e00009794586b70bfa900d984ec55 (corte v93). Referencia de comportamiento compilado: APK v95 firmado, SHA-256 04f2b91a4741aa9a05d3f893a151f827133343216ff8996fb5011fb941522205. Los archivos reconstruidos son código nuevo revisado contra contratos y registros compilados; no son una restauración exacta de los commits perdidos.

## Alcance recuperado
Mapa Idle/Queued/Done→Idle local; Thinking, Working y WaitingUser según eventos actuales; Error e Interrupted terminales observados; tap→Taunt, pulsación larga/acción accesible→menú. WaitingProvider no obtiene un productor ficticio. La identidad de generación, catálogo y ticket impide replay tardío. Idle conserva el original SHA-256 332b63d9b482d8b340c25bf3ab7e3d9f4bc77e7c8e6d5b0eee63ba78d30be1ff. Drawer Agent/Agente usa la mascota, SEED interno no se muestra; Workspaces abre lista con menú lateral y conserva acciones, paginación y Back.

## Cambios funcionales nuevos
- Cliente OkHttp 5.2.3 dedicado: sin cookies ni autenticadores compartidos, sin modificar CookieHandler global, TLS normal y validación HTTPS/origen en cada redirect. Kotlin permanece 2.2.20. Licencia Apache-2.0 de OkHttp; Okio es dependencia transitiva. La variante Android añade PlatformInitializer al proveedor existente de AndroidX Startup: se verificará el manifiesto efectivo, no se declara idéntico al anterior.
- Precarga en fondo de los seis estados remotos publicados del paquete activo al refrescar el índice. Reutilización por hash; descarga en streaming y verificación de tamaño declarado, EOF y SHA antes de publicar. No se eleva un evento ya consumido al completarse una descarga. El cooldown requiere una nueva solicitud; no se promete retry autónomo.
- Aceptar alias del directorio padre mediante contención canónica, manteniendo nombre esperado, hash/tamaño y rechazo de symlink final/directorio dedicado. Apertura NOFOLLOW y comprobación de inode/descriptor.
- Levitar también mientras Rive nativo reproduce: amplitud previa de 3 dp, ciclo de 5 s, tamaño 84 dp y objetivo externo fijo; movimiento reducido y lifecycle siguen vigentes.
- Quitar únicamente la indicación visual pressed/ripple de la mascota, preservando gestos y semántica.
- Observaciones locales de índice/descarga/integridad/caché/importación/surface/primer frame con enums cerrados, sin URL, texto privado, tokens ni mensajes de excepción. Cola de 16 entradas best-effort fuera de UI y ring existente de 32 registros; deduplicación por triple solo tras persistir. No es un estado vivo y la ausencia de registro no demuestra ausencia de fallo.

## Límites
La descarga es streaming, pero el runtime Rive requiere ByteArray al importar; JSON strings no tienen límite universal de memoria. Archivo fsync + rename no garantiza durabilidad del directorio ante corte eléctrico. La validación de metadatos/PNG y pruebas JVM no acredita render JNI/GPU físico. El usuario confirmó Idle real; no se ha identificado una única causa comprobada en su teléfono para todas las descargas ausentes.

## Estado al traspaso del 13-10-2026, 14:17 UTC

Fuente congelada `39a86f05f29ea0aed788a29f881ac91a24a08c75`, árbol `a1c63704549fc112f35dfd7a265742c0a78e958f`, respaldada en el commit remoto `de8c76cde9664b4906c98973f523179d531fb3f1` de árbol idéntico. Este cambio documental no modifica producto ni fixtures. El usuario ha transferido el trabajo a otro agente: no hay continuación automática ni nuevas funciones autorizadas por este documento.

- Compilación de código productivo y tests aprobada; build de preparación de la plantilla completado. Su DEX/perfiles difieren de95 y requieren clasificación, aunque las otras86 entradas ZIP sean idénticas.
- Full reconciliado:300 identidades,299 PASS/1 FAIL (281 retenidas +5 sustituciones +14 últimas). No es una ejecución universal ni toda fresca. La ausencia del snapshot before original de35 rutas de recursos limita la procedencia; no se afirma igualdad completa de todos los inputs antes/después.
- Fallo vigente: `MainActivityWorkspaceRoutingTest.realCatalogNextPreviousAndFirstBrowseBoundedPagesWithoutStartingCrew`, línea266, catálogo de51 espacios permanece Loading durante la espera inicial de10s. Los otros7 casos de routing pasan. Se reparó un loop distinto de scroll del fixture con claves reales, pero la causa del Loading sigue inconclusa. No se ha demostrado un defecto productivo ni se elimina el fallo.
- Play final nunca ejecutado; Release/Vital, lint Full/Play, auditoría de artefacto, firma y readback v96 pendientes. **No existe APK96.** Los APK y resultados de95 no sustituyen estos gates.
- Último diagnóstico detenido por el traspaso a las13:57UTC, exit130. Conservó5 SUCCESS/1 FAILURE y otro caso iniciado; no es un pase terminal ni se suma a la aceptación. Lock Gradle libre comprobado dos veces; ningún nuevo pipeline iniciado. Revalidar el estado antes de retomar.

### Continuación mínima para el agente entrante

Consultar primero el [traspaso y cola vigente](../Pending.md). La selección aprobada es41 clases completas y2 métodos exactos de `MainActivityDrawerNavigationTest` por sabor. Conservar todos los fallos e interrupciones. Evitar otro ciclo largo de timing sin causa productiva comprobada; puede considerarse una entrega de prueba con limitación UI declarada, nunca allPASS. Ejecutar la selección Play y una única FullRelease/Vital con ambos lint; auditar la candidata exacta y la plantilla, firmar con D7 existente y verificar los bytes persistidos antes de entregar. No afirmar identidad de incidentes lint frente95: su XML externo no se recuperó; revisar las incidencias actuales por fuente y categoría.

La evidencia técnica local se conserva bajo `RECOVERY-20261013/validation` junto al checkout, incluyendo `full-qualified-reconciliation.json` (SHA-256 `6edec82eca065421541f21d2fc3a5fa2edb8df8933259ce8bd13ebdc8238ee69`) y el recibo `handoff-interruption.json` de la ejecución diagnóstica. Son resultados de desarrollo, no pruebas físicas. El respaldo Library de fuente y sus datos de restauración figuran en Pending.md.

Después de entregar y respaldar el cierre, detener el desarrollo hasta nueva indicación. UX43 paso4 no está implementado; los acuses del paso3 ya se entregaron con94. UX34 mantiene aprobación separada y CrewAI sigue último.
