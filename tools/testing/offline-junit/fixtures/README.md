# Targeted owner-lease two-process fixture

This source is deliberately **outside Gradle's test source roots**. It must not be discovered by a
normal test task, and missing explicit configuration fails rather than skips. The lead compiles this
single fixture against the exact exported Full test classpath into a new private auxiliary directory.
Create a derived export whose only classpath change is appending that directory; retain the original
export, compiler result, derived diff and source hashes for independent review. Do not change the
OfflineJUnit guard, add process/network permissions, or describe this derived fixture classpath as
identical to a normal Gradle test task.

The existing guarded OfflineJUnit runner selects the exact class
`com.jarvys.agent.coding.CodingOwnerLeaseProcessFixture`. Install the guard before loading the class.
The lead alone launches/terminates the separate local JVMs; the fixture never launches a subprocess.
Each endpoint uses a separate output directory to avoid overwriting XML.

Explicit environment configuration:

- `JARVYS_CODING_LEASE_FIXTURE_ROOT`: fresh, precreated private absolute directory without symlinks.
- `JARVYS_CODING_LEASE_FIXTURE_NONCE`: fresh canonical UUID for this protocol.
- `JARVYS_CODING_LEASE_FIXTURE_MODE`: `holder`, `probe-live` or `probe-dead`.

Clean-close scenario:

1. Start `holder`; its `nonce.ready` appears only after real owner lease + pin acquisition.
2. Start `probe-live`. It verifies ALIVE before and after the holder's internal same-process probe
   has closed its descriptor. This catches accidental OS lock release from another descriptor close.
3. Require probe exit 0 and `nonce.probed-after`; the lead writes `nonce.release` with the nonce bytes.
4. Require holder exit 0 and `nonce.closed`. Only after observing actual process exit, the lead writes
   `nonce.owner-exited` with nonce bytes. Run `probe-dead` and require exit 0.

Abrupt-exit scenario uses a **fresh root/nonce**, repeats steps 1–2, then the lead terminates its own
holder JVM without creating release. After observing terminal process exit, write owner-exited and
run probe-dead. Record expected holder termination separately, not as a passing holder test.

All marker producers, including the lead for `release` and `owner-exited`, publish complete bytes
using a temporary file in the same root followed by atomic rename. Do not expose a partially written
marker or silently fall back to non-atomic publication. These local coordination markers do not
claim fsync durability.

All waiting is bounded; a timeout, missing marker or bootstrap failure is not evidence of DEAD.
Keep the stable owner file. The unchanged sentinel proves only that this probe did not mutate that
file: it does not test manager recovery, journal terminal repair, no-replay execution, physical power
loss, device behavior or semantic-memory fences. Those require their own integration oracles.
