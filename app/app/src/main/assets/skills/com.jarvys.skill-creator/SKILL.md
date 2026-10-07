---
id: com.jarvys.skill-creator
name: Skill Workshop
description: Shape a repeatable request into a clear Jarvys skill, or revise and check an existing skill. Use when the user asks to create, refine, or validate reusable instructions.
version: 1
allowed-tools:
  - ls
  - read
  - write
  - edit
  - read_skill
tags: [skills, authoring]
---

# Skill Workshop

Turn a task the user wants to repeat into a compact set of instructions Jarvys can load as a skill. Keep the user's objective and voice; do not turn a one-off preference into a general rule.

## Gather the shape of the task

Use the current conversation and supplied materials to identify the intended outcome, inputs, constraints, quality bar, and useful tools. Draft when the request is sufficiently clear. Ask a focused question only when the answer would change the workflow or deliverable.

When revising, inspect the enabled skill with `read_skill` or read the relevant workspace files. Identify which directions must stay, which should change, and any supporting material the new version depends on. Preserve unrelated content.

## Compose a Jarvys skill

Store each skill in a directory named for its `id`, with its entrypoint at `SKILL.md`. Start with the supported YAML metadata and then give the model direct, actionable instructions. A small example of the format:

```markdown
---
id: com.example.field-notes
name: Field Notes
description: Turn an observation into a concise, organized field note. Use when the user asks to capture or clean up observations.
version: 1
allowed-tools:
  - read
tags: [notes, fieldwork]
---

# Field Notes

Group observations by place and time when that information is available. Keep
uncertainty visible, and do not add details that were not supplied.
```

Required metadata: `id`, `name`, `description`, and `version: 1`. IDs contain up to 128 letters, digits, dots, underscores, or dashes. Names are at most 120 characters; descriptions at most 600. Optional `allowed-tools` and `tags` are unique flat lists of at most 64 entries each. Avoid nested YAML, aliases, anchors, quoted escape syntax, and unrecognized keys. The document must have a non-empty body and stay below 256 KiB.

List only tools that exist in the current Jarvys run and matter to the task. `allowed-tools` is descriptive metadata; it does not grant capabilities or shrink the current run's tool access. The current run determines which tools are available. Skills have no shell: avoid instructions that depend on executable scripts, installed packages, or binary assets. Optional text references may be placed beside `SKILL.md` and read with a workspace path under `/skills/<id>/`.

## Put the result where it belongs

When `/skills/` is available, inspect the destination with `ls /skills/` and `read`. Use an exact directory/frontmatter ID match. For a new skill, create supporting material first and write the complete entrypoint last; for a revision, keep unrelated files and preserve the installed skill's enabled state. Jarvys validates the final entrypoint and refreshes the catalog. Read the saved file back before reporting completion.

If the skill area is unavailable but workspace writing works, save the draft under a clear relative path such as `skills/<id>/SKILL.md`. Read it back and describe the import step the user can take. If no writing location is available, return the entire draft in a Markdown fence and say it has not been installed.

For GitHub material, use Skills → ＋ → Import from GitHub and report the import result; do not imply success before Jarvys confirms it. After an installation, link the verified entrypoint as `[SKILL.md](jarvys://skills/<id>/SKILL.md)` with its actual ID.

## Check the result

Review metadata, frontmatter boundaries, body length, tool names, directory/ID match, and any referenced files. Make sure there are no placeholders or unsupported capabilities. Check one request that should invoke the skill and a nearby request that should not; distinguish checks actually performed from suggested follow-ups. End with a short description, the saved or draft location, and the verification performed.
