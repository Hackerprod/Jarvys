# Retired: reference-only workspace result wake

This document described the pre-v1 wake implementation and is retained only as a historical record. That delivery path has been removed: result references and notices remain audit evidence, while a worker's actual content is stored as an attributed `AGENT` conversation input and admitted through the ordinary conversation turn pipeline.

The former `workspace_result_wake_pending`, `workspace_result_wake_started`, and `workspace_result_wake_acknowledged` rows are not read as model context or scheduled work. Historical rows are excluded from model and memory projections. No new wake marker is written, and `AgentForegroundService` has no reference-only wake action.

See [agent-messaging.md](agent-messaging.md) for the evidence, current architecture, delivery/acknowledgement rules, tests, and known limits.
