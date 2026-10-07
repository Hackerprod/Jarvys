# Validación final de recuperación v28

## Resultado

- Compilación completa Full y Play aprobada, Kotlin y Java.
- Full: **923/923 pruebas aprobadas**, 0 fallos, 0 errores, 0 omitidas, 198 clases de prueba.
- Play: **839/839 pruebas aprobadas**, 0 fallos, 0 errores, 0 omitidas, 178 clases de prueba.
- Total: **1.762 ejecuciones de pruebas aprobadas**.
- Ambos APK unsigned generados con `./build_apk.sh --unsigned`. Versión 28 / `1.2.22-UX13-recovery`, paquete `com.jarvys.agent`.
- Integridad ZIP y ausencia de firma verificadas. `apksigner verify` falla por falta de firma, como se espera para estos artefactos. No se creó ninguna keystore. Hashes y tamaños en `unsigned-apks.json`.
- El build unsigned mantiene separado `helper_manifest.unsigned.json` y no sobreescribe el manifiesto de distribución firmada.

## Reproducción

Desde `app/`, con JDK 21, Android SDK 36 y build-tools 35.0.0 disponibles:

```sh
./gradlew -PunsignedBuild=true :app:testFullDebugUnitTest :app:testPlayDebugUnitTest
./build_apk.sh --unsigned
```

En el ejecutor usado, la JVM de Robolectric necesitó un `user.home` escribible, el proxy del entorno y el almacén de certificados ya confiado por el sistema. Se configuraron externamente mediante un init script; TLS permaneció verificado. [Opciones oficiales de Robolectric](https://robolectric.org/configuring/).

## Alcance y límites

El proyecto integra las 48 fuentes nuevas identificadas por nombre en v28, recursos/prompt recuperados y las modificaciones principales de runtime, almacenamiento, herramientas, Crew, Linux, autenticación y UI. Se conservaron las fuentes originales anteriores como base.

El Kotlin/Compose exacto, comentarios originales, historial perdido, fuentes no empaquetadas y clave privada de firma no son recuperables del APK. Los nombres de archivo representados y las pruebas no equivalen a una demostración de paridad de cada comportamiento con el binario original.

No se ha instalado el APK, probado en un teléfono real, realizado un login real ni enviado llamadas a proveedores externos. La firma original sigue siendo necesaria para actualizar la instalación existente sin cambiar identidad. Los APK de desarrollo permanecen sin firmar por instrucción del usuario.

UX14 parcial no se mezcló con esta recuperación de v28. Los archivos de análisis decompilado y dependencias binarias no se añadieron al repositorio.
