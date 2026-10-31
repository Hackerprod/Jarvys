# Fresh reconstruction pre-write gate — pending

The exact seven classes in `BOT_PORTABILITY_P2_PREWRITE_CLASSES.json` are selected before execution, for both Full and Play on unchanged source. No result is claimed yet. This is a focused compatibility gate, not the complete implementation acceptance suite or a recovery of prior test counts.

- New native-SQLite schema compatibility tests cover all populated v1 tables and byte/type conservation, exact v1/v2 DDL, no automatic enrollment, cached readers/doctor/export, transactional interruption and real SQLITE_FULL, malformed schemas and reopen.
- Existing current Registry tests exercise old assignment/control/read contracts.
- Current checkpoint durability, incorporation codecs, continuation and coordination histories cover strict typed decode, atomic replacement, size/fsync faults and conservation.
- Generation receipts fence source identity across storage lifecycle.

Admission remains OFF. No app-open upgrade is enabled. A focused failure blocks the gate; broader fixture/compilation failures are reported separately, never relabeled PASS. Gate success authorizes only local implementation validation; all changed checkpoint/authority code requires corresponding new tests and final-source repetition before independent implementation PASS. New typed worker-wait/control source is still an uncompiled draft outside app source at this checkpoint.

Version2 is an additive extension of the current generation, not a legacy import or destructive repair. Existing rows are not deleted/backfilled. Old-binary downgrade after new writes is unsupported; keeping the v98 APK is not a rollback guarantee.
