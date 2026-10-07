# Validación parcial del módulo coding

- 13 clases de producción compiladas con javac de JDK 21, destino Java 8, API Android 36 y contratos Core existentes.
- 41 de 41 pruebas enfocadas aprobadas en JVM local.
- Se probaron rutas y symlinks, aislamiento por proyecto, Unicode, cursores, búsqueda, mutaciones, recibos, recuperación de diarios y adopción idempotente.
- Se corrigió un defecto observado en v28: un resultado demasiado grande podía ser descartado y un resultado posterior más pequeño admitido, avanzando el cursor y omitiendo el descartado. Una prueba protege el límite de prefijo ordenado.

## Límites

La prueba host utiliza el sistema de archivos NIO real, un selector Android de prueba y un adaptador de presupuesto de materialización idéntico al valor del APK. Ningún adaptador entra en el código de producción. La compilación completa Android/Robolectric, el camino de I/O previo a Android 26 y la integración con la aplicación siguen pendientes. Estas 41 pruebas no certifican toda la app.

Las cinco clases de tests están en `app/app/src/test/java/com/jarvys/agent/coding/`. Cuando termine la integración, ejecutar `:app:testFullDebugUnitTest --tests 'com.jarvys.agent.coding.*' -PunsignedBuild=true`.
