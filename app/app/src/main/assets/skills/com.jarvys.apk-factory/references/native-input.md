# Factory guidance factory-guidance-v86 / native-input

## Shared contract
Installed current page/declared capability/live policy; preview UNAVAILABLE. Basics authority/uncertainty rules apply; no hardware,zero-egress or packaged acceptance claim.

## Voice recognition
voiceRecognition.status({}) → availability/discovery/busy/pendingActivity/error, no microphone probe. voiceRecognition.recognize({language?,languageModel?,prompt?,maxResults?,preferOffline?,timeoutMs?}) launches Android recognition Activity. Defaults empty language/prompt, free_form,5,false,120000ms. language valid BCP47≤64bytes; model free_form|web_search; prompt≤1024bytes; maxResults integer1..32; timeout1..600000. Options≤8192bytes,valid Unicode; ASK once.

Result status:recognized,results,confidenceScores,requestedLanguage,requestedLanguageModel,preferOfflineRequested,completionMeans:recognizer_reported_results. Disclosures mode:installed,simulated:false,engineSelection:android_system_activity,engineVerified:false,networkMayBeUsed:true,networkUseVerified:false,offlineGuaranteed:false,accuracyVerified:false,audioReturned:false. No truncation/fake confidence. preferOffline is a hint; external recognizer may transmit audio.
voiceRecognition.cancel({}) reports cancel_requested,cancelled,pendingActivity,externalActivityClosedVerified:false,transmittedAudioCannotBeRecalled:true. No closure/deletion proof; late results suppressed.

## Foreground location
location.status({}) discovers provider/permission/foreground/cleanup state. location.requestPermission({precision?}) defaults approximate; precise requests actual fine+coarse OS flow, user may grant coarse only. Rechecks grant; no settings/radio enabling.
location.current({provider?,timeoutMs?,maxAgeMs?,maxAccuracyMeters?,allowMock?}) defaults auto,15000,0,0,true. provider auto|gps|network; timeout1000..120000; maxAge0..300000; finite accuracy0..100000 (0 imposes no bound); explicit Boolean allowMock. Auto respects actual providers/grants; no IP-derived GPS. Cached fixes meet age; no background/geofencing.
Result status:"located",latitude,longitude,accuracyMeters,provider,timestamp,ageMs,mock,permissionGranularity,acquiredBeforeRequest,foregroundOnly:true; optional altitudeMeters,speedMetersPerSecond,bearingDegrees when available. Epoch timestamp/monotonic age; no accuracy guarantee. location.cancel({}) returns cancelled/cleanupPending/error; cleanup_failed is not success. Lifecycle loss unregisters; failed cleanup retains ownership, no physical stop proof.

## Foreground PCM recording
recording.status({}) discovers real microphone feature, RECORD_AUDIO grant and helper state. recording.requestPermission({}) uses actual OS grant. recording.record({durationMs?,maxBytes?,format?,sampleRate?,channels?}) requires recording+documents. Defaults10000ms,2MiB,wav-pcm16,16000Hz,1channel. Limits100..3600000ms,48bytes..512MiB,8000..48000Hz,1|2 channels; signed document handle/session/lease quotas can bind earlier. Unsupported hardware rejects.

Fixed-buffer private-cache canonical44-byte PCM16 WAV; result opaque read handle plus format,mimeType,bytes,durationMs,elapsedMs,sampleRate,channels,reason(duration|byte_limit|stopped),simulated:false,contentVerified:false,networkUsed:false. No raw path/URI/full-array output or audibility proof. recording.stop({}) finalizes captured frames; empty → RECORDING_EMPTY. recording.cancel({}) discards owned capture; no renewed grant. Failed cleanup remains busy; no physical-stop guarantee.

Lifecycle loss cancels; no cross-page delivery. Crash cache files may remain; no secure erasure. Playback uses independent signed audio size/duration/setup budgets and streaming WAV validation; see documents-media. No automatic upload/background.

## App-private secret vault
Keystore-encrypted private no-backup vault; each method including list/get uses policy. No host value disclosure, auto-reset or compromised-app/OS protection claim. No project/log credentials. Explicit JS strings cannot be securely erased.

scope exactly {origin,methods,header,prefix?}: HTTPS origin (no userinfo/query/fragment/non-root path; canonical lowercase host/default443),1..32 unique uppercase HTTP tokens≤32chars, lowercase-normalized header token≤128chars excluding transport/proxy-owned headers, optional printable ASCII prefix≤128. Wildcard method/origin not accepted. Opaque IDs match sec_[a-f0-9]{32}; label optional≤80 UTF-16 units/paired Unicode/no controls.
- secrets.put({value,scope,label?,replaceId?}): explicit programmatic nonempty UTF-8 value, signed limit, writes encrypted vault; JS already knows input.
- secrets.capture({scope,label?,replaceId?}): optional native secure input instead of passing value through JS. Input UI, no second policy approval/value result.
- secrets.get({id}): explicit plaintext under secrets.get policy, not forbidden/non-disclosing.
- secrets.list({}) and secrets.describe({id}): id/label/scope metadata only, no value/hash/size; put/capture likewise.
- secrets.remove({id}) → {id,removed:true}; secrets.reset({}) → {reset:true}, explicit vault/key discard, not automatic recovery.
- secrets.request({id,request}): secrets+network, ordinary bounded network.request options under one secrets.request policy. Native injection rechecks URL/method at each redirect; mismatch fails. No second network approval. Response may echo secret; sent bytes cannot be recalled.

Signed resources.secrets defaults entries32/valueBytes8192/vaultBytes524288; maxima256/32768/4194304; vaultBytes≥valueBytes+8192. Plaintext backups exclude vault; key loss/corruption may be irrecoverable. Preserve errors/unknown writes.

## Incoming Android shares
incomingShare requires documents and exact signed incomingShare:{mimeTypes:[...],multiple:boolean}. MIME1..32 sorted unique lowercase≤127chars: exact,type/*,*/*. Only matching SEND/optional SEND_MULTIPLE filters. Sender/MIME/content are untrusted.
- incomingShare.list({}) → array {id,action,declaredMimeType,textBytes,attachmentIndices,busy,expiresAfterMs,processLocal:true}; no content/URI/provider lookup.
- incomingShare.take({id,attachmentIndices?}): opaque64hex id; default all remaining attachments, or unique integers0..31 including[] for text only. Reserves all slots before any provider opens; no truncation. One policy binds envelope+selection; DENY opens nothing.
- Result id/action/declaredMimeType/text/subject/attachments/processLocal:true/contentTypeVerified:false/expiresAfterMs; attachment {index,handle,mode:"read",declaredMimeType}. Text once then null; documents read/close, no URI/path.
- incomingShare.discard({id}) drops unconsumed content/cancels pending take; already-delivered handles keep separate ownership until closed/session ends.

Queue page/process-local,5min,no restored/replayed Intent. Genuine temporary read grants, no persistent grant/URI injection. Lost consumed delivery stays at-most-once; no blind re-take. Blocked cleanup retains slots; no global URI-grant revocation.
Signed resources.incomingShare defaults/maxima: queuedEnvelopes16/64,attachmentsPerEnvelope16/32,textBytes65536/262144,queuedBytes1048576/8388608; textBytes≤queuedBytes. Subject≤1024bytes;bridge may bind sooner,no truncation. Queue metadata/URIs,not file/heap cap.
