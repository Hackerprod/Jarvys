# Gestión selectiva del contexto

UX34 reúne el diseño de un prototipo para reducir representaciones históricas enviadas al modelo sin borrar el historial canónico. Está al final de la cola: primero deben terminar todos los pendientes anteriores. Publicar estos documentos no activa la funcionalidad.

## Documentos

1. [SPEC.md](SPEC.md): contrato normativo completo y gates de fases 0–4.
2. [PHASE0_ACCEPTANCE.md](PHASE0_ACCEPTANCE.md): primer trabajo de desarrollo, fixtures y evidencia requerida.
3. [AUDIT.md](AUDIT.md): auditoría técnica A–F y límites de verificación sobre las bases citadas.
4. [CONTRACT_REVIEW.md](CONTRACT_REVIEW.md): decisiones R1–R6 y precisiones de evaluación, snapshot coherente y verificación incremental.

SPEC.md prevalece sobre alternativas históricas descritas en los documentos de referencia. No hay que volver el código a un commit antiguo; al comenzar una fase se registra la base real y se revisan diferencias relevantes.

## Estado

- Diseño aceptado para un prototipo controlado.
- Implementación aún no iniciada.
- Primer alcance de desarrollo: diagnóstico de finalidad/identidad/uso y transporte sintético, preservando legacy.
- Sin protocolo activo, migraciones reales, purgas, cambios de permisos o APPLY sobre conversaciones reales.
- Fases posteriores requieren sus gates y aprobación; ninguna métrica de ahorro, autonomía general o durabilidad se da por demostrada.

La primera evaluación mecánica usa candidatos anotados. La evaluación semántica es independiente y usa candidatos estructurales sin oráculo que esconda casos difíciles. Ambas son controladas; no equivalen a habilitación de producto general.
