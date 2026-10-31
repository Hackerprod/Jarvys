# Portabilidad de bots — checkpoint público P0

Fecha: 31 de octubre de 2026 UTC.

**Resultado: PASS documental de P0, revisión 2**, emitido por un evaluador independiente con contexto limpio. Aprueba el diseño de contratos e integración; no certifica implementación P1, continuidad física Android ni comportamiento con proveedores reales.

## Identidad y alcance

- Auditoría P0 R2 evaluada, SHA-256: `8b44cd896eb08a16851e1f465945007dc270fb438dc2d3d523b186ca3a9c559b`.
- Roadmap R2: [BOT_PORTABILITY_ROADMAP.md](BOT_PORTABILITY_ROADMAP.md), SHA-256 `727e8e52a932d4a0b413d4a0fae8764b1ffa8384a07dca902c18f9b5207039b0`.
- Baseline de fuente auditada: `46176a6280e8a79e7dfa5eb0af88ffbc977acfd4`; producción documentada v97: `bc3639918b36cf0af7831d057c29b90e6f43001e`.

P0 inventarió las capacidades existentes, sus brechas, las autoridades de datos, las transiciones y la cohorte de aceptación. No modificó producción, esquemas, tests, versión, menú ni memoria. No instaló ni ejecutó las aplicaciones upstream. Este resumen no reproduce informes ni evidencia privada.

## Contratos revisados

La revisión 1 recibió FAIL documental por falta de concreción de autoridad, CAS, conservación de la espera y unión a la continuación de Central. La revisión 2 resolvió ese hallazgo; el FAIL anterior no se reescribe como PASS.

- La coordinación propuesta usa un único bloque versionado `centralContinuationV1` en el checkpoint nativo existente del hijo. Su propietario lógico es Central y su escritor es el Store; no es memoria adicional, otro outbox ni un estado editable por el modelo.
- La actualización exige comparación de revisión y hash bajo el lock de sesión. Los guardados ordinarios preservan el bloque vigente; los cambios de coordinación preservan el estado del runtime. Se especifican orden de locks y tratamiento conservador de fallos de persistencia.
- La espera conserva identidad, selección y consumo de resultado, clave de continuación y vínculos de ciclos. Un sucesor requiere control nativo exacto, punto seguro y checkpoint confirmado antes de dispatch; no se deduce del último ciclo visible.
- Las respuestas retenidas reutilizan el outbox existente y requieren correlación y admisión específicas. Un envío STARTED incierto no se repite por ausencia de ACK.
- La continuación de Central reutiliza inputs y recibos existentes, con claim durable y unión exacta entre input, mensaje assistant y generación. Todas las rutas de consumo deben respetar el protocolo; no se permite entrada indirecta como steering genérico.

## Evidencia basal y límites

Se ejecutaron **38 casos Full y 38 Play**, seis clases por sabor, con cero fallos, errores u omisiones. Se reutilizaron binarios previamente compilados, verificando identidad de fuentes y artefactos antes y después de cada sabor. **No se recompiló ni construyó un APK.**

Estos resultados corresponden a la cohorte basal existente, no a pruebas del protocolo futuro. No hubo validación en dispositivo Android físico ni llamadas a proveedores reales. El guard del runner no equivale a aislamiento de red del sistema operativo. Los conteos no deben reutilizarse como resultados P1.

## Obligaciones pendientes de P1

1. Demostrar recuperación segura de checkpoints nativos generalistas: persistible no equivale a reanudable. Identidad y autoridad vigentes, punto seguro verificable y control explícito siguen siendo obligatorios.
2. Implementar y probar preservación, CAS, correlación de respuestas, vínculos de ciclos, selección de resultados y continuación única, incluidos crashes entre stores y todos los caminos de consumo de Central.
3. Rechazar efectos STARTED/UNCERTAIN, controles irresueltos, fuentes ajenas, ciclos obsoletos y conflictos de payload; conservar evidencia sin replay automático.
4. Verificar compatibilidad antes de habilitar escrituras. No se proponen migraciones de Room/Registry ni cambio de envelope. El downgrade a v97 con episodios nuevos no está soportado; desactivar nueva admisión no autoriza borrar datos.
5. Probar STOP/CORRECT, revocación, memoria scoped, fallos de persistencia y regresiones de UI/menú, con Full y Play sobre los mismos bytes finales y revisión independiente de la implementación.

El PASS P0 es de diseño. No sustituye estos gates ni autoriza PR, merge, despliegue o cambios destructivos. Árboles de workers y nuevas políticas proactivas permanecen en sus fases posteriores.
