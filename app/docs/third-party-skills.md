# Skills de terceros: DevelopersGlobal/ai-agent-skills

Fuente: [DevelopersGlobal/ai-agent-skills](https://github.com/DevelopersGlobal/ai-agent-skills), carpeta `skills/`.

Commit upstream exacto: `f47f948a577e8c901193da92c12899ff4322f855`.

Licencia: MIT. Titular del aviso upstream: **Copyright (c) 2026 DevelopersGlobal**. El aviso y el texto íntegro de la licencia se conservan en [ai-agent-skills-LICENSE.txt](../app/src/main/assets/licenses/ai-agent-skills-LICENSE.txt).

Se incluyeron **31 skills `SKILL.md` y 22 Markdown categóricos (53 en total)**. No se excluyó ninguno tras el audit.

## Cambios de conversión y alcance

- Cada skill usa frontmatter Jarvys permitido: `id`, `name`, `description`, `version: 1` y `tags`; el directorio coincide con `id`. Todos incluyen la etiqueta `internal`, sin añadir claves al parser ni habilitar herramientas inexistentes.
- Los cuerpos se compararon con los 53 archivos del commit citado y conservan todo el texto upstream, incluidas las 22 tablas `Metadata` de los ficheros categóricos y las seis líneas originales de `Known Limitations` restauradas en API Designer, Bug Hunter, Code Reviewer, Regex Wizard, Dependency Auditor y Security Headers Checker. No se eliminó contenido instructivo. Se quitaron 22 caracteres U+200B en ocho archivos, todos junto a fences Markdown; siete entradas de lista y sus enlaces a cinco checklists no empaquetados; y las 44 URLs de perfil del autor, conservando el handle como atribución. El enlace al ejemplo no distribuido `docs/migration-v2.md` se convirtió a texto conservando “migration guide”.
- Los cambios documentados de cuerpo son: una cita de procedencia añadida en cada archivo; 60 enlaces entre skills convertidos a ids Jarvys en 31 archivos; ejemplos de Claude atribuidos a Assistant (22 etiquetas y tres frases); y las eliminaciones de rutas no empaquetadas descritas arriba. Se restauraron seis apariciones de URLs externas upstream que se habían perdido: `skills/documentation/SKILL.md` (`https://github.com/joelparkerhenderson/architecture-decision-record`), `skills/git-workflow/SKILL.md` (`https://www.conventionalcommits.org/`), `skills/hallucination-prevention/SKILL.md` y `skills/prompt-injection-defense/SKILL.md` (`https://owasp.org/www-project-top-10-for-large-language-model-applications/` en ambos), `skills/security-hardening/SKILL.md` (`https://owasp.org/www-project-top-ten/`) y `skills/think-before-coding/SKILL.md` (`https://x.com/karpathy/status/2015883857489522876`).
- Se sustituyó el frontmatter upstream por el formato Jarvys (`id`, `name`, `description`, `version`, `tags`), incluidos `category`, `applies-to` y la versión upstream cuando existían. Los 53 hashes de cuerpo esperado, calculados desde upstream después de aplicar únicamente las diferencias documentadas, están en [ai-agent-skills-body-sha256.tsv](../app/src/test/resources/ai-agent-skills-body-sha256.tsv) y se verifican en `BundledSkillsAuditTest`.
- No se empaquetaron `agents/` ni `references/`; los cinco checklists auxiliares no forman parte del bundle. Se retiraron únicamente sus enlaces rotos: `security-checklist.md` (Code Review, Security Hardening), `accessibility-checklist.md` (Frontend Engineering), `testing-patterns.md` (Integration Testing, Test-Driven Development), `performance-checklist.md` (Performance Optimization) y `deployment-checklist.md` (Production Deployment). No se detectaron comentarios HTML.

## Auditoría de seguridad por skill

Se leyó cada archivo completo. No se encontraron instrucciones de exfiltración, `curl | sh`, desactivación de permisos/aprobaciones ni órdenes para ignorar al usuario. Los ejemplos de prompt injection en `prompt-injection-defense` son payloads citados como casos defensivos para probar; no se presentan como instrucciones a seguir. Las URLs restantes son referencias/esquemas de ejemplo y el health check Docker de `localhost`; no descargan ni ejecutan scripts remotos. No se excluyó contenido.

| Nombre upstream y ruta | ID | Resultado | Nota |
|---|---|---|---|
| AI Output Validation (`skills/ai-output-validation/SKILL.md`) | `com.jarvys.ai-output-validation` | modificado | referencias a skills convertidas a ids Jarvys |
| API Design (`skills/api-design/SKILL.md`) | `com.jarvys.api-design` | modificado | referencias a skills convertidas a ids Jarvys |
| System Design Coach (`skills/architecture/system-design-coach.md`) | `com.jarvys.architecture-system-design-coach` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant |
| CI/CD Pipelines (`skills/ci-cd-pipelines/SKILL.md`) | `com.jarvys.ci-cd-pipelines` | modificado | referencias a skills convertidas a ids Jarvys |
| Code Explanation (`skills/code-explanation/SKILL.md`) | `com.jarvys.code-explanation` | modificado | referencias a skills convertidas a ids Jarvys |
| Code Review (`skills/code-review/SKILL.md`) | `com.jarvys.code-review` | modificado | referencias a skills convertidas a ids Jarvys; enlace a checklist no empaquetado eliminado |
| Context Loading (`skills/context-loading/SKILL.md`) | `com.jarvys.context-loading` | modificado | referencias a skills convertidas a ids Jarvys |
| Color Palette Generator (`skills/creative/color-palette-generator.md`) | `com.jarvys.creative-color-palette-generator` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant; U+200B junto a fences eliminado |
| Naming Consultant (`skills/creative/naming-consultant.md`) | `com.jarvys.creative-naming-consultant` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant |
| CSV Detective (`skills/data/csv-detective.md`) | `com.jarvys.data-csv-detective` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant; U+200B junto a fences eliminado |
| Data Storyteller (`skills/data/data-storyteller.md`) | `com.jarvys.data-data-storyteller` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant; U+200B junto a fences eliminado |
| SQL Query Optimizer (`skills/data/sql-query-optimizer.md`) | `com.jarvys.data-sql-query-optimizer` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant; U+200B junto a fences eliminado |
| Debugging Methodology (`skills/debugging-methodology/SKILL.md`) | `com.jarvys.debugging-methodology` | modificado | referencias a skills convertidas a ids Jarvys |
| API Designer (`skills/developer/api-designer.md`) | `com.jarvys.developer-api-designer` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant; U+200B junto a fences eliminado |
| Bug Hunter (`skills/developer/bug-hunter.md`) | `com.jarvys.developer-bug-hunter` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant; U+200B junto a fences eliminado |
| Code Reviewer (`skills/developer/code-reviewer.md`) | `com.jarvys.developer-code-reviewer` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant |
| Git Commit Crafter (`skills/developer/git-commit-crafter.md`) | `com.jarvys.developer-git-commit-crafter` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant |
| Regex Wizard (`skills/developer/regex-wizard.md`) | `com.jarvys.developer-regex-wizard` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant |
| CI/CD Pipeline Builder (`skills/devops/cicd-pipeline-builder.md`) | `com.jarvys.devops-cicd-pipeline-builder` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant |
| Dockerfile Optimizer (`skills/devops/dockerfile-optimizer.md`) | `com.jarvys.devops-dockerfile-optimizer` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant; U+200B junto a fences eliminado |
| Documentation (`skills/documentation/SKILL.md`) | `com.jarvys.documentation` | modificado | referencias a skills convertidas a ids Jarvys |
| Error Handling (`skills/error-handling/SKILL.md`) | `com.jarvys.error-handling` | modificado | referencias a skills convertidas a ids Jarvys |
| Frontend Engineering (`skills/frontend-engineering/SKILL.md`) | `com.jarvys.frontend-engineering` | modificado | referencias a skills convertidas a ids Jarvys; enlace a checklist no empaquetado eliminado |
| Git Workflow (`skills/git-workflow/SKILL.md`) | `com.jarvys.git-workflow` | modificado | referencias a skills convertidas a ids Jarvys |
| Goal-Driven Execution (`skills/goal-driven-execution/SKILL.md`) | `com.jarvys.goal-driven-execution` | modificado | referencias a skills convertidas a ids Jarvys |
| Hallucination Prevention (`skills/hallucination-prevention/SKILL.md`) | `com.jarvys.hallucination-prevention` | modificado | referencias a skills convertidas a ids Jarvys |
| Idea to Spec (`skills/idea-to-spec/SKILL.md`) | `com.jarvys.idea-to-spec` | modificado | referencias a skills convertidas a ids Jarvys |
| Incremental Coding (`skills/incremental-coding/SKILL.md`) | `com.jarvys.incremental-coding` | modificado | referencias a skills convertidas a ids Jarvys |
| Integration Testing (`skills/integration-testing/SKILL.md`) | `com.jarvys.integration-testing` | modificado | referencias a skills convertidas a ids Jarvys; enlace a checklist no empaquetado eliminado |
| Meeting Notes to Tasks (`skills/meeting-notes-to-tasks/SKILL.md`) | `com.jarvys.meeting-notes-to-tasks` | modificado | referencias a skills convertidas a ids Jarvys |
| Multi-Agent Orchestration (`skills/multi-agent-orchestration/SKILL.md`) | `com.jarvys.multi-agent-orchestration` | modificado | referencias a skills convertidas a ids Jarvys |
| Observability (`skills/observability/SKILL.md`) | `com.jarvys.observability` | modificado | referencias a skills convertidas a ids Jarvys |
| Performance Optimization (`skills/performance-optimization/SKILL.md`) | `com.jarvys.performance-optimization` | modificado | referencias a skills convertidas a ids Jarvys; enlace a checklist no empaquetado eliminado |
| Production Deployment (`skills/production-deployment/SKILL.md`) | `com.jarvys.production-deployment` | modificado | referencias a skills convertidas a ids Jarvys; enlace a checklist no empaquetado eliminado |
| Email Diplomat (`skills/productivity/email-diplomat.md`) | `com.jarvys.productivity-email-diplomat` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant |
| Learning Path Generator (`skills/productivity/learning-path-generator.md`) | `com.jarvys.productivity-learning-path-generator` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant |
| Meeting Summarizer (`skills/productivity/meeting-summarizer.md`) | `com.jarvys.productivity-meeting-summarizer` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant |
| Prompt Injection Defense (`skills/prompt-injection-defense/SKILL.md`) | `com.jarvys.prompt-injection-defense` | modificado | referencias a skills convertidas a ids Jarvys |
| RAG and Memory (`skills/rag-and-memory/SKILL.md`) | `com.jarvys.rag-and-memory` | modificado | referencias a skills convertidas a ids Jarvys |
| Refactoring (`skills/refactoring/SKILL.md`) | `com.jarvys.refactoring` | modificado | referencias a skills convertidas a ids Jarvys |
| Research And Summarize (`skills/research-and-summarize/SKILL.md`) | `com.jarvys.research-and-summarize` | modificado | referencias a skills convertidas a ids Jarvys |
| Dependency Auditor (`skills/security/dependency-auditor.md`) | `com.jarvys.security-dependency-auditor` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant |
| Security Headers Checker (`skills/security/security-headers-checker.md`) | `com.jarvys.security-security-headers-checker` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant; U+200B junto a fences eliminado |
| Threat Modeler (`skills/security/threat-modeler.md`) | `com.jarvys.security-threat-modeler` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant |
| Security Hardening (`skills/security-hardening/SKILL.md`) | `com.jarvys.security-hardening` | modificado | referencias a skills convertidas a ids Jarvys; enlace a checklist no empaquetado eliminado |
| Simplicity First (`skills/simplicity-first/SKILL.md`) | `com.jarvys.simplicity-first` | modificado | referencias a skills convertidas a ids Jarvys |
| Surgical Changes (`skills/surgical-changes/SKILL.md`) | `com.jarvys.surgical-changes` | modificado | referencias a skills convertidas a ids Jarvys |
| Task Decomposition (`skills/task-decomposition/SKILL.md`) | `com.jarvys.task-decomposition` | modificado | referencias a skills convertidas a ids Jarvys |
| Test-Driven Development (`skills/test-driven-development/SKILL.md`) | `com.jarvys.test-driven-development` | modificado | referencias a skills convertidas a ids Jarvys; enlace a checklist no empaquetado eliminado |
| Think Before Coding (`skills/think-before-coding/SKILL.md`) | `com.jarvys.think-before-coding` | modificado | referencias a skills convertidas a ids Jarvys |
| Changelog Author (`skills/writing/changelog-author.md`) | `com.jarvys.writing-changelog-author` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant |
| README Generator (`skills/writing/readme-generator.md`) | `com.jarvys.writing-readme-generator` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant |
| Technical Writer (`skills/writing/technical-writer.md`) | `com.jarvys.writing-technical-writer` | modificado | tabla Metadata original conservada; ejemplos etiquetados como Assistant |

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

Los 53 `SKILL.md` nuevos pesan **214.027 bytes** (`wc -c`); el aviso MIT añade **1.094 bytes**, para **215.121 bytes de assets añadidos** antes de compresión APK.

## Registro de prepublicación

Antes de publicar, incluir **DevelopersGlobal/ai-agent-skills (MIT)** en la sección `Open-source licenses` de la UI junto con el aviso en `app/app/src/main/assets/licenses/ai-agent-skills-LICENSE.txt`. La lista documental vigente de reposición está en [inline-chat-blocks.md](inline-chat-blocks.md).
