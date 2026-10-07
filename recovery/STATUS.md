# Recuperación progresiva de Jarvys v28

Rama de trabajo: `recovery/v28-20261007`. La rama original permanece intacta.

## Procedencia

- Base original: `110a059cf63577a5d92d828b09e8f4b20768a0a9`.
- APK de referencia: `Jarvys-UX13-full-v28.apk`, versión 28 / `1.2.22-UX13`, full/debug.
- SHA-256 del APK: `43d2cf85b4843a212aa129868d3111e46a71e21c0c61489dd7305a09e3b6964f`.
- Certificado público: `ad6f699ffdcbc57c0cf0631c772e8323ca678b47bb67f4e09cfd8442e61b39ee`.
- ZIP, checksums de los 17 DEX y firma APK v2 verificados.
- Evidencia: JADX 1.5.6 en modo principal, simple y fallback; Apktool 3.0.3.

## Criterio de restauración

Se incorpora código legible a la aplicación, contrastando los errores del descompilador con instrucciones DEX/smali. El material generado no se presenta como el Kotlin original. Los avances se comprueban y se guardan en commits pequeños.

No se incluyen APK, dependencias descompiladas, claves, tokens ni datos privados de una instalación del teléfono. Las vistas de análisis se conservan por separado.

## Avance actual

- Restaurados los cambios de recursos de texto propios de v28, con su procedencia en `resource-restoration.json`.
- Protegido `app/build_apk.sh`: si falta la clave original, se detiene en lugar de generar otra silenciosamente.
- Restauradas 13 clases del módulo de proyectos de programación y cinco clases de pruebas. Compilan con destino Java 8 contra Android 36; 41 pruebas JVM enfocadas aprobadas. Ver `validation/coding-host.md` para límites.
- Restauradas las rutas de adjuntos/imágenes, metadatos y almacenamiento de conversaciones, con transporte de imágenes y aislamiento. 27 aserciones host aprobadas; suite Android pendiente. Ver `validation/attachments-host.md`.
- Restaurados perfiles/checkpoints Crew y recuperación explícita; 39 pruebas agregadas, aún pendientes de ejecución completa.
- Producción Full compila (Kotlin y Java) en la integración de trabajo; suite completa en curso.
- Restaurado el runtime Linux de proyectos, con estados persistentes, cancelación, paginación y permisos explícitos. Pruebas agregadas, ejecución Android en curso.
- Restaurada la integración del runtime principal, delegación con capacidades restringidas, carga de skills revisada en cada uso y servicio de chat con adjuntos duraderos.
- Restaurado el diagnóstico de autenticación, cancelación y reintento DNS antes de enviar cuerpo; pruebas sin red agregadas.
- En curso: publicación de UI; corrección de regresiones de la suite Android completa con runner validado.
- Pendiente: compilación completa, pruebas y verificación funcional de la aplicación reconstruida.

## Desarrollo sin firma

Durante la recuperación se usa `cd app && ./build_apk.sh --unsigned`. El parámetro Gradle `-PunsignedBuild=true` elimina la configuración de firma de ambos sabores debug. Los resultados llevan sufijo `-unsigned.apk` y no se deben instalar como actualización de la app existente. La ruta se ha validado por sintaxis; la primera compilación completa sigue pendiente de la integración.

## Firma y pruebas

La clave privada original no está en el APK. No se generará una nueva ni se sustituirá la app instalada durante la recuperación. La compilación y las pruebas de fuentes pueden ejecutarse sin firmar un APK. Un paquete firmado con otra clave no puede actualizar directamente la instalación anterior.

Este documento se actualizará con resultados de pruebas reales y tareas que queden pendientes. Una recuperación parcial no se considerará una v28 terminada.
