# Factory guidance factory-guidance-v88 / external-actions

Named capabilities+pinned host/retained exact artifact/recipient checks; no documents dependency. Dispatches to a verified default recipient or opens the relevant Android chooser for the user to select one. Five-minute operation lifetime, no replay or closure acknowledgment. Cancel cannot recall data/close recipient tasks. Preview UNAVAILABLE; documents.cancel unrelated. Receipts prove dispatch,not rendering/navigation/dial/draft/event/send/delivery. A new interaction automatically closes an interrupted one from the same family while preserving its uncertain outcome.

## Browser
browser.open({url}): ASCII≤2048,lowercase https/DNS host≥2 labels (≤63/label,253total),optional:443. Reject userinfo/fragment/local/numeric host/trailing dot/control/whitespace/backslash/malformed escapes incl escaped control/space/DEL/backslash. No normalization/secret detection. Full URL reaches browser/site; cookies/accounts/history/sync and DNS/redirects/resources/app launches outside Jarvys control. No Jarvys fetch/INTERNET from browser capability. {launchRequested,pageLoadConfirmed:false}; no automatic retry.

## Maps/dialer
maps.open({latitude,longitude}): finite[-90,90]/[-180,180],canonical decimals; or maps.open({query}): nonblank paired Unicode1–256codepoints/1024UTF8bytes,no control/format/line/paragraph characters,encoded once.
phone.dial({number}): optional+ then1–15 unchanged ASCII digits,no USSD/extensions. Exact keys; geo ACTION_VIEW/tel ACTION_DIAL only,no GPS/autocall/arbitrary URI. Recipient network/accounts/grants outside control. {launchRequested,actionConfirmed:false}.

## Editors
email.compose({to,subject,body}) and sms.compose({number,body}); all strings required,subject/body may be empty. One email≤254ASCIIchars: local≤64,[A-Za-z0-9_+\-]+(\.[A-Za-z0-9_+\-]+)*; domain≥2labels,≤253total,each1–63alphanumeric/hyphen with alphanumeric ends. SMS same dialer-number grammar. No normalization/existence proof.
Subject≤256codepoints/1024UTF8bytes,body≤4096bytes. Paired Unicode,no ISO/format/bidi/line/paragraph controls except body LF/TAB; no CR. JSON editors≤32KiB,maps/dialer≤8KiB. ACTION_SENDTO mailto/smsto only,encoded once (+ as%2B),fixed native extras; no URI queries/CC/BCC/attachment/contacts-read/autosend. Opening shares fields; recipient may change/ignore/cloud-sync/transmit. {launchRequested,actionConfirmed:false}. These capabilities add no Android permission; other selected families may.
