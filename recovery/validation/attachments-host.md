# Adjuntos e imágenes: verificación de recuperación

- Compilación independiente con javac de ChatAttachment, ConversationImageReference, ImageEditInput y AttachmentStore.
- 27 aserciones aprobadas sobre archivos reales: cierre de streams, limpieza parcial, aislamiento de conversaciones, nombres hostiles, huérfanos, symlinks/sentinelas y base64 canónico.
- Todas las fuentes propias se analizaron sin errores de sintaxis; la pasada Java de la app llegó a estas clases sin diagnósticos.
- Añadidas 35 pruebas en cinco clases *RecoveryTest, más cuatro pruebas de formato de requests de imagen. La ejecución completa Android/Robolectric de estas pruebas queda pendiente.

La restauración conserva los datos originales del usuario separados de las partes preparadas para el modelo; las herramientas delegadas no heredan adjuntos ni zonas privadas. Los recibos de imagen se limitan a la conversación. Los diagnósticos omiten contenido privado. La eliminación verifica rutas y deja constancia de conversación eliminada para bloquear callbacks tardíos.

Endurecimiento adicional respecto al binario: validación canónica de ledgers de conversaciones, además de los guards ya presentes para runs. No se ha ejecutado una sesión real ni enviado una petición a ningún proveedor.
