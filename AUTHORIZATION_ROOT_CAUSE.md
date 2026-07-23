# Root Cause of AuthorizationFailed

## Observation
The pipeline logs `AuthorizationFailed` immediately upon startup, and `GeminiLiveProvider` transitions from `Disconnected -> Initializing -> Disconnected` without ever establishing a session.

## Exact Code Path

1. **File:** `e:\UltronAssistant\app\src\main\java\com\flexfly\ultron\voice\speaker\SpeakerVerificationManager.kt`
   - **Line:** 39
   - **Condition:** The initial state of `_verificationState` is set to `VerificationResult.NotEnrolled` when `store.hasOwnerProfile()` is false.

2. **File:** `e:\UltronAssistant\app\src\main\java\com\flexfly\ultron\voice\session\VoiceSessionManager.kt`
   - **Method:** `start()` -> `speakerManager.verificationState.collect`
   - **Line:** 65
   - **Behavior:** `StateFlow.collect` immediately emits the current value of the flow (`NotEnrolled`) upon subscription when the service starts.

3. **File:** `e:\UltronAssistant\app\src\main\java\com\flexfly\ultron\voice\session\VoiceSessionManager.kt`
   - **Method:** `handleVerificationResult(result: VerificationResult)`
   - **Line:** 77-82
   - **Condition:**
     ```kotlin
     is VerificationResult.Rejected, is VerificationResult.NotEnrolled -> {
         if (_sessionState.value !is VoiceSessionState.Idle) {
             endSession("AuthorizationFailed")
         } else {
             emitEvent(VoiceSessionEvent.AuthorizationFailed())
         }
     }
     ```
   - **Result:** Because `_sessionState.value` is `Idle` and the result is `NotEnrolled`, it immediately hits the `else` block and emits `VoiceSessionEvent.AuthorizationFailed()`.

## Why this is a bug
`NotEnrolled` is a valid initial state representing that the user hasn't enrolled a voice profile. It should not be treated as an authorization failure. When a wake word is subsequently detected, the system auto-approves (transitions to `Verified`), but the initial emission of `AuthorizationFailed` breaks downstream observers or pollutes the state machine.

## Fix
Separate `VerificationResult.Rejected` from `VerificationResult.NotEnrolled` in `handleVerificationResult()`.
- If `Rejected`, emit `AuthorizationFailed`.
- If `NotEnrolled`, simply ignore it when `Idle` (as it's a valid standby state waiting for a wake word to auto-verify).
