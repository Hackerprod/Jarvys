# Revisión independiente del roadmap de portabilidad de bots

**Resultado: PASS documental de la revisión 2**, emitido el 31 de octubre de 2026 UTC por un evaluador independiente de la redacción, iniciado con contexto limpio. No quedan hallazgos documentales bloqueantes.

Este checkpoint resume fielmente el dictamen. El PASS aprueba la planificación, no la implementación, el comportamiento del runtime, un APK, la paridad upstream, la recuperación física de Android ni los resultados con proveedores reales.

## Identidad evaluada

- Documento: [Roadmap de portabilidad selectiva de bots para Jarvys](BOT_PORTABILITY_ROADMAP.md), revisión 2, 334 líneas.
- SHA-256 exacto: `727e8e52a932d4a0b413d4a0fae8764b1ffa8384a07dca902c18f9b5207039b0`.
- Baseline Jarvys: `455e53a1f7d6b887beff9d10da8b521edd98c91c`.
- Checkpoint de producción documentado: `bc3639918b36cf0af7831d057c29b90e6f43001e`, v97. No se certificó instalación física.
- OpenMausBot: [`7de00e425848d5f1a2a845cb4784851b87d8cad2`](https://github.com/milind-soni/OpenMausBot/tree/7de00e425848d5f1a2a845cb4784851b87d8cad2).
- OpenDuet: [`677e0fcd148d80b99dd788c6d6f9c1c9f40a72db`](https://github.com/CopilotKit/OpenDuet/tree/677e0fcd148d80b99dd788c6d6f9c1c9f40a72db).

Se mantiene la recomendación de adoptar primero coordinación selectiva de OpenMausBot y después mejoras selectivas de proactividad de OpenDuet, reutilizando los componentes nativos de Jarvys.

## Hallazgos resueltos

La revisión 1 recibió FAIL documental por una contradicción del primer recorrido de recuperación. También señaló dos aclaraciones no bloqueantes. La revisión 2 resolvió las tres; el dictamen no se cambió por alcanzar un número de rondas.

1. **Espera y cambio de ciclo — bloqueante resuelto.** La espera lógica conserva identidad, revisión, resultados consumidos y clave de continuación independientemente de los ciclos ejecutores. Una reanudación nativa sólo sustituye el ciclo esperado mediante un vínculo durable con el control exacto, checkpoint, revisión y comprobaciones de autoridad actuales. Se rechazan ciclos antiguos sustituidos, follow-ups no enlazados y nuevas misiones. La recuperación del padre tiene su propia admisión; un resultado durable no permite repetir un dispatch STARTED/UNCERTAIN. La aceptación incluye casos positivos y negativos de reinicio de hijo y padre sin duplicar consumo o continuación.
2. **Identidad de memoria — aclaración resuelta.** La ruta de tarea usa el bridge, Crew witness vigente, operaciones y recibos scoped de workspace. `RuntimeMemoryIdentity` queda limitado a sus proyecciones existentes de Central; no se amplía su selector ni se usa como identidad de tareas.
3. **Cancelación del padre y del subárbol — aclaración resuelta.** Son operaciones distintas. D2 exige decidir antes de P2 qué ocurre con hijos en cola o ejecutando. No se hereda una cascada upstream; se conservan resultados visibles bajo los permisos actuales y una cancelación explícita debe respetar destinos, revisiones y ciclos exactos, sin despertar al padre detenido ni afectar ciclos posteriores.

La reevaluación también comprobó el outbox de supervisor existente y la diferencia entre RESUME y CORRECT. El roadmap lo reutiliza: no propone otro outbox ni afirma que sus mensajes libres ya implementen la nueva resolución durable de preguntas o el grafo de esperas.

## Alcance y límites de la revisión

Se releyó íntegramente la revisión 2 y se contrastaron las rutas nativas afectadas. Se inspeccionaron fuentes y tests existentes; los pins, licencias, exclusión enterprise y la advertencia sobre CI fallida de OpenDuet se comprobaron durante la revisión independiente.

No se ejecutaron tests, builds, código de referencia, instalaciones, llamadas a proveedores ni cambios externos como parte de la evaluación. No fue una auditoría exhaustiva de seguridad. Los conteos históricos y la entrega de APK se contrastaron con documentación del repositorio, no se reprodujeron en un teléfono ni se validó durabilidad durante meses reales.

Los enlaces relativos a código y tests describen el baseline inspeccionado. Pueden no resolver desde un adjunto aislado o un respaldo público parcial. Eso no autoriza publicar archivos excluidos, credenciales o evidencia privada para completar enlaces.

## Gates que siguen vigentes

**D1 y la autorización del usuario siguen siendo obligatorias antes de implementar.** El PASS no modifica por sí solo la instrucción histórica de Engram antes de bots ni autoriza publicación o despliegue.

P0 debe concretar el mapa de autoridad de cada dato y demostrar un perfil de checkpoint seguro. D2 fija límites y cancelación antes de P2; D3 exige compatibilidad antes de escribir un formato nuevo; D4 decide políticas proactivas; D5 precede cualquier afirmación física o con proveedor real. Son decisiones explícitas de ejecución, no defectos documentales pendientes.
