# Factory guidance factory-guidance-v86 / documents-media

## Sequential document handles
documents: retained artifact/pinned host, no raw paths/URIs/persistent grants. Two hosts block broker choice. ALLOW_ALWAYS opens Android picker; ASK once; DENY blocks. No second Use.
- documents.open({mimeType}) → {handle,mode:"read",expiresAfterMs,maximumBytes,providerCommitConfirmed:false}.
- documents.create({filename,mimeType}) → same with mode:"write".
- documents.read({handle,offset,length}) → {data,offset,nextOffset,eof}. data canonical padded standard Base64, no data URL.
- documents.write({handle,offset,data}) → {offset,nextOffset,bytesWritten,providerCommitConfirmed:false}.
- documents.close({handle}) → {status:"close_requested",providerCommitConfirmed:false}.
- documents.cancel({})/no args → {cancelled:true,rollbackConfirmed:false,pickerMayRemainOpen}.
MIME≤127chars, one type/no parameters; open allows */* or image/*, create concrete. Filename [a-zA-Z0-9][a-zA-Z0-9 _.-]{0,119}, no .. or final dot. Chunk1..32768 decoded bytes, offset integer0 then exact nextOffset. No seek/parallel reads/writes. Empty EOF permitted; short read is not EOF. Shrink final chunk to quota; finally close and show failures.

Signed resources.documents defaults/maxima: openHandles4/16,handleBytes16MiB/4GiB,sessionBytes32MiB/16GiB,leaseMs300000/86400000,handleBytes≤sessionBytes. Fixed128 process slots and32KiB chunks remain; totals use long offsets, not whole-file allocation. Expiry/session/foreground/revocation apply even if configured byte budget remains. Close/cancel does not refund cumulative byte use; unconfirmed cleanup retains slots. Handles cannot survive page replacement/process death or be persisted as future grants.

Blocked provider cleanup retains ownership. Partial writes/files and remote copies possible; close_requested proves no commit/upload. Settings recovery preserves uncertainty, no closure/authority proof.

## Sharing, photos and playback
share.file({handle,filename,mimeType}): documents+share,untouched read handle,concrete MIME/filename grammar; no JS path/URI/target. Consumes into immutable private disk snapshot with32KiB chunks. Signed resources.fileShare defaults/maxima: sourceBytes8MiB/4GiB,transferTimeoutMs300000/86400000,leaseMs300000/86400000,all minima1. Document cumulative quotas also bind; EOF probe needs quota headroom. Authenticated v1 Int transport retained≤8MiB; larger snapshots use v2 Long size/offset. Host checks signed budgets/source/UID/certificate/policy before pulling; final share grant binds content/hash and ASK once. Read-only authenticated policy preflight blocks saved DENY before snapshot reads; it grants nothing and adds no human prompt. Final effect rechecks live policy. Read-only recipient URI expires by signed lease. {chooserOpened,deliveryConfirmed:false} dispatch only,no closure acknowledgment. Timeout/cancel blocks new reads,retains admission through blocked I/O/cleanup,no replay or recall of copies/open FDs.

photos.pick({})/photos.capture({}) need photos+documents; same saved policy and native selection without second Use. Returns document read handle;8MiB JPEG/PNG,≤4096px/side and12MP,5min; acquisition charges cumulative quota. Metadata/location can remain. Camera uses bounded write-only pipe, no seek/reopen/thumbnail fallback/gallery save/send; camera may retain copies. API24–29 provider FDs must be regular. Interrupted UI/recovery preserves unknown outcomes.

audio.play({handle}): audio+documents,untouched read handle consumed into immutable private disk snapshot,32KiB streaming (tiny≤32KiB PCM fast path). Canonical44-byte PCM16 WAV only,mono/stereo,8–48kHz,whole frames; no compressed codecs/URI/loop/background. Signed resources.audio defaults/maxima: sourceBytes6MiB/4GiB,durationMs30000/268435454,setupTimeoutMs300000/86400000. v1 Int transport≤6MiB,v2 Long above. Bounds are format/budget ceilings,no guaranteed disk/duration; document quotas/lease+charged EOF probe still bind. Host validates bytes,ALLOW_ALWAYS plays,ASK once,no extra Play. Receipt playbackAttempted/audibilityConfirmed:false proves no hearing. Focus/lifecycle/route/revocation requests stop; failed cleanup remains blocked,no physical-stop proof. documents.cancel requests revocation,no rollback. SDK441245454ms outer wait covers two max setup phases+maxduration+10s,never extends native authority.

## Backups
Read basics/database: selected backups need documents+family and explicit dataBackup.keys/tables (default empty disables). Frozen1MiB plaintext, untouched application/json import handles, Android export destination, no combined transaction. Policy/unknown-commit/provider constraints apply; UNAVAILABLE in preview.
