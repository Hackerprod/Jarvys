# S6 — proyección exacta y generación del destino

Diseño implementado y probado de forma focal después del adapter. **Full/Play 140/140 por sabor;
wiring de producto todavía pendiente**. Ver [resultados](S6-connection-projection-test-results.json).
No amplía permisos ni convierte elección local en autorización de una operación.

## Estado previo inspeccionado (checkpoint adapter)

- `ConnectorStateEvent` en `PersonalAgentEntities.kt` sólo conserva id, connector_id, connected y
  connected_at_ms. El bridge genera un UUID por escritura, no recibe una identidad durable exacta.
- `PersonalAgentRepository` enlaza al crear el intent el último connected=1 por timestamp/UUID y lo
  etiqueta authorization=allow. Esa consulta debe desaparecer para nuevos recibos.
- `JarvysChatDatabaseProvider` gestiona handles/pools/cierre, pero no expone generación durable de
  proyección ni un fence compartido con limpieza del outbox. La foundation sólo modela ese fence.
- Room está en schema 5. El adapter aislado tiene destino inyectado fijo; no acredita cambio de
  destino real ni recuperación tras resultado incierto de un reemplazo.

## Datos y proyección propuesta

1. Añadir mediante migración explícita metadata tipada para eventos nuevos (store epoch, secuencia,
   operation/parent, elección previa nullable, razón y payload exacto versionado). Conservar filas
   legacy sin inventar secuencia/proof: son historia no acreditada por el protocolo nuevo.
2. El id nuevo es epoch:sequence. Transacción de lote: insertar si no existe; si existe exigir igualdad
   de todo el payload. Cualquier conflicto aborta el lote, nunca reemplaza el original. Reloj y UUID
   no deciden orden; la secuencia sólo se compara dentro del mismo epoch.
3. Una identidad de generación del destino debe persistir dentro de la base y sobrevivir a cierres
   normales; una base reconstruida obtiene otra. Usar metadata operacional dedicada, no documentos
   del agente ni memoria del usuario. Se reutiliza `storage_meta` con clave `connection_projection_generation_v1`: inserción lazy
   transaccional si falta; valor existente malformado falla cerrado, nunca se reemplaza silenciosamente.
4. Separar esa generación durable de la instancia viva del provider. Un cierre/revalidación puede
   invalidar ACKs en vuelo sin fingir reconstrucción de la base ni fabricar historial.
5. `project(batch)` devuelve ACK sólo después del commit Room y conserva identidad exacta de eventos
   y destino. El coordinador limpia sólo pendientes coincidentes, bajo fence que impida cambiar el
   destino entre la última validación y el commit de limpieza de preferencias.

## Orden y límites del fence real

- Startup/readiness se espera fuera de main y antes de adquirir el fence de proyección.
- Proyección Room no mantiene writer de preferencias ni locks de registry/lifecycle. Liberar la
  transacción/lease antes de solicitar ACK y esperar fence.
- Reemplazo/cierre/revalidación y ACK usan el mismo fence por raíz. Orden acordado: fence → writer
  de preferencias. No permitir la inversión writer → fence.
- Orden provider acordado: fence → monitor. `closeInstance` captura la lista bajo monitor y lo
  libera antes de cerrar cada handle. El cierre invalida evidencia antes de cerrar Room, incluso si
  el cierre falla. No adquirir un fence desde una lease Room/read activa; el guard falla antes de
  esperar readiness. Pruebas de cierre con transacción en vuelo y callback rechazado son necesarias.
- Un reemplazo que lanza excepción después de poder cambiar el destino invalida ACKs anteriores.
  Requiere revalidación acreditada del destino real; no basta cambiar una cadena o confiar en el
  destino viejo. Si la base real finalmente no cambió, revalidar bajo fence mediante lectura real de
  su generación durable e incrementar la identidad viva/version del fence. Aceptar nuevos batches
  de esa generación, nunca restaurar el token anterior ni aceptar ACKs previos a la incertidumbre.
- Memoria OFF y olvidar episodios no deben borrar estos hechos operacionales ni recrear recuerdos
  retirados al reproducir el outbox. No prometer reconstrucción de historia ya drenada y perdida:
  sólo pendientes y últimos snapshots acreditados disponibles.

## Observación causal de conexión

Capturar observación del MISMO ReadView que usa el gate. Propagarla en metadata tipada local por
invocación, no argumentos del modelo ni búsqueda posterior de grants. La admisión de efecto puede
referenciar eventId exacto aunque la proyección esté pendiente; no requiere fallback a otro evento.
El evento de elección no lleva authorization=allow. El permiso/consentimiento efectivo pertenece
exclusivamente al recibo de admisión de la operación. Historial previo se conserva como histórico,
no se reinterpreta como autoridad actual.

## Pruebas necesarias antes de wiring

- Migración schema 5 → nueva versión, datos legacy intactos, índices/FKs y comparación fresh/migrado.
- Replay exacto, conflicto de payload, lote rollback y reloj repetido/retrocedido.
- Room commit seguido de crash antes de cleanup; cleanup incierto y replay sin duplicación.
- Reset/cierre con ACK en vuelo, mismo destino revalidado, destino nuevo y reemplazo incierto.
- Proyección rezagada tras baja: ningún enlace al grant anterior, sólo referencia exacta o desconocida.
- OFF/forget/rebuild: no reconnect, no nueva autorización ni resurrección de episodios.
- Pruebas de locks con barreras reales, sin UI bloqueada ni callbacks externos bajo writer.

## Binding y retención acordados

- Bootstrap de preferencias no espera Room ni reset de conversaciones. El estado inicial es UNBOUND;
  una baja explícita no depende de poder abrir la base. La generación previa reconocida se conserva
  separada de la disponibilidad actual del destino.
- El bridge acredita evidencia privada con generación durable, issuer por archivo, instancia viva y
  versión. Lee dentro del fence en su worker; libera lease/fence antes de retornar. El owner vincula
  después con validación pura bajo el mismo fence, sin callback bloqueante a Room. Cada binding tiene
  identidad opaca nueva, incluso con igual generación. Un ACK viejo nunca vuelve a ser válido.
- El lote conserva su binding exacto. El bridge rechaza evidencia ajena/fabricada y exige igualdad
  completa de cada evento dentro de una única transacción. Sólo después se pide cleanup al owner.
- Cleanup usa snapshot cacheado versionado, keep-set exacto y una operación atómica de ACK/poda/floors.
  Retiene current, último recibo, último snapshot confirmado sólo para proyección, toda incertidumbre
  y dependencias de pendientes. No conserva todos los UUID confirmados para siempre ni elimina
  incertidumbres por edad/tamaño. Sin Room o con incertidumbre, backlog puede crecer.
- Cada commit posterior materializa candidatos/pending reservados que antes fallaron sin escribir
  bytes; nunca inventa current/proof de ese candidato. Floors de revisión/intent/seq no son prueba.
  Un candidato huérfano sin current/legacy requiere reparación restrictiva nueva; no habilita DEFAULT.

El wiring de producto continúa pendiente. Foundation y adapter aislados no satisfacen estas
pruebas por sí solos; no activar la fachada de producto antes de cerrar también Registry/UI/readers.
