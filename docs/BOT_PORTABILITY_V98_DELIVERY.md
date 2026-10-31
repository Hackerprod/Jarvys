# Portabilidad P1 — gate de entrega v98

Fecha: 31 de octubre de 2026 UTC.

**PASS independiente del delta de producción y del APK, acotado a host.** El artefacto está preparado; este checkpoint no afirma que haya sido entregado, instalado ni probado en un teléfono.

## Identidad exacta

- Source compilado y revisado: `a50dd3ab9b4029af3a88a5260e59183349bacc4e`.
- P1 previamente aceptado: `6bfd252a7e516cc25144194913a93b13b4dc8c67`.
- APK: `Jarvys-v98-full-arm64-bot-continuity-debug.apk`.
- Package: `com.jarvys.agent`; versión `98`, nombre `1.2.91-BOT-CONTINUITY`; Full DEBUG, ARM64; minSDK 24, targetSDK 35.
- Tamaño firmado: **40.718.267 bytes**.
- SHA-256 del APK firmado: `d70949e04935964d61ab6b7e9d352308c1ef7e1ed602fa6d8bb4b36e9676dd54`.
- Certificado DEBUG original, SHA-256: `d2de47ccf48e02f58ecf4633392ff3f119e925aad1abaa7921c7ce163cfcec4e`. Coincide con el certificado verificado sobre el APK v97 conservado; no se creó una clave nueva. No es una firma de producción.

Un commit posterior de documentación no cambia la identidad de source compilado anterior. No se publica el historial local ni material de firma.

## Verificación host

Full y Play: **92 clases / 833 casos únicos por sabor**, cero fallos, errores u omisiones: cohorte de continuidad 90/798 y suplemento de contexto preparado/protegido 2/35. Fuentes, XML y artefactos se verificaron mediante hashes. El evaluador independiente verificó las cohortes completas y ejecutó de nuevo **5 clases / 64 casos por sabor** tras la construcción. Esos casos focales no se suman al total único. También pasaron 16 ejecuciones en JVM nuevas de los 14 casos de messaging; son repeticiones separadas.

El delta posterior al PASS P1 corrige exclusivamente la invalidación de metadata antes del proveedor. Reconstruye como máximo una solicitud preparada, sólo con señal tipada nativa ligada a la solicitud/token exactos y después de validar fuentes y autoridad original. Conserva el ticket/owners de memoria; rechaza segundo drift, revocación, cancelación, transferencia y señales reenviadas. Una compacción previa, o presupuesto nuevo que requiera compacción durante el refresh, impide esa recuperación. Este refresh no repite proveedor, herramienta ni efecto incierto. Las 12 regresiones nuevas ejercitan el runtime y las cards/compactor nativos. [Detalles y fallos históricos conservados](BOT_PORTABILITY_P1_IMPLEMENTATION.md).

La construcción unsigned y una segunda ejecución forzada de prerrequisitos/paquete producen bytes idénticos: SHA-256 `2b55e8da8e7d00e95b3aea348f5d56a2c3a0d6f7f49e5df4b32111289e265c40`, 40.675.843 bytes. Es reproducibilidad en el mismo toolchain/cache local fijado, no una prueba en otra máquina ni una descarga limpia independiente. El rebuild cambió el contenedor JAR de Factory Contract; el evaluador verificó sus 51 payloads contra las clases compiladas sin cambios y utilizó bindings nuevos para sus reruns.

Se comprobaron firma, manifest, integridad ZIP, DEX, nativas Android/SQLite ARM64, alineación ZIP de 16K, ausencia de SQLite de escritorio, permisos sin cambios y Factory template idéntico a v97. La firma conserva todos los payloads unsigned. El APK v97 permanece intacto, SHA-256 `b512bbc15658271fb77cd2d58be2d62a31a105b269891debbb80cd3c6d003451`.

## Límites y siguientes gates

- Un solo Central, agentes generalistas, memoria Engram nativa y menú restaurado de v97; sin rediseño, chat adicional, memoria paralela, migración ni borrado.
- La recuperación de resultados retenidos sigue exigiendo autoridad USER fresca mediante `continue_result` cuando no existe permiso live exacto. No se reactiva trabajo incierto al abrir la app.
- **Downgrade binario a v97 no soportado** después de nuevos episodios/referencias exactas BLOB. Conservar el APK anterior no constituye una promesa de downgrade seguro. No se solicita borrar datos ni reinstalar.
- No se acredita instalación/aceptación visual física, force-stop/Doze/reinicio real, proveedor vivo, meses reales transcurridos ni paridad integral upstream. No hubo nueva entrega durante esta verificación.
- **P2 continúa deshabilitada a la espera de D2 del usuario:** límites finitos numéricos y política de costo/autonomía/parada. La autorización general de implementación no se convierte en un presupuesto ilimitado ni en cuotas nuevas supuestas.
