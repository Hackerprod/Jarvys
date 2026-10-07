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
- En curso: proyectos de programación y diarios; perfiles/checkpoints Crew; adjuntos e imágenes; integración de runtime y UI.
- Pendiente: compilación completa, pruebas y verificación funcional de la aplicación reconstruida.

## Firma y pruebas

La clave privada original no está en el APK. No se generará una nueva ni se sustituirá la app instalada durante la recuperación. La compilación y las pruebas de fuentes pueden ejecutarse sin firmar un APK. Un paquete firmado con otra clave no puede actualizar directamente la instalación anterior.

Este documento se actualizará con resultados de pruebas reales y tareas que queden pendientes. Una recuperación parcial no se considerará una v28 terminada.
