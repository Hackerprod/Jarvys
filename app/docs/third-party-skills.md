# Skills de terceros: DevelopersGlobal/ai-agent-skills

Fuente: [DevelopersGlobal/ai-agent-skills](https://github.com/DevelopersGlobal/ai-agent-skills), carpeta `skills/`.

Commit upstream exacto: `f47f948a577e8c901193da92c12899ff4322f855`.

Licencia: MIT. Titular del aviso upstream: **Copyright (c) 2026 DevelopersGlobal**. El aviso y el texto íntegro de la licencia se conservan en [ai-agent-skills-LICENSE.txt](../app/src/main/assets/licenses/ai-agent-skills-LICENSE.txt).

Se incluyeron **31 skills `SKILL.md` y 22 Markdown categóricos (53 en total)**. No se excluyó ninguno tras el audit.

## Cambios de conversión y alcance

- Cada skill usa frontmatter Jarvys permitido: `id`, `name`, `description`, `version: 1` y `tags`; el directorio coincide con `id`. Todos incluyen la etiqueta `internal`, sin añadir claves al parser ni habilitar herramientas inexistentes.
- Se conservaron los cuerpos. Se sustituyó el frontmatter upstream (incluidos `category`, `applies-to` y `version` upstream); los ficheros categóricos perdieron su tabla de plataformas (`Works With`) y los ejemplos atribuidos a Claude se atribuyeron al asistente.
- Los enlaces relativos entre skills apuntan a ids Jarvys. No se empaquetaron `agents/` ni `references/`: ningún skill necesita un agente de referencia y los cuatro checklists referenciados son material auxiliar; las instrucciones del skill siguen completas sin ellos. Se retiraron esas referencias a archivos que no se distribuyen.
- Se quitaron 22 caracteres U+200B en ocho ficheros categóricos; estaban únicamente junto a fences para proteger ejemplos Markdown anidados y no contenían instrucciones. No se detectaron comentarios HTML.

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
| API Designer (`skills/developer/api-designer.md`) | `com.jarvys.developer-api-designer` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant; U+200B solo junto a fences; retirado |
| Bug Hunter (`skills/developer/bug-hunter.md`) | `com.jarvys.developer-bug-hunter` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant; U+200B solo junto a fences; retirado |
| Code Reviewer (`skills/developer/code-reviewer.md`) | `com.jarvys.developer-code-reviewer` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |
| Git Commit Crafter (`skills/developer/git-commit-crafter.md`) | `com.jarvys.developer-git-commit-crafter` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |
| Regex Wizard (`skills/developer/regex-wizard.md`) | `com.jarvys.developer-regex-wizard` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |
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
| Production Deployment (`skills/production-deployment/SKILL.md`) | `com.jarvys.production-deployment` | modificado | referencias a skills convertidas a ids Jarvys; enlace a checklist auxiliar no incluida retirado |
| Email Diplomat (`skills/productivity/email-diplomat.md`) | `com.jarvys.productivity-email-diplomat` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |
| Learning Path Generator (`skills/productivity/learning-path-generator.md`) | `com.jarvys.productivity-learning-path-generator` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |
| Meeting Summarizer (`skills/productivity/meeting-summarizer.md`) | `com.jarvys.productivity-meeting-summarizer` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |
| Prompt Injection Defense (`skills/prompt-injection-defense/SKILL.md`) | `com.jarvys.prompt-injection-defense` | modificado | referencias a skills convertidas a ids Jarvys |
| RAG and Memory (`skills/rag-and-memory/SKILL.md`) | `com.jarvys.rag-and-memory` | modificado | referencias a skills convertidas a ids Jarvys |
| Refactoring (`skills/refactoring/SKILL.md`) | `com.jarvys.refactoring` | modificado | referencias a skills convertidas a ids Jarvys |
| Research And Summarize (`skills/research-and-summarize/SKILL.md`) | `com.jarvys.research-and-summarize` | modificado | referencias a skills convertidas a ids Jarvys |
| Dependency Auditor (`skills/security/dependency-auditor.md`) | `com.jarvys.security-dependency-auditor` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant |
| Security Headers Checker (`skills/security/security-headers-checker.md`) | `com.jarvys.security-security-headers-checker` | modificado | metadata de plataforma retirado; ejemplos etiquetados como Assistant; U+200B solo junto a fences; retirado |
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

Medición source-level del texto variable de descripciones y enumeraciones. Los deltas comparan el árbol inicial `5bcad55` con `343d9e2`, y la corrección final con ese mismo baseline. No incluyen el esquema fijo JSON de cada herramienta ni skills importadas del usuario.

| Componente | `5bcad55` vs. `343d9e2` | Corrección final vs. `343d9e2` |
|---|---:|---:|
| Metadata del catálogo del chat principal | +6.285 caracteres | 0 |
| IDs de `read_skill` en el chat principal | +1.808 caracteres | +1.808 caracteres |
| IDs de `delegate_subtask` | +1.808 caracteres | 0 |
| Enum `skill_ids` de `create_bot` | +1.861 caracteres | 0 |
| Encabezado del catálogo | -13 caracteres | -13 caracteres |
| **Total chat principal** | **+11.749 caracteres** | **+1.795 caracteres** |
| IDs de `read_skill` por worker de Crew | +1.808 caracteres | +1.808 caracteres |

La corrección deja las descripciones de las 53 skills internas fuera del catálogo del chat. El catálogo mantiene sus presupuestos existentes de 40 entradas y 10 KiB; `read_skill` conserva los IDs internos en su descripción para que el modelo los pueda cargar. La enumeración de `delegate_subtask` y el enum de `create_bot` omiten esos IDs, y los hijos reciben las skills internas mediante el tag y el scope de ejecución.

Cada worker de Crew recibe los IDs internos en la descripción de `read_skill`; no recibe el catálogo de metadatos del chat principal. Las tareas programadas conservan los IDs en su scope persistido porque el procesador los usa para reconstruir `read_skill` y la búsqueda de skills; no se enumeran en el descriptor de `schedule_task`.

El cambio neto esperado es aproximadamente **+1,8k caracteres por turno del chat principal** y **+1,8k por worker de Crew** frente a `343d9e2`, sin cambiar límites numéricos existentes.

Los 53 `SKILL.md` nuevos pesan **207.296 bytes** (`wc -c`); el aviso MIT añade **1.094 bytes**, para **208.390 bytes de assets añadidos** antes de compresión APK.

## Registro de prepublicación

Antes de publicar, incluir **DevelopersGlobal/ai-agent-skills (MIT)** en la sección `Open-source licenses` de la UI junto con el aviso en `app/app/src/main/assets/licenses/ai-agent-skills-LICENSE.txt`. La lista documental vigente de reposición está en [inline-chat-blocks.md](inline-chat-blocks.md).
