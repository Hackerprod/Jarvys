# Diagnóstico de autenticación recuperado

Se conserva PKCE/state y el destino oficial de autenticación. El diagnóstico muestra únicamente campos sanitizados y allowlists de categoría/etapa/código. No se imprimen tokens, cuerpos, códigos ni IDs de cuenta.

Reintentos automáticos: exclusivamente fallo DNS previo al cuerpo de POST de token, 2/4/8 segundos dentro de 20 segundos. La solicitud de código de dispositivo no se reproduce; la consulta de estado de aprobación tiene su política transitoria separada. La cancelación invalida callbacks antiguos. El guardado verifica los cuatro campos de sesión leídos de vuelta.

Nuevas pruebas: CodexAuthConnectionRetryTest (7), CodexAuthDiagnosticTest (5), CodexOAuthDiagnosticsTest (7 Robolectric). Las primeras 12 pasaron en JUnit aislado sin red. Robolectric y la suite de autenticación existente están en la ejecución compartida; resultado completo pendiente.
