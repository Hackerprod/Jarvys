# Factory guidance factory-guidance-v78 / basics

Use with the always-loaded Factory core; this reference grants no tools or approvals.

# APK Factory
HTML/CSS/JS, not a fixed notes application.
Factory API26+/apps API24+.
## Contract
`apk_factory inspect`: schema/capabilities/limits/template/availability. Read scope/files; relative paths/Captain-reviewed adoption. Clarify audience/workflows/style/identity/data. New: unique ID/name/icon; update: same appId/key, higher versionCode. Preserve other apps. Web/spec cannot extend precompiled DEX/libraries/permissions/services/APIs; unsupported: template review.
## Capabilities
Offline WebView: no CDNs/remote fonts/scripts/API/login. Accessible text/targets; loading/empty/error/recovery.
Responsive/scrollable forms, viewport meta. Native bar/cutout/keyboard insets once; reachable inputs/visible bars.
Capabilities: storage/export/share/clipboard/haptics/device/documents/photos/audio/browser/maps/phone/email/sms/contacts/calendar/database/presentation. No arbitrary files/clipboard read/direct camera/microphone/location/Bluetooth/notifications/background/network/billing. JSON grants no permissions/code.
Await/catch; no user/credential logs/preview native-effect claims.
Load `<script src="/factory-sdk.js"></script>` before local JS at https://app.jarvys.invalid; no inline scripts/handlers or SDK replacement/weakened origin/frame checks.
window.Jarvys frozen Promises:
- runtime.info(): metadata, no grant.
- storage.get(key): string/null; set(key,value): strings; remove(key): delete; list(): keys. Serialize structures.
- export.text({filename,text,mimeType}): optional MIME: text/plain, text/markdown, application/json, text/csv. {saved:true} confirms save; cancel rejects.
- share.text({text,title}): optional title; {chooserOpened:true} proves no delivery.
- clipboard.write(text): native confirmation; no read.
- haptics.perform(kind): tap/longPress; returns performed.
- device.info(): platform/apiLevel/appId/targetSdk; no stable personal IDs.
Version storage; handle missing/invalid/upgraded data; no startup clears/false save success. Human export/share; cancel≠delivery.
