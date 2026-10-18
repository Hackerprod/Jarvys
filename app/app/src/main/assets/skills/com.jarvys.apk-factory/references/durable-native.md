# Factory guidance factory-guidance-v88 / durable-native

Installed APIs,preview UNAVAILABLE. Durable data does not authenticate its caller: recheck current app/source identity and capabilities before effects. Background work waits when its caller/source or Android prerequisites are unavailable. Own reads/cleanup do not replay completed effects. No background JS/exactly-once/device acceptance claim.

## Notifications and action inbox
notifications.status({channelId?}) returns status,available,permission,runtimePermissionRequired,notificationsEnabled,channelsSupported,channelStatus,ready; optional channelId/channelImportance. permission not_required|not_declared|granted|not_granted; status ready|unavailable|permission_not_declared|permission_required|app_blocked|channel_missing|channel_blocked. Scheduler fields: schedulerAvailable,exactAlarmCapabilityDeclared,exactAlarmAccess,defaultPrecision:inexact. No display/read proof.
notifications.channel({id,name,description?,importance?}): id[A-Za-z0-9][A-Za-z0-9_.-]{0,63}; nonblank name≤160UTF8bytes,description≤512; importance low|default|high (default default). Native factory.<id>; API26+ creates missing,preserves user settings,channelCreated. API24/25 not_required.
notifications.requestPermission({}) uses genuine Android13+/target33+ POST_NOTIFICATIONS flow and rereads the actual OS grant. Earlier APIs need no prompt. Android may suppress or deny the request.
notifications.show({id,channelId,title,body,priority?,actions?}): integer id1..2147483647, channel grammar above,title nonblank≤240UTF8bytes,body≤4096,priority low|default|high. Valid Unicode/no controls except body LF/TAB. Own tag/id replaces visible notification. No actions:{status:"accepted",id}; actions:retained generationId/status. Both dispatch-only. ≤3 actions, each{id,title,data?}: unique identifier same64char grammar,title≤120bytes,data object≤4096bytes(default{}),total args≤32768. Immutable action opens own Activity/queues inert event; no arbitrary component/URL/remote-input/send.
notifications.cancel({id}) own removal/token invalidation; {status:"cancel_requested",id},no read/closure proof or renewed effect grant.
notifications.pollActions({}) → {event:null|event},oldest first; notifications.ackAction({eventId}) → {eventId,acknowledged},exact64hex event. Poll repeats until ack: deduplicate; tap grants no arbitrary effect. notifications.generationStatus({generationId}) inspects own immediate generation. notifications.forgetGeneration({generationId,acknowledgeDeliveryUnknown?}) explicitly clears eligible retained history, default false. Generation/event tokens native64hex.

## One-shot notification scheduling
Optional signed notifications:{scheduling:boolean,exactAlarms:boolean}; absence disabled; exactAlarms requires scheduling and notifications capability. Selective compiled alarm/recovery receivers+RECEIVE_BOOT_COMPLETED; exact SCHEDULE_EXACT_ALARM, no USE_EXACT_ALARM shortcut.
notifications.schedule({scheduleId,atEpochMs,expiresAtEpochMs,precision?,allowWhileIdle?,notification}): scheduleId64char identifier grammar; epoch integers1..9007199254740991, at future,expiry≥at. precision inexact(default)|exact; idle false(default); notification is same show model. Retained same ID/payload idempotent,changed payload conflicts; never downgrade exact.
notifications.resumeScheduled({scheduleId}) resumes eligible stored work after caller/source and Android checks. notifications.scheduleStatus({scheduleId}) returns own record+Android snapshot. notifications.cancelScheduled({scheduleId}) persists cancellation/cleans owned alarm, cannot recall crossed post. notifications.forgetScheduled({scheduleId,acknowledgeDeliveryUnknown?}) forgets eligible terminal history; false retains uncertainty. Forget ends ID guarantee; use new IDs.
notifications.requestExactAlarmAccess({}) opens genuine package-specific settings only when declared/required; receipt exactAlarmAccessRequested:true is no grant. Recheck status on return.

Status names include pending,arming,scheduled,schedule_unknown,queued_os_blocked,queued_exact_permission,dispatch_reserved,accepted,delivery_unknown,cancelled,revoked,expired. Alarm acceptance proves no timing/delivery. Durable pre-notify reservation; crash ambiguity→delivery_unknown,no auto-repost. Alarm/reboot/time recovery rechecks caller/source and Android prerequisites; unavailable work stays queued until eligible or expires. Same signer ownership persists; Doze/OEM/force-stop/grants remain constraints.
Signed resources.notifications defaults/maxima: schedules128/1024,inboxEvents256/1024,tombstones1024/4096,storageBytes4MiB/16MiB(min64KiB). No silent deduplication-history eviction.

## Generic home-screen widgets
Compiled RemoteViews, no per-app Android source. widgets.availability({}) → available,requestPinSupported,minimumPinApi:26,placementControlledByLauncher:true,htmlSupported:false,eventDelivery:"at_least_once_delivery". Manual placement API24+; pin API26+/launcher-dependent.
widgets.list({}) returns sorted own integer IDs (defensive4096 transport cap); widgets.read({widgetId}) → widgetId,state,configuration,dispatchUnknown,plus generation/sourceApkSha256 when configured. Foreign IDs fail NOT_OWN_WIDGET.
widgets.requestPin({model}) → requested|unsupported, exception DISPATCH_UNKNOWN; no placement/configuration proof. List then update own IDs; no uncertain pin replay.
widgets.update({widgetIds,model,acknowledgeUnknown?}) and widgets.remove({widgetIds,acknowledgeUnknown?}) default acknowledgment false; sorted unique positive own IDs. All use widgets.manage binding action/IDs/model/ack. remove resets inert placeholder,launcherPlacementRemoved:false. Unknown dispatch needs explicit programmatic acknowledgment, no second human gate.
Model exactly{root:node}; node grammar:
- row/column:{type,children}, nonempty children
- text:{type,text},≤4096UTF8bytes,LF/TAB allowed
- image:{type,asset,description}, packaged www/...png|jpg|jpeg|webp only, no URLs/URIs/SVG/runtime downloads
- progress:{type,max,value,indeterminate},max integer1..1000000,value0..max,Boolean indeterminate
- button:{type,id,label,data,delivery?}, unique ID, object data; delivery repeatable(default)|oncePerGeneration
widgets.pollActions({}) → {event:null|event}; same pending event until widgets.acknowledgeAction({eventId}) → {acknowledged:boolean}, repeated ack false. Separate inboxes; deduplicate app work, no autonomous tap effect.

Durable-before-effect dispatchUnknown; redraw checks the active caller/source before updating. Exact compiled receivers only; launcher consent/rendering unverified.
Signed resources.widgets defaults/maxima: instances16/128,nodes64/256,depth6/8,actions16/64,modelBytes16384/131072,actionDataBytes2048/16384,inboxEvents64/1024,storageBytes1MiB/16MiB,imageBytes262144/2097152,imagePixels262144/1048576. Image totals per tree; actions≤nodes,actionDataBytes≤modelBytes≤storageBytes; parser bounds still apply.

## Finite background HTTP jobs
jobs requires network; exact precompiled JobService,INTERNET/ACCESS_NETWORK_STATE/RECEIVE_BOOT_COMPLETED. Finite persisted JobScheduler, no periodic/foreground service/JS. Bounded network.request, no document streaming or vault injection. Explicit headers privately stored; no credential logs.
Choose lowercase UUID jobId before enqueue:
- jobs.enqueueHttp({jobId,request,constraints?}): request exact bounded network.request model. constraints network any(default)|unmetered,requiresCharging false,minimumLatencyMs0,optional overrideDeadlineMs; delays integer0..30days,deadline≥minimum. Override deadline explicitly permits Android to relax functional constraints.
- jobs.status({jobId}) inspects retained state.
- jobs.cancel({jobId}) cancels owned work, no remote rollback.
- jobs.resume({jobId,execution:"now",acknowledgeUnknown?}) foreground off-main,bypasses scheduling constraints under jobs.enqueueHttp. Unknown needs true/replayPossible:true; completed work is never replayed.
- jobs.readResult({jobId,acknowledge?}) → {jobId,result,acknowledged} only when complete response persisted; HTTP4xx/5xx are completed ok:false.
- jobs.forget({jobId,acknowledgeUnknown?}) terminal only,completed result acknowledged/unknown explicitly acknowledged. Ends ID guarantee; new work,new UUID.
Enqueue persists an inert payload and one schedule; persistence does not authenticate a future caller. Same retained ID/payload returns record,no reschedule; changed payload conflicts. Status accepted means durable record/schedule unconfirmed; queued may wait on OS/caller/source availability, running crossed durable dispatch reservation, completed persisted response, unknown possible unconfirmed remote effect,cancelled before dispatch. No automatic HTTP replay after reservation, even GET. Explicit resume requests foreground execution. Only transient pre-effect failures allow native backoff.
Death/onStopJob/revocation preserves unknown/fences late delivery. At-most-one automatic attempt ≠ exactly-once server delivery. Private no-backup ledger,stable signer/current effect proof. Signed resources.jobs records32/1024,storageBytes8MiB/64MiB(min1MiB); payload+max-result+4096 reservation and64KiB overhead can bind before count. Reduced budgets retain old data without new effects. Foreground resume session≤125s; real background/launcher acceptance unverified.
