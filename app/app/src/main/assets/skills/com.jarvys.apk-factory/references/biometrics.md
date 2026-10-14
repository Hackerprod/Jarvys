# Factory guidance factory-guidance-v87 / biometrics

## Local strong OS authentication
Declared biometrics, installed apps only; all preview methods UNAVAILABLE. Selective normal USE_BIOMETRIC permission, no dangerous permission prompt/broker requirement. Nonbiometric profiles can have other selected permissions; only the base template is permission-free.

Framework BiometricPrompt on API30+ with BIOMETRIC_STRONG and setConfirmationRequired(true). Confirmation is a system hint, not proof of an extra button. API24–29 unsupported. No weak biometric, device-credential fallback, enrollment/settings launch, CryptoObject/key binding, reusable token or biometric material. The person alone operates the OS prompt, with fixed title/subtitle, supplied reason and no-other-authority notice.

- biometrics.availability({}) or no args → {available,status,minimumApi:30,biometricClass:"strong",credentialFallback:false,mode:"installed"}; status ready|unavailable|unsupported. No enrollment/modality/identity details; ready guarantees no future success.
- biometrics.authenticate({reason}): exact key, nonblank1–160 UTF-16 code units/≤512 UTF-8 bytes, paired Unicode; no trimming/coercion, controls, bidi/format or line/paragraph separators. No secrets in reason. One foreground UI owner; initial focus required, OS prompt focus transfer allowed. Mismatch/help nonterminal.
- Success {authenticated:true,biometricClass:"strong",authorizesOtherActions:false,mode:"installed",simulated:false}. No identity proof/remote assertion/other-action authority; never persist as reusable authorization.
- biometrics.cancel({}) or no args rejects active request CANCELLED, returns {cancelRequested:boolean,cancellationAcknowledged:false}. False proves no earlier prompt closure; true also only requests cancellation. Cleanup exception returns UNAVAILABLE.

Native60-second monotonic deadline, SDK90 seconds. Pause/navigation/reset/destruction/revocation/global bridge rate failure cancels delivery. No replay after process restart, automatic resume or blind retry on missing reply.

Cancel retains process-wide draining owner across Activity recreation until terminal callback. After2 seconds or cleanup throw, gate poisoned until process restart: authenticate/cancel UNAVAILABLE. Mismatch/help is no acknowledgment; restart is no proof of prior closure. Never bypass poison.

At most3 admitted starts/rolling60s and8 no-op cancels/10s; counters survive Activity recreation, not restart. Valid cancel envelope≤1KiB uses reserved bounded control capacity. Bounded recognized invalid cancel fails just that request; oversized/unparseable traffic follows ordinary global limiter and can cancel pending authentication. Invalid data grants nothing.

Errors: INVALID_ARGUMENT,UNAVAILABLE,BUSY,RATE_LIMITED,CANCELLED,TIMEOUT,AUTHENTICATION_FAILED. New explicit request after interruption; fake tests establish no physical matching/closure/device acceptance.
