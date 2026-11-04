# P4A baseline regression source

This exact test source targets production baseline e0801e05caca2f4857ace19e1d7033cd8fe7a356. SHA-256: f856d8356141e9338e4d0bb1112e257ceef50e86ef018846496dfc8907640504. It is outside normal test sources because its argument-only preparation APIs are deliberately denied by the new native-owner contract.

Fresh Full host execution on 2026-11-04 confirmed both intended failures: option consumption survived failed USER insertion (consumed seq 2, no USER/submission, next sequence 2→3, repeat returned null); inline preparation reopened with one USER but no submission/recovery projection. No Play baseline execution occurred. An existing calibration test separately failed before its assertions in FreshStorageBootstrap; it is not a passing calibration or evidence of a product defect.

Replacement current-source acceptance retains the same invariants through actual displayed UI/receiver ownership: complete USER/marker/submission rollback, all-row/counter conservation and reopen, reselect after rollback, exact PREPARED recovery and one service/provider start. Original baseline assertions are preserved here unchanged, not weakened to accept the old behavior. Raw diagnostics remain outside the repository.
