# S6 — reparación live de journal y proyección Coding

**Checkpoint interno WIP; ASYNC continúa desactivado.**

El job instrumentado se conserva antes de reservar/escribir y nunca cae al writer legacy por una
reserva incompleta. Tras finalizar, una única continuación técnica espera el cierre real de las
operaciones originales; no bloquea el worker de Bridge ni repite reserve, aceptación o ejecución.

La reparación exige un pin nuevo emitido por el mismo objeto owner, distinto del pin original.
Los pins hijos que permiten terminar operaciones ya admitidas no acreditan una nueva reparación.
Quiesce impide nuevas admisiones; un pin admitido antes puede terminar el audit y mantiene el drain.
El alias histórico autorizado para leer tampoco obtiene permiso para reparar. Status/list no lanzan
esta reparación; un segundo fallo conserva PENDING, sin loop de reintento.

Store recertifica sólo el candidato privado exacto, verificando bytes, directorios y CAS. Un terminal
ya pendiente no crea otra revisión. El terminal deseado no puede cambiar fase o autorización ni
sustituir silenciosamente hechos incompatibles. En Room, la transacción exacta consulta si hubo
aceptación: la conserva si existe; de lo contrario exige el hecho preclaim del productor y registra
RESERVATION_ABANDONED conservador, sin aceptación, ejecución ni roles semánticos inventados.

## Oráculos y límites

- Manager real: reserva **entregada**, interrupción antes del primer journal y fallo real de directory
  sync después de rename; reparación automática del mismo job, cero enqueue/backend y ausencia no
  probada globalmente. El fixture prepara sólo el directorio, nunca un target o Commit ficticio.
- Quiesce previo al fallo: no nuevo repair pin, bytes originales preservados y PENDING observable.
- Store real: candidato inicial/terminal, idempotencia/CAS, dueño/pin, bytes ajenos, directorio sustituido
  e inicialización sin head tras drenar. Son pruebas del Store, no de todas las fronteras del manager.
- Bridge real: ausencia de aceptación no se infiere de excepciones; proyección genérica no la inventa,
  la reparación restrictiva es idempotente y cambio de generación impide atribuirla a otro destino.

Quedan abiertos ACK de reserva perdido antes de entrega/callable pendiente, terminal deseado tras
ejecución real y fallo final, recovery de otro owner/scanner y Crew STARTED sin respuesta, cadena
semántica del productor real, cobertura externa de compaction y gates restantes. No hay garantía de
power-loss Android, cierre S6/S7/S8, equivalencia Engram ni APK.

## Validación del árbol congelado

- Full y Play compilan: PASS, 1m 21s (91 tasks: 18 ejecutadas, 73 up-to-date).
- **234 Full + 185 Play = 419 casos frescos PASS**, 30/22 clases; cero fallos, errores o skips.
- Ambas ejecuciones terminaron exit 0; runner offline revisado sin excepciones adicionales.
- Diez hashes fuente/test permanecieron idénticos antes y después de la ejecución.
- [Manifiesto por clase, casos y hashes XML](S6-coding-live-repair-test-results.json).

El juicio independiente revisa este checkpoint focal; no sustituye los gates todavía abiertos.
