# v68 / 1.2.61-FACTORY-FILES

Status: implementation in progress; independent design review approved.

Bounded `share.file` consumes an untouched read handle, maximum 8 MiB, into an immutable
snapshot. A native 32 KiB Binder protocol transfers it to the exactly authenticated
Jarvys host. Both documents/share capabilities are mandatory. Human-only native review,
durable document/share admission and a narrow expiring read-only FileProvider protect
the chooser. No raw JS paths/URIs, persistent grants, generated providers or permissions.

Chooser opened is distinct from delivery. Native human closure/recovery acknowledges
closing external tasks but cannot verify OS closure or recall copies/open descriptors.

Source/release security review, full/focused test evidence, exact lint comparison,
three actual unsigned APK audits, existing D7 signing and native delivery are pending.
No real device, user document or third-party share has been used. Other F1/F2/F3 and
UX34 gated phases remain closed; UX43 remains last.
