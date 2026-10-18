# Factory guidance factory-guidance-v89 / radios

## Foreground NFC NDEF
Declared nfc; selective NFC normal permission and optional hardware.nfc feature, no arbitrary tag API/HCE/payment service/intent dispatch. Installed,preview UNAVAILABLE. Operations use the declared capability; status/cancel do not start a reader. Nearby tag physically required.
- nfc.status({}) → status,busy,available,enabled,nfcPermissionGranted,foreground,stateKnown,foregroundOnly:true,cleanupFailed,error,lastWriteOutcome,limits. Discovery only; no RF probe/settings enabling.
- nfc.read({timeoutMs?}) → {status:"read"|"empty",records,byteLength,foregroundOnly:true}; no NDEF message gives empty list, non-NDEF NDEF_UNSUPPORTED, no silent formatting.
- nfc.write({records,timeoutMs?,allowFormat?}) → {status:"written",formatted,byteLength,writeOutcome:"confirmed",rollback:false,foregroundOnly:true}. allowFormat defaults false; only explicit true plus NdefFormatable permits format. Checks writable/max-size before write; no retry or read-only locking.
- nfc.cancel({}) → {status:"cancelled",cancelled,draining,writeOutcome,rollback:false}; owned request only, uncertainty retained.
Record exact {tnf,typeHex,idHex,payloadHex}, canonical lowercase even hex. TNF integer0..5;0 all empty,5 type empty,1..4 type required. Type/ID≤255bytes; reserved/unchanged/raw chunks rejected. Android may dechunk; inert data,no URI launch or native object/UID exposure.

Signed resources.nfc defaults/maxima: messageBytes8192/131072(min3),records16/256,timeoutMs30000/300000(min1000); omitted request timeout min30000/signed cap. Encoded NDEF size includes headers/type/ID/payload, actual tag capacity may be lower. Bounds app copies,not framework heap allocation. One process reader/bounded queues. Lifecycle cancels; blocked cleanup retains ownership. Failed write/cancel can follow effect,no rollback/stop proof.

## Generic BLE central
Declared bluetooth requires exact signed bluetooth:{version:1,scanPrivacy:"full"|"neverForLocation"}; omitted capabilities supplies `full`. Signed choice controls permission profile. full may require location; neverForLocation asserts no location derivation and can filter beacons. Android12+ nearby SCAN/CONNECT grants remain real; older versions use legacy/location requirements. No programmatic radio enable/explicit bond API, but Android GATT may trigger pairing/authentication UI. BLE central only, no Classic/advertising/GATT server/background service/device-specific business protocol.

All IDs canonical lowercase opaque UUIDs, page-native ownership; no raw address. Service UUID full128-bit lowercase.
- bluetooth.status({}): framework/radio/API/target/permission/privacy/location state, not a scan/connection proof.
- bluetooth.requestPermissions({scan,connect}): both Boolean, at least one true; requests genuine missing OS permissions for exact scope, rereads grants.
- bluetooth.scan({serviceUuids,durationMs}):0..16 unique UUIDs (empty=unfiltered); integer1..signed scan duration, returns scanId. Foreground/OEM scan throttling applies.
- bluetooth.poll({handle}): scanId|connectionId, bounded events,active,cleanupPending,cumulative decimal-string droppedEvents. Correlate operation IDs; no second prompt per event. Live checks through delivery.
- bluetooth.stopScan({scanId}): stopped/cleanupPending, drops scan events. Delivered device handles remain until new scan epoch/page cancellation.
- bluetooth.connect({deviceId}): scanned device only, direct LE autoConnect=false; connectionId plus connecting/connected, actual connection event follows. No implicit discovery/write.
- bluetooth.services({connectionId,offset,limit}): offset0..4352,limit1..256. First offset0 starts discovery and returns operationId; completion includes catalog. Later calls cached page {entries,offset,nextOffset,totalEntries,cached}; byte-bound may shorten page, no hidden truncation.
- bluetooth.read({connectionId,attributeId}): characteristic/ordinary descriptor, completion valueBase64 (empty is valid).
- bluetooth.write({connectionId,attributeId,valueBase64,writeType}): exact canonical Base64 bytes, response|noResponse. Descriptors require response; CCCD only through subscribe/unsubscribe. No implicit app-level packet splitting.
- bluetooth.requestMtu({connectionId,mtu}): integer23..517, callback reports requestedMtu/actualMtu/noResponsePayloadBytes. Admission true is not negotiation. Android14 stack behavior may differ from request; honor observed MTU for no-response payload.
- bluetooth.subscribe({connectionId,attributeId,mode}): characteristic notify|indicate; operationId/subscriptionId. Active only after local routing+CCCD callback success, then poll events.
- bluetooth.unsubscribe({subscriptionId}): operationId; wait for completion event. Ambiguous in-flight teardown retires connection.
- bluetooth.disconnect({connectionId}): localResourcesReleased,cleanupPending,writeMayHaveReachedDevice; no physical radio-disconnection proof.
- bluetooth.cancel({operationId}): queued operation never issued; in-flight retires whole connection to fence late callbacks. Connect/scan starts use disconnect/stopScan instead.

Queued GATT receipts {connectionId,operationId,accepted:true} mean admission, not completion. Native rejection may give not_started. Write evidence distinguishes not_sent,sent_to_stack,att_acknowledged,unknown; none means peripheral business success. No blind retry of an uncertain write. Service catalog preserves duplicate UUID instances: service {kind,serviceId,uuid,instanceId,type}; characteristic {kind,attributeId,serviceId,uuid,properties}; descriptor {kind,attributeId,characteristicId,uuid}. Service-changed retires IDs; reconnect/discover,no hidden reflection.

Bluetooth runtime permission requests use Android's OS flow. Status/poll/cleanup do not request new permissions. Resource leases outlive reply; one-shots through poll delivery/drop. Lifecycle cancels,failed cleanup stays pending. Write validation checks length/hash/destination/type; raw values/addresses are not logged.
Signed resources.bluetooth defaults/maxima: connections4/16,discoveredDevices128/1024,queuedOperations32/256,bufferedEvents128/2048,pollEvents32/256,services64/256,attributes512/4096,subscriptions16/256,valueBytes512/512,scanDurationMs30000/300000,operationTimeoutMs30000/300000. All minima1, pollEvents≤bufferedEvents; current Android/ATT/MTU can bind earlier. Overflow reports dropped events rather than promising lossless telemetry. SDK620000ms is no lease extension; physical acceptance unverified.
