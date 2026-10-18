# Skills de terceros: DevelopersGlobal/ai-agent-skills

Fuente: [DevelopersGlobal/ai-agent-skills](https://github.com/DevelopersGlobal/ai-agent-skills), carpeta `skills/`.

Commit upstream exacto: `f47f948a577e8c901193da92c12899ff4322f855`.

Licencia: MIT. Titular del aviso upstream: **Copyright (c) 2026 DevelopersGlobal**. El aviso y el texto íntegro de la licencia se conservan en [ai-agent-skills-LICENSE.txt](../app/src/main/assets/licenses/ai-agent-skills-LICENSE.txt).

Se incluyeron **31 skills `SKILL.md` y 22 Markdown categóricos (53 en total)**. No se excluyó ninguno tras el audit.

## Cambios de conversión y alcance

- Cada skill usa frontmatter Jarvys permitido: `id`, `name`, `description`, `version: 1` y `tags`; el directorio coincide con `id`. Todos incluyen la etiqueta `internal`, sin añadir claves al parser ni habilitar herramientas inexistentes.
- Se conservaron los cuerpos. Se sustituyó el frontmatter upstream (incluidos `category`, `applies-to` y `version` upstream); los ficheros categóricos perdieron su tabla de plataformas (`Works With`) y los ejemplos atribuidos a Claude se atribuyeron al asistente. Algunas afirmaciones sobre capacidades se expresaron en función de si hay runtime/herramientas disponibles.
- Los enlaces relativos entre skills apuntan a ids Jarvys. No se empaquetaron `agents/` ni `references/`: ningún skill necesita un agente de referencia y los cuatro checklists referenciados son material auxiliar; las instrucciones del skill siguen completas sin ellos. Se retiraron esas referencias a archivos que no se distribuyen.
- Se quitaron 22 caracteres U+200B en ocho ficheros categóricos; estaban únicamente junto a fences para proteger ejemplos Markdown anidados y no contenían instrucciones. No se detectaron comentarios HTML.
- `production-deployment` conserva sus pasos, con una nota que exige autorización explícita del usuario y las aprobaciones vigentes antes de ejecutar un deploy o rollback.

## Auditoría de seguridad por skill

Se leyó cada archivo completo. No se encontraron instrucciones de exfiltración, `curl | sh`, desactivación de permisos/aprobaciones ni órdenes para ignorar al usuario. Los ejemplos de prompt injection en `prompt-injection-defense` son payloads citados como casos defensivos para probar; no se presentan como instrucciones a seguir. Las URLs restantes son referencias/esquemas de ejemplo y el health check Docker de `localhost`; no descargan ni ejecutan scripts remotos. No se excluyó contenido.

| Nombre upstream y ruta | ID | Resultado | Nota |
|---|---|---|---|
| AI Output Validation (`skills/ai-output-validation/SKILL.md`) | `com.jarvys.ai-output-validation` | modificado | referencias a skills convertidas a ids Jarvys |
| API Design (`skills/api-design/SKILL.md`) | `com.jarvys.api-design` | modificado | referencias a skills convertidas a ids Jarvys |
| System Design Coach (`skills/architecture/system-design-coach.md`) | `com.jarvys.architecture-system-design-coach` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |
| CI/CD Pipelines (`skills/ci-cd-pipelines/SKILL.md`) | `com.jarvys.ci-cd-pipelines` | modificado | referencias a skills convertidas a ids Jarvys |
| Code Explanation (`skills/code-explanation/SKILL.md`) | `com.jarvys.code-explanation` | modificado | referencias a skills convertidas a ids Jarvys |
| Code Review (`skills/code-review/SKILL.md`) | `com.jarvys.code-review` | modificado | referencias a skills convertidas a ids Jarvys; enlace a checklist auxiliar no incluida retirado |
| Context Loading (`skills/context-loading/SKILL.md`) | `com.jarvys.context-loading` | modificado | referencias a skills convertidas a ids Jarvys |
| Color Palette Generator (`skills/creative/color-palette-generator.md`) | `com.jarvys.creative-color-palette-generator` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant; U+200B solo junto a fences; retirado |
| Naming Consultant (`skills/creative/naming-consultant.md`) | `com.jarvys.creative-naming-consultant` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |
| CSV Detective (`skills/data/csv-detective.md`) | `com.jarvys.data-csv-detective` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant; U+200B solo junto a fences; retirado |
| Data Storyteller (`skills/data/data-storyteller.md`) | `com.jarvys.data-data-storyteller` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant; U+200B solo junto a fences; retirado |
| SQL Query Optimizer (`skills/data/sql-query-optimizer.md`) | `com.jarvys.data-sql-query-optimizer` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant; U+200B solo junto a fences; retirado |
| Debugging Methodology (`skills/debugging-methodology/SKILL.md`) | `com.jarvys.debugging-methodology` | modificado | referencias a skills convertidas a ids Jarvys |
| API Designer (`skills/developer/api-designer.md`) | `com.jarvys.developer-api-designer` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant; U+200B solo junto a fences; retirado; límites de herramientas expresados condicionalmente |
| Bug Hunter (`skills/developer/bug-hunter.md`) | `com.jarvys.developer-bug-hunter` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant; U+200B solo junto a fences; retirado; límites de herramientas expresados condicionalmente |
| Code Reviewer (`skills/developer/code-reviewer.md`) | `com.jarvys.developer-code-reviewer` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant; límites de herramientas expresados condicionalmente |
| Git Commit Crafter (`skills/developer/git-commit-crafter.md`) | `com.jarvys.developer-git-commit-crafter` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |
| Regex Wizard (`skills/developer/regex-wizard.md`) | `com.jarvys.developer-regex-wizard` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant; límites de herramientas expresados condicionalmente |
| CI/CD Pipeline Builder (`skills/devops/cicd-pipeline-builder.md`) | `com.jarvys.devops-cicd-pipeline-builder` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |
| Dockerfile Optimizer (`skills/devops/dockerfile-optimizer.md`) | `com.jarvys.devops-dockerfile-optimizer` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant; U+200B solo junto a fences; retirado |
| Documentation (`skills/documentation/SKILL.md`) | `com.jarvys.documentation` | modificado | referencias a skills convertidas a ids Jarvys |
| Error Handling (`skills/error-handling/SKILL.md`) | `com.jarvys.error-handling` | modificado | referencias a skills convertidas a ids Jarvys |
| Frontend Engineering (`skills/frontend-engineering/SKILL.md`) | `com.jarvys.frontend-engineering` | modificado | referencias a skills convertidas a ids Jarvys; enlace a checklist auxiliar no incluida retirado |
| Git Workflow (`skills/git-workflow/SKILL.md`) | `com.jarvys.git-workflow` | modificado | referencias a skills convertidas a ids Jarvys |
| Goal-Driven Execution (`skills/goal-driven-execution/SKILL.md`) | `com.jarvys.goal-driven-execution` | modificado | referencias a skills convertidas a ids Jarvys |
| Hallucination Prevention (`skills/hallucination-prevention/SKILL.md`) | `com.jarvys.hallucination-prevention` | modificado | referencias a skills convertidas a ids Jarvys |
| Idea to Spec (`skills/idea-to-spec/SKILL.md`) | `com.jarvys.idea-to-spec` | modificado | referencias a skills convertidas a ids Jarvys |
| Incremental Coding (`skills/incremental-coding/SKILL.md`) | `com.jarvys.incremental-coding` | modificado | referencias a skills convertidas a ids Jarvys |
| Integration Testing (`skills/integration-testing/SKILL.md`) | `com.jarvys.integration-testing` | modificado | referencias a skills convertidas a ids Jarvys; enlace a checklist auxiliar no incluida retirado |
| Meeting Notes to Tasks (`skills/meeting-notes-to-tasks/SKILL.md`) | `com.jarvys.meeting-notes-to-tasks` | modificado | referencias a skills convertidas a ids Jarvys |
| Multi-Agent Orchestration (`skills/multi-agent-orchestration/SKILL.md`) | `com.jarvys.multi-agent-orchestration` | modificado | referencias a skills convertidas a ids Jarvys |
| Observability (`skills/observability/SKILL.md`) | `com.jarvys.observability` | modificado | referencias a skills convertidas a ids Jarvys |
| Performance Optimization (`skills/performance-optimization/SKILL.md`) | `com.jarvys.performance-optimization` | modificado | referencias a skills convertidas a ids Jarvys; enlace a checklist auxiliar no incluida retirado |
| Production Deployment (`skills/production-deployment/SKILL.md`) | `com.jarvys.production-deployment` | modificado | referencias a skills convertidas a ids Jarvys; enlace a checklist auxiliar no incluida retirado; deploy/rollback requieren autorización explícita y aprobaciones vigentes |
| Email Diplomat (`skills/productivity/email-diplomat.md`) | `com.jarvys.productivity-email-diplomat` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |
| Learning Path Generator (`skills/productivity/learning-path-generator.md`) | `com.jarvys.productivity-learning-path-generator` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |
| Meeting Summarizer (`skills/productivity/meeting-summarizer.md`) | `com.jarvys.productivity-meeting-summarizer` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |
| Prompt Injection Defense (`skills/prompt-injection-defense/SKILL.md`) | `com.jarvys.prompt-injection-defense` | modificado | referencias a skills convertidas a ids Jarvys |
| RAG and Memory (`skills/rag-and-memory/SKILL.md`) | `com.jarvys.rag-and-memory` | modificado | referencias a skills convertidas a ids Jarvys |
| Refactoring (`skills/refactoring/SKILL.md`) | `com.jarvys.refactoring` | modificado | referencias a skills convertidas a ids Jarvys |
| Research And Summarize (`skills/research-and-summarize/SKILL.md`) | `com.jarvys.research-and-summarize` | modificado | referencias a skills convertidas a ids Jarvys |
| Dependency Auditor (`skills/security/dependency-auditor.md`) | `com.jarvys.security-dependency-auditor` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant; límites de herramientas expresados condicionalmente |
| Security Headers Checker (`skills/security/security-headers-checker.md`) | `com.jarvys.security-security-headers-checker` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant; U+200B solo junto a fences; retirado; límites de herramientas expresados condicionalmente |
| Threat Modeler (`skills/security/threat-modeler.md`) | `com.jarvys.security-threat-modeler` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |
| Security Hardening (`skills/security-hardening/SKILL.md`) | `com.jarvys.security-hardening` | modificado | referencias a skills convertidas a ids Jarvys; enlace a checklist auxiliar no incluida retirado |
| Simplicity First (`skills/simplicity-first/SKILL.md`) | `com.jarvys.simplicity-first` | modificado | referencias a skills convertidas a ids Jarvys |
| Surgical Changes (`skills/surgical-changes/SKILL.md`) | `com.jarvys.surgical-changes` | modificado | referencias a skills convertidas a ids Jarvys |
| Task Decomposition (`skills/task-decomposition/SKILL.md`) | `com.jarvys.task-decomposition` | modificado | referencias a skills convertidas a ids Jarvys |
| Test-Driven Development (`skills/test-driven-development/SKILL.md`) | `com.jarvys.test-driven-development` | modificado | referencias a skills convertidas a ids Jarvys; enlace a checklist auxiliar no incluida retirado |
| Think Before Coding (`skills/think-before-coding/SKILL.md`) | `com.jarvys.think-before-coding` | modificado | referencias a skills convertidas a ids Jarvys |
| Changelog Author (`skills/writing/changelog-author.md`) | `com.jarvys.writing-changelog-author` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |
| README Generator (`skills/writing/readme-generator.md`) | `com.jarvys.writing-readme-generator` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |
| Technical Writer (`skills/writing/technical-writer.md`) | `com.jarvys.writing-technical-writer` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |

## Coste de metadata por turno

Medición del catálogo de assets, comparando `HEAD` con esta incorporación y usando el serializador de `CoreAgentRuntime.skillCatalog()` y el presupuesto estándar existente. Se excluye `com.jarvys.apk-factory` del chat principal porque `SkillScopePolicy` lo reserva para Coding. La estimación de tokens es caracteres/4; los tokenizadores de los proveedores pueden variar.

| Destino | Antes | Después | Incremento estimado |
|---|---:|---:|---:|
| Catálogo del chat principal/captain | 720 caracteres, 726 bytes (4 de 4 entradas) | 7.001 caracteres, 7.011 bytes (40 entradas: las 4 previas y 36 internas) | 6.281 caracteres, 6.285 bytes, ~1.571 tokens |
| Descripción de `read_skill` del chat principal, con IDs disponibles | 161 caracteres | 1.969 caracteres | 1.808 caracteres, ~452 tokens |
| `read_skill` en un bot/worker sin lista previa | sin herramienta | 1.877 caracteres en la descripción, 53 IDs internos | 2.116 caracteres contando la instrucción de uso/trust, ~529 tokens |
| `read_skill` del perfil Coding | 221 caracteres, con APK Factory | 2.029 caracteres en la descripción, con APK Factory y 53 IDs internos | 2.047 caracteres contando la instrucción de uso/trust, ~512 tokens |

Los workers de bots no reciben el catálogo descriptivo de `skillCatalog()`; reciben los IDs del `read_skill` declarado y cargan el cuerpo bajo demanda. El runtime ahora une las skills internas habilitadas por `SkillScopePolicy` a las seleccionadas por el perfil y declara `read_skill` cuando esa lista no está vacía, incluso si el perfil no lo había seleccionado. Las 53 skills quedan disponibles a todos los perfiles compatibles con su scope. El listado del chat principal mantiene su límite existente de 40 entradas y 10 KiB: 17 descripciones internas quedan fuera de ese catálogo, pero sus IDs siguen presentes en `read_skill`.

En el chat principal, catálogo y descripción de `read_skill` suman **8.089 caracteres adicionales** (~2.023 tokens estimados) por turno; en un worker nuevo, IDs y pauta de confianza suman **2.116 caracteres** (~529 tokens estimados). Son medidas del texto variable, sin la estructura fija del esquema JSON de herramientas.

El coste es significativo. Se eligió compactar solo la metadata interna omitiendo el nombre redundante y ordenar primero las entradas ya existentes. Así se conserva la metadata previa y no se añade otro tope ni cambia el contenido de las instrucciones; `read_skill` incluye el nombre completo en el encabezado del cuerpo cargado. Para el ajuste anterior, los 40 elementos habrían sido las primeras coincidencias alfabéticas y habrían desplazado `Skill Workshop`.

Los 53 `SKILL.md` nuevos pesan **208.144 bytes** (`wc -c`); el aviso MIT añade **1.094 bytes**, para **209.238 bytes de assets añadidos** antes de compresión APK. La comparación `FullDebug` sin firmar desde el mismo commit base da **46.599.460 → 46.712.448 bytes**, un crecimiento de **112.988 bytes** (110,3 KiB; 0,24 %), incluyendo assets comprimidos y los cambios de runtime.

## Registro de prepublicación

Antes de publicar, incluir **DevelopersGlobal/ai-agent-skills (MIT)** en la sección `Open-source licenses` de la UI junto con el aviso en `app/app/src/main/assets/licenses/ai-agent-skills-LICENSE.txt`. La lista documental vigente de reposición está en [inline-chat-blocks.md](inline-chat-blocks.md).
