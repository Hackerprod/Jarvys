# v69 / 1.2.62-FACTORY-PHOTOS

Status: implementation checkpoint, host review and validation in progress. Not a release receipt.

Photos are a separate declared capability requiring documents. The exact latest signed APK
and unique compatible host are authenticated. Only native human actions launch a trusted
system picker or camera and return the photo. Cancellation/restart retains durable automation
protection and explicit uncertain-outcome recovery. Existing v63–v68 behavior is preserved.

Capture uses an exact one-use write-only URI backed by a bounded reliable pipe, no filesystem
path and no broad camera/gallery permission. Requires RESULT_OK plus clean EOF. Cameras
requiring seek/reopen are unsupported; there is no thumbnail fallback. Selected/captured input
is restricted to JPEG/PNG, 8 MiB, 4096 pixels per side and 12 megapixels, with bounded decoded
allocation. Original metadata remains. No automatic sending or Jarvys gallery publication.

A reverse native FileShareTransfer transports immutable chunks into page-scoped document
read handles. Preview is unavailable. URI/path/Binder authority is never exposed to JavaScript.
Physical camera/picker/Binder interoperability, grants, installation and data preservation
remain unverified. Other F1/F2/F3 families, gated UX34 phases and UX43-last remain unchanged.

Runtime initial validation passed 232 core tests, 69 APK-runtime tests, 13 Node SDK tests.
Host tests, aggregate, exact lint comparison, final three-APK audit and separate existing-D7
signing/native delivery are pending. Detailed host evidence stays outside this repository.
