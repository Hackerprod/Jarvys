# Jarvys v28: recuperación integrada

La reconstrucción está integrada en `recovery/v28-20261007` y compilada como **1.2.22-UX13-recovery** (código 28).

## Verificado

- Full: **923 pruebas aprobadas**, sin fallos/errores/omitidas.
- Play: **839 pruebas aprobadas**, sin fallos/errores/omitidas.
- Ambos APK de desarrollo construidos **sin firma**, sin generar otra clave.
- 48 nuevas fuentes identificadas en el APK representadas en el proyecto; mapa en `new-source-coverage.json`.
- [Resultados finales y límites](validation/FINAL.md), [resultados de pruebas](validation/test-results.json) y [hashes de APK unsigned](validation/unsigned-apks.json).

## Restaurado

Adjuntos y referencias de imagen; almacenamiento/metadatos y acciones de conversaciones; proyectos coding y diarios idempotentes; perfiles/checkpoints e inbox duradero de Crew; jobs Linux con permisos explícitos, cancelación e incertidumbre honesta; autenticación diagnosticable sin filtrar secretos; UI Compose, modelo/esfuerzo, contraste y recuperación explícita. La edición Play no anuncia ejecución Linux ni finge resultados de trabajos no verificables.

## Procedencia

- Base original: `110a059cf63577a5d92d828b09e8f4b20768a0a9`.
- APK de referencia: `Jarvys-UX13-full-v28.apk`, 28 / `1.2.22-UX13`, full/debug.
- SHA-256: `43d2cf85b4843a212aa129868d3111e46a71e21c0c61489dd7305a09e3b6964f`.
- Certificado público: `ad6f699ffdcbc57c0cf0631c772e8323ca678b47bb67f4e09cfd8442e61b39ee`.
- ZIP, 17 DEX y firma APK v2 de referencia verificados. Análisis con JADX 1.5.6 y Apktool 3.0.3.

## Desarrollo

`cd app && ./build_apk.sh --unsigned` produce Full y Play unsigned. El helper histórico `tools/build_apk_bigheap.sh` usa el mismo contrato seguro. El build firmado exige la clave original existente y nunca la reemplaza automáticamente.

La recuperación no es el Kotlin original exacto ni una prueba en dispositivo real. No se generó una clave, no se instaló nada y no se tocaron los datos de la instalación del teléfono. Los informes de hitos anteriores son históricos; el estado vigente está en `validation/FINAL.md`.
