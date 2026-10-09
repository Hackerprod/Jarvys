# Continuous mission lifetime (UX38)

Jarvys has no application-wide elapsed-time deadline for a normal agent run.
The former 15-minute preference and its settings control are retired. Restored,
custom, zero and incorrectly typed `agent_timeout_seconds` values are removed
without reading or applying them. Advancing a clock does not request cancellation.

## Independent operations and honest recovery

- Explicit STOP still closes the active action gate, interrupts the captain and
  live Crew workers, cancels owned processes, and waits for process cleanup before
  releasing project ownership. An already-started effect is never silently retried.
- HTTP connect/read bounds, bounded process execution, resource/context budgets,
  and no-progress detection remain. They are not mission wall-clock deadlines.
  A command result such as TIMED_OUT remains a tool observation for the agent to
  inspect and recover from; it does not itself expire the captain or other bots.
- A provider socket/IO timeout without actual cancellation is a transport failure,
  not a fabricated user STOP. A recoverable provider failure (transport, HTTP 408,
  429 without an existing rate-limit waiter, or 5xx) retains a typed PARTIAL result.
  This also covers provider failures while the existing compactor requests a
  summary. No timeout retries or context-maintenance feature are introduced.
- A partial provider turn does not replay a request, mark a worker DONE, or cancel
  independent work. The affected worker waits for its already-started owned jobs
  to settle, then exposes PARTIAL for explicit continuation/reconciliation.
  This is a recoverable pause; it is not a promise of automatic remote resumption.
- Existing Proactive and scheduled read-only processors retain their typed transient
  retry classification through an in-memory cause, without changing their capability
  allowlists or retry/delivery policy. Interactive and Crew paths do not use that
  opt-in classification hook and do not automatically replay uncertain actions.
- Existing memory reflection treats every non-completed loop outcome as interrupted
  work: no revisions means rolled back; retained revisions mean partial, never ready.
  It preserves existing revision receipts. No direct write replay is introduced;
  existing reflection scheduling/backoff remains unchanged.
- Late owned-job receipts remain evidence for the same worker generation. A
  stopped/partial worker can retain a receipt without restarting inference.
  Successful DONE cycles retain the existing receipt-driven follow-up behavior.
- Mission presentation preserves PARTIAL, STOPPED, actual FAILED and explicit
  deadline outcomes separately. Independent running bots remain visibly active
  after a partial captain turn. A prior partial mission is not rewritten by STOP
  of unrelated later work.

## Language and persistence

Runtime-owned timeout, no-progress and provider-interruption messages are selected
through the current application language. English and Spanish resources feed the
chat and mission records. Model output and internal English instructions are not
translated. No canonical conversation history is purged or reinterpreted.

Existing durable receipts/checkpoints and process-death interruption handling
remain authoritative. Versioned project bots use explicit checkpoint reconciliation;
legacy missions are not automatically relaunched. No operation is replayed simply
because a process was interrupted or a final receipt is missing.

## Boundaries

Android may suspend or kill the app, revoke background execution, lose connectivity,
or restart the device. Removing an elapsed timer cannot guarantee uninterrupted
execution for days. Host virtual-clock and synthetic transport tests establish
code behavior, not physical-device uptime or real-provider acceptance.

The existing low-level deadline cancellation signal remains for explicitly bounded
callers/tests; UX38 does not add a natural-language mission-deadline scheduler.
UX34 phases 1–4 remain gated. Provider request payloads, diagnostic response metadata,
and context selection remain unchanged.
