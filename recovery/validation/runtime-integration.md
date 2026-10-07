# Runtime y aislamiento

El runtime principal y el servicio de chat enlazan los módulos recuperados: checkpoints/perfiles de Crew, herramientas coding, ejecución Linux, adjuntos e imágenes.

La delegación se vuelve a enlazar a un subconjunto real de handlers, conserva guards de invocación y revisa skills/permisos en cada uso. Los workers no heredan adjuntos, memoria ni zonas privadas. Las imágenes solo se preparan para la llamada de modelo del chat principal. Los pedidos almacenados pasan al servicio por IDs, sin adjuntar contenido sensible a Intent extras.

Producción Full (Kotlin y Java), así como fuentes de las pruebas, compilaron. Una pasada anterior ejecutó 535 pruebas: 107 no pudieron iniciar por el cache de Robolectric del entorno y dos auditorías UI se corrigieron usando componentes compartidos; no se relajaron los tests. El runner local fue corregido manteniendo TLS verificado y una prueba Android ProjectScopeTest pasó. La suite completa vuelve a ejecutarse; estos resultados parciales no constituyen validación total.
