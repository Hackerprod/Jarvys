# Main-chat host fixture mapping

All 51 cases are retained. This inventory describes the prior run-2 failures and proposed fixture corrections, not results for the changed source. Pure UI-state namespace isolation tests remain state-container tests, not multiple-chat admission. No production service boundary is relaxed.

- `olderInteractiveCompletionCannotReleaseNewGenerationOrLegacyScope`: Previously passed; preserved unchanged except shared fixture where applicable
- `steeringMessagesRemainDurableInArrivalOrder`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `approvalArrivingWhileOtherChatVisibleReturnsToItsOwner`: Previously passed; preserved unchanged except shared fixture where applicable
- `transcriptIncorporatedSteeringIsNotReplayedAfterPartialCompletion`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `concurrentGateApprovalsStayWithTheirRunSessions`: Previously passed; preserved unchanged except shared fixture where applicable
- `steeringAdmittedAfterRunExitIsPickedUpExactlyOnce`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `messageSentAfterStopAppearsImmediatelyAndSurvivesOldWorkerExit`: Previously passed; preserved unchanged except shared fixture where applicable
- `steeringWithoutAnActiveProviderStartsANormalFollowUpRun`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair; contextless Runtime replaced with actual context/session-bound runtime
- `steeringDoesNotLaunchAReplacementOrChangeTheActiveGeneration`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `approvalCardAndWaitSurviveSameChatHydration`: Previously passed; preserved unchanged except shared fixture where applicable
- `interruptedPendingInputRecoveryLeavesDurableRequestForRetry`: Previously passed; preserved unchanged except shared fixture where applicable
- `serviceRestartRecoversPendingInputsAcrossSessionsInSequence`: Prior failure: obsolete cross-chat recovery; preserve two-input Central ordering and assert foreign pending stays untouched/no execution
- `ordinaryRejectedSubmissionReleasesItsReservationAndRecordsFailure`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `newerPendingInputSupersedesFollowUpForAlreadyConsumedSteering`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `recreationPreservesLiveOwnershipAndCompletion`: Previously passed; preserved unchanged except shared fixture where applicable
- `dispatchedUnincorporatedSteeringRecoversOnceAfterServiceRestart`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair; contextless Runtime replaced with actual context/session-bound runtime
- `pendingPromotionUsesExactDurableIdentityBeforeBeginRun`: Previously passed; preserved unchanged except shared fixture where applicable
- `startingHiddenReplacementDoesNotSwitchVisibleConversation`: Previously passed; preserved unchanged except shared fixture where applicable
- `hiddenStartRetainsApprovalUntilOwnerChatIsOpened`: Previously passed; preserved unchanged except shared fixture where applicable
- `stopCannotMissPublishedRunBeforeGenerationBinding`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `agentStartedRunFailureAfterToolCheckpointRecoversQueuedUserInput`: Previously passed; preserved unchanged except shared fixture where applicable
- `explicitStopBlocksApprovalDuringPresenterRegistrationGap`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `stopCancelsAndResolvesQueuedMessagesForOnlyThatSession`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `concurrentSteeringPersistsAndDispatchesInArrivalOrder`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `earlyRuntimeSetupFailureCannotLeakInteractiveOwnership`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `stoppingAnActiveRunDoesNotLaunchItsQueuedSteeringFollowUp`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `commentaryIsExactlyOnceAndDoesNotFinishRun`: Previously passed; preserved unchanged except shared fixture where applicable
- `steeringKeepsAnActiveApprovalResolvable`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `messagesForOneSessionRunInArrivalOrder`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `assistantAnswerBeforeSteeringIsVisibleAndRestoredInConversationOrder`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `steeringDuringAnActiveRunPersistsWithoutStoppingThatRun`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `deletedConversationCannotAcceptSteeringOrLeaveItsRunStuck`: Prior failure: obsolete deletion of an interactive arbitrary chat; replaced by exact Central deletion refusal plus foreign tombstone no-steering boundary and retained Central ownership
- `finalDrainConsumesDispatchedSteeringWithoutStoppingTheRun`: Previously passed; preserved unchanged except shared fixture where applicable
- `serviceRecoveryIgnoresPendingAgentInputsInWorkerSessions`: Previously passed; preserved unchanged except shared fixture where applicable
- `hiddenChatCompletionDoesNotStopVisibleChatOrResurrectOnReturn`: Previously passed; preserved unchanged except shared fixture where applicable
- `pickedButUnincorporatedSteeringIsFollowedUpOnceAfterCancellation`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `oldGenerationCannotCompleteOrMutateNewOne`: Previously passed; preserved unchanged except shared fixture where applicable
- `earlyAgentInputStartFailureIsSuppressedPerSessionUntilInputIsResolved`: Previously passed; preserved unchanged except shared fixture where applicable
- `transcriptIncorporatedSteeringIsNotReplayedAfterTimeout`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `otherChatRunsBesideActiveChatAndStopIsScopedToItsSession`: Prior failure: obsolete multi-chat execution positive; replaced with foreign-start/steering refusal plus Central preservation/foreign STOP isolation
- `navigationAndReturnKeepRunWithoutCrossChatCallbacks`: Previously passed; preserved unchanged except shared fixture where applicable
- `transcriptIncorporatedSteeringIsNotReplayedAfterFailure`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `steeringPreservesAnActiveApprovalUntilTheUserStopsTheRun`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `deterministicRuntimeFactoryFailureWritesOneFailedResponseAndDoesNotRetry`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `dispatchedSteeringSurvivesProviderFailureBeforeNextCheckpointAndStartsFollowUp`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `cancellingQueuedReplacementDoesNotReleaseUnwindingOlderWorker`: Previously passed; preserved unchanged except shared fixture where applicable
- `ordinarySubmissionCancelledBeforeStartReleasesItsReservation`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair; contextless Runtime replaced with actual context/session-bound runtime
- `steeringAfterFinalProviderClosureStartsANormalFollowUpRun`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair; contextless Runtime replaced with actual context/session-bound runtime
- `synchronousCancellationIdleCheckCannotDestroyReplacementService`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `steeringRemainsDurableWhenTheServiceExecutorIsShuttingDown`: Prior failure: noncanonical interactive session prevents intended assertion; canonical fixture repair
- `selectedAppLocaleSurvivesApplicationContextNormalization`: Previously passed; preserved unchanged except shared fixture where applicable


Run3's prior 51-case mapping produced 48 PASS and three remaining fixture failures. Their corrected scenarios now use one actual recovery turn with ordered pending inputs, append the serial follow-up only after closing first-provider ingress, and bind approval to the exact active generation. A new 52nd real-service regression tests hot-incorporated USER duplicate intents. It is unrun at this checkpoint; no production duplicate guard has been applied.
