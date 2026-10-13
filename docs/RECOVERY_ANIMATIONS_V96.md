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

## Estado
Fuente integrada; pruebas Android, compilación, lint, APK, firma y lectura persistida pendientes. La evidencia de entregas anteriores permanece histórica y no sustituye la validación de esta reconstrucción.
