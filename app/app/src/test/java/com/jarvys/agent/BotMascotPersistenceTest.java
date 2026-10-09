package com.jarvys.agent;

import static org.junit.Assert.*;
import com.jarvys.agent.crew.BotDefinition;
import com.jarvys.agent.crew.BotMascotDescriptor;
import com.jarvys.agent.crew.CrewProfile;
import com.jarvys.agent.crew.CrewProfileRepository;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Storage/CAS tests only. The injected gate below is a fixture, NOT Android Rive validation evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BotMascotPersistenceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String BOT = "custom-mascot";
    private static final String DESCRIPTION = "Original round courier with a satchel and expressive feet";
    private static final String ICON = "12345678-1234-1234-1234-123456789abc.png";
    private static final byte[] SOURCE = "{\"name\":\"Original geometry fixture\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RIV = {'R', 'I', 'V', 'E', 7, 4, 0, 0};
    private static final BotMascotStore.CompilerGate FIXTURE_GATE = (source, token) -> { token.throwIfCancelled(); return RIV.clone(); };
    private static final String PAYLOAD = BotMascotStore.sha256("approved-payload".getBytes(StandardCharsets.UTF_8));

    @Test public void validatedPackageKeepsOriginalSourceAndBytesAndHasNoPublicRawPublishEntry() throws Exception {
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot());
        BotMascotStore.ValidatedPackage prepared = prepare(store);
        BotMascotStore.PackageBytes bytes = store.read(BOT, prepared.descriptor());
        assertArrayEquals(SOURCE, bytes.source());
        assertArrayEquals(RIV, bytes.riv());
        assertEquals(BotMascotStore.sha256(SOURCE), prepared.descriptor().sourceHash);
        assertEquals(BotMascotStore.sha256(RIV), prepared.descriptor().assetHash);
        assertEquals("LOCAL_COMPILED", prepared.descriptor().validationLevel);
        assertEquals(DESCRIPTION, prepared.descriptor().visualDescription);
        assertEquals(3, packageDirectory(prepared).list().length);
        for (java.lang.reflect.Method method : BotMascotStore.class.getMethods()) assertNotEquals("prepare", method.getName());
        assertEquals(0, BotMascotStore.ValidatedPackage.class.getConstructors().length);
        byte[] returned = bytes.riv(); returned[0] = 0;
        assertArrayEquals(RIV, bytes.riv());
    }

    @Test public void assignmentChangesOnlyMetadataAndPreservesMascotAcrossEveryOtherEdit() {
        CrewProfileRepository repository = repository();
        BotDefinition first = create(repository);
        BotDefinition icon = repository.setIcon(BOT, first.revision, ICON);
        BotMascotStore.ValidatedPackage prepared = prepare(BotMascotStoreTestSupport.store(temporary.getRoot()));
        CrewProfileRepository.MascotAssignment assigned = repository.setMascot(BOT, icon.revision, prepared, "operation_1", PAYLOAD);
        assertFalse(assigned.alreadyExisted);
        assertEquals(icon.revision + 1, assigned.definition.revision);
        assertEquals(icon.profile.version, assigned.definition.profile.version);
        assertEquals(icon.profile.toJson().toString(), assigned.definition.profile.toJson().toString());
        assertEquals(ICON, assigned.definition.iconRef);
        BotDefinition edited = repository.save(assigned.definition.withProfile(assigned.definition.profile), Collections.emptyList(), Collections.emptyList());
        BotDefinition toggled = repository.setEnabled(BOT, edited.revision, false);
        BotDefinition enabled = repository.setEnabled(BOT, toggled.revision, true);
        BotDefinition replacedIcon = repository.setIcon(BOT, enabled.revision, "00000000-0000-0000-0000-000000000000.png");
        BotDefinition reopened = repository().definition(BOT);
        assertEquals(prepared.descriptor(), reopened.mascot);
        assertEquals(replacedIcon.revision, reopened.revision);
        assertEquals(1, reopened.mascotReceipts.size());
        assertEquals(assigned.receipt.assignedRevision, reopened.mascotReceipts.get(0).assignedRevision);
        assertThrows(UnsupportedOperationException.class, () -> reopened.mascotReceipts.clear());
    }

    @Test public void retryAfterRestartAndLaterMascotProfileAndIconEditsReturnsOriginalReceiptWithoutReinstalling() {
        CrewProfileRepository repository = repository(); create(repository);
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot());
        BotMascotStore.ValidatedPackage one = prepare(store), two = prepare(store);
        CrewProfileRepository.MascotAssignment first = repository.setMascot(BOT, 1, one, "op-1", PAYLOAD);
        CrewProfileRepository.MascotAssignment second = repository.setMascot(BOT, 2, two, "op-2", PAYLOAD);
        BotDefinition edited = repository.save(second.definition, Collections.emptyList(), Collections.emptyList());
        BotDefinition icon = repository.setIcon(BOT, edited.revision, ICON);
        BotDefinition disabled = repository.setEnabled(BOT, icon.revision, false);
        CrewProfileRepository restarted = repository();
        CrewProfileRepository.MascotAssignment retry = restarted.setMascot(BOT, 1, null, "op-1", PAYLOAD);
        assertTrue(retry.alreadyExisted);
        assertEquals(first.receipt.assignedRevision, retry.receipt.assignedRevision);
        assertEquals(one.descriptor(), retry.receipt.mascot);
        assertEquals(two.descriptor(), retry.definition.mascot);
        assertEquals(disabled.revision, retry.definition.revision);
        assertNotNull(restarted.mascotOperation(BOT, "op-1", PAYLOAD));
        assertNull(restarted.mascotOperation(BOT, "not-yet", PAYLOAD));
        assertThrows(IllegalArgumentException.class, () -> restarted.mascotOperation(BOT, "op-1", otherHash()));
        assertThrows(IllegalArgumentException.class, () -> restarted.setMascot(BOT, disabled.revision, two, "op-1", otherHash()));
        assertFalse(store.cleanupUnassigned(restarted, BOT, one.descriptor().packageRef));
        assertFalse(store.cleanupUnassigned(restarted, BOT, two.descriptor().packageRef));
        assertEquals(disabled.revision, restarted.definition(BOT).revision);
    }

    @Test public void staleRevisionDisabledBuiltinWrongBotAndWrongStoreCannotAssign() throws Exception {
        CrewProfileRepository repository = repository(); create(repository);
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot());
        BotMascotStore.ValidatedPackage prepared = prepare(store);
        assertThrows(IllegalArgumentException.class, () -> repository.setMascot(BOT, 2, prepared, "op", PAYLOAD));
        assertThrows(IllegalArgumentException.class, () -> repository.setMascot("coding", 1, prepared, "op", PAYLOAD));
        assertThrows(IllegalArgumentException.class, () -> repository.setMascot("android-use", 1, prepared, "op", PAYLOAD));
        BotMascotStore.ValidatedPackage otherBot = store.prepare("custom-other", DESCRIPTION, SOURCE, FIXTURE_GATE, CancellationToken.uncancellable());
        assertThrows(IllegalArgumentException.class, () -> repository.setMascot(BOT, 1, otherBot, "op", PAYLOAD));
        BotMascotStore otherStore = BotMascotStoreTestSupport.store(temporary.newFolder());
        BotMascotStore.ValidatedPackage elsewhere = prepare(otherStore);
        assertThrows(IllegalArgumentException.class, () -> repository.setMascot(BOT, 1, elsewhere, "op", PAYLOAD));
        assertThrows(IllegalArgumentException.class, () -> repository.setMascot(BOT, 1, null, "op", PAYLOAD));
        repository.setEnabled(BOT, 1, false);
        assertThrows(IllegalArgumentException.class, () -> repository.setMascot(BOT, 2, prepared, "op", PAYLOAD));
        assertNull(repository.definition(BOT).mascot);
    }

    @Test public void simultaneousAssignmentsHaveOneWinnerAndCleanupCannotDeleteTheWinner() throws Exception {
        CrewProfileRepository repository = repository(); create(repository);
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot());
        BotMascotStore.ValidatedPackage[] packages = {prepare(store), prepare(store)};
        AtomicInteger successes = new AtomicInteger(), conflicts = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[2];
        for (int i = 0; i < 2; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try { start.await(); repository.setMascot(BOT, 1, packages[index], "op" + index, PAYLOAD); successes.incrementAndGet(); }
                catch (IllegalArgumentException expected) { conflicts.incrementAndGet(); }
                catch (Throwable failure) { unexpected.set(failure); }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads) { thread.join(5000); assertFalse(thread.isAlive()); }
        assertNull(unexpected.get()); assertEquals(1, successes.get()); assertEquals(1, conflicts.get());
        BotDefinition saved = repository.definition(BOT);
        for (BotMascotStore.ValidatedPackage candidate : packages) {
            boolean winner = candidate.descriptor().equals(saved.mascot);
            assertEquals(!winner, store.cleanupUnassigned(repository, BOT, candidate.descriptor().packageRef));
            assertEquals(winner, packageDirectory(candidate).isDirectory());
        }
        assertArrayEquals(RIV, store.read(BOT, saved.mascot).riv());
    }

    @Test public void storageFailureAndCorruptionNeverReplacePriorAsset() throws Exception {
        CrewProfileRepository repository = repository(); create(repository);
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot());
        BotMascotStore.ValidatedPackage one = prepare(store), two = prepare(store);
        repository.setMascot(BOT, 1, one, "op-1", PAYLOAD);
        File obstruction = new File(temporary.getRoot(), "crew_profiles/profiles.json.new");
        assertTrue(obstruction.mkdir());
        // AtomicFile.openRead removes stale .new entries; a nonempty directory makes failure deterministic.
        File marker = new File(obstruction, "block-write"); assertTrue(marker.createNewFile());
        assertThrows(IllegalArgumentException.class, () -> repository.setMascot(BOT, 2, two, "op-2", PAYLOAD));
        assertTrue(marker.delete()); assertTrue(obstruction.delete());
        assertEquals(one.descriptor(), repository.definition(BOT).mascot);
        assertArrayEquals(RIV, store.read(BOT, one.descriptor()).riv());
        Files.write(new File(packageDirectory(two), "asset.riv").toPath(), new byte[]{'R', 'I', 'V', 'E', 0, 0, 0, 0});
        assertThrows(IllegalArgumentException.class, () -> repository.setMascot(BOT, 2, two, "op-2", PAYLOAD));
        assertEquals(one.descriptor(), repository.definition(BOT).mascot);
        assertTrue(store.cleanupUnassigned(repository, BOT, two.descriptor().packageRef));
    }

    @Test public void unreadableCatalogPreventsCleanupOfPossiblyAssignedAsset() throws Exception {
        CrewProfileRepository repository = repository(); create(repository);
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot());
        BotMascotStore.ValidatedPackage prepared = prepare(store);
        File catalog = new File(temporary.getRoot(), "crew_profiles/profiles.json");
        Files.write(catalog.toPath(), "invalid".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> store.cleanupUnassigned(repository, BOT, prepared.descriptor().packageRef));
        assertTrue(packageDirectory(prepared).isDirectory());
    }

    @Test public void cancellationBeforeAndInsideValidationCreatesNothingAndCommitGateHonorsCancellation() {
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot());
        CancellationToken cancelled = CancellationToken.cancellable(); cancelled.cancel();
        assertThrows(java.util.concurrent.CancellationException.class, () -> store.prepare(BOT, DESCRIPTION, SOURCE, FIXTURE_GATE, cancelled));
        CancellationToken during = CancellationToken.cancellable();
        assertThrows(java.util.concurrent.CancellationException.class, () -> store.prepare(BOT, DESCRIPTION, SOURCE, (s, token) -> { token.cancel(); return RIV.clone(); }, during));
        assertFalse(new File(temporary.getRoot(), "bot_mascots").exists());
        CrewProfileRepository repository = repository(); create(repository);
        BotMascotStore.ValidatedPackage prepared = prepare(store);
        CancellationToken beforeCommit = CancellationToken.cancellable();
        try (BotIconService.CommitGate gate = new BotIconService.CommitGate(beforeCommit)) {
            beforeCommit.cancel();
            assertThrows(java.util.concurrent.CancellationException.class, () -> gate.commit(() -> repository.setMascot(BOT, 1, prepared, "op", PAYLOAD)));
        }
        assertNull(repository.definition(BOT).mascot);
        assertTrue(store.cleanupUnassigned(repository, BOT, prepared.descriptor().packageRef));
    }

    @Test public void absentOrFailingCompilerCannotPersistAndCompilerCannotMutateOriginalSource() {
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot());
        assertThrows(IllegalArgumentException.class, () -> store.prepare(BOT, DESCRIPTION, SOURCE, null, CancellationToken.uncancellable()));
        assertThrows(IllegalArgumentException.class, () -> store.prepare(BOT, DESCRIPTION, SOURCE,
                (s, token) -> { throw new IllegalArgumentException("Compilation unavailable"); }, CancellationToken.uncancellable()));
        assertFalse(new File(temporary.getRoot(), "bot_mascots").exists());
        BotMascotStore.ValidatedPackage prepared = store.prepare(BOT, DESCRIPTION, SOURCE, (s, token) -> { s[0] = 0; return RIV.clone(); }, CancellationToken.uncancellable());
        assertArrayEquals(SOURCE, store.read(BOT, prepared.descriptor()).source());
        assertArrayEquals(RIV, store.read(BOT, prepared.descriptor()).riv());
    }

    @Test public void interruptedStagingIsRemovedWithoutDeletingCommittedOrUnknownFiles() throws Exception {
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot());
        BotMascotStore.ValidatedPackage prepared = prepare(store);
        File directory = packageDirectory(prepared).getParentFile();
        File interrupted = new File(directory, ".stage-00000000-0000-0000-0000-000000000000");
        assertTrue(interrupted.mkdir());
        Files.write(new File(interrupted, "source.json").toPath(), SOURCE);
        File unrelated = new File(directory, "user-data"); assertTrue(unrelated.mkdir());
        assertEquals(1, store.cleanupInterruptedStaging(BOT));
        assertFalse(interrupted.exists()); assertTrue(unrelated.exists());
        assertTrue(packageDirectory(prepared).isDirectory());
        assertEquals(0, store.cleanupInterruptedStaging(BOT));
    }

    @Test public void pathsSymlinksOversizedBytesAndAlteredManifestsFailClosed() throws Exception {
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot());
        for (String invalid : Arrays.asList("coding", "../other", "/tmp/x", "custom/x", "https://host", "custom-"))
            assertThrows(IllegalArgumentException.class, () -> store.prepare(invalid, DESCRIPTION, SOURCE, FIXTURE_GATE, CancellationToken.uncancellable()));
        assertThrows(IllegalArgumentException.class, () -> store.prepare(BOT, DESCRIPTION, new byte[BotMascotStore.MAX_SOURCE_BYTES + 1], FIXTURE_GATE, CancellationToken.uncancellable()));
        assertThrows(IllegalArgumentException.class, () -> store.prepare(BOT, DESCRIPTION, SOURCE, (s, token) -> new byte[BotMascotStore.MAX_RIV_BYTES + 1], CancellationToken.uncancellable()));
        assertThrows(IllegalArgumentException.class, () -> store.prepare(BOT, DESCRIPTION, new byte[]{(byte) 0xc3, 0x28}, FIXTURE_GATE, CancellationToken.uncancellable()));
        BotMascotStore.ValidatedPackage prepared = prepare(store);
        File asset = new File(packageDirectory(prepared), "asset.riv");
        assertTrue(asset.delete());
        File outside = temporary.newFile(); Files.write(outside.toPath(), RIV);
        Files.createSymbolicLink(asset.toPath(), outside.toPath());
        assertThrows(IllegalArgumentException.class, () -> store.read(BOT, prepared.descriptor()));
        assertThrows(IllegalArgumentException.class, () -> store.cleanupInterruptedStaging("custom-../outside"));
        assertArrayEquals(RIV, Files.readAllBytes(outside.toPath()));
        assertTrue(asset.delete()); Files.write(asset.toPath(), RIV);
        File manifest = new File(packageDirectory(prepared), "manifest.json");
        JSONObject changed = new JSONObject(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8)).put("url", "https://example.invalid");
        Files.write(manifest.toPath(), changed.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> store.read(BOT, prepared.descriptor()));
    }

    @Test public void v3CatalogMigratesOncePreservingPngRevisionProfileAndEnabledState() throws Exception {
        CrewProfile profile = new CrewProfile(BOT, 7, "Prior Bot", "Saved description", "Keep private instructions", Collections.emptyList(), Collections.emptyList());
        JSONObject legacy = new JSONObject().put("schemaVersion", 3).put("bots", new JSONArray().put(new JSONObject()
                .put("profile", profile.toJson()).put("revision", 12).put("enabled", false).put("iconRef", ICON)));
        File catalog = new File(temporary.getRoot(), "crew_profiles/profiles.json");
        assertTrue(catalog.getParentFile().mkdirs());
        Files.write(catalog.toPath(), legacy.toString().getBytes(StandardCharsets.UTF_8));
        BotDefinition migrated = repository().definition(BOT);
        assertEquals(profile.toJson().toString(), migrated.profile.toJson().toString());
        assertEquals(12, migrated.revision); assertFalse(migrated.enabled); assertEquals(ICON, migrated.iconRef);
        assertNull(migrated.mascot); assertTrue(migrated.mascotReceipts.isEmpty());
        byte[] migratedBytes = Files.readAllBytes(catalog.toPath());
        assertEquals(4, new JSONObject(new String(migratedBytes, StandardCharsets.UTF_8)).getInt("schemaVersion"));
        repository().definitions();
        assertArrayEquals(migratedBytes, Files.readAllBytes(catalog.toPath()));
    }

    @Test public void receiptLimitIsExplicitAndOldRetriesRemainAvailable() throws Exception {
        CrewProfile profile = new CrewProfile(BOT, 1, "Bot", "Description", "Instructions", Collections.emptyList(), Collections.emptyList());
        JSONArray receipts = new JSONArray(); BotMascotDescriptor last = null;
        for (int i = 0; i < BotMascotDescriptor.MAX_RECEIPTS; i++) {
            last = new BotMascotDescriptor(String.format(java.util.Locale.ROOT, "00000000-0000-0000-0000-%012d", i), PAYLOAD, PAYLOAD, DESCRIPTION);
            receipts.put(new JSONObject().put("operationId", "op" + i).put("payloadHash", PAYLOAD).put("assignedRevision", i + 2).put("mascot", last.toJson()));
        }
        JSONObject row = new JSONObject().put("profile", profile.toJson()).put("revision", 65).put("enabled", true).put("iconRef", ICON)
                .put("mascot", last.toJson()).put("mascotReceipts", receipts);
        File catalog = new File(temporary.getRoot(), "crew_profiles/profiles.json"); assertTrue(catalog.getParentFile().mkdirs());
        Files.write(catalog.toPath(), new JSONObject().put("schemaVersion", 4).put("bots", new JSONArray().put(row)).toString().getBytes(StandardCharsets.UTF_8));
        CrewProfileRepository repository = repository();
        IllegalArgumentException full = assertThrows(IllegalArgumentException.class, () -> repository.setMascot(BOT, 65, null, "op-new", PAYLOAD));
        assertTrue(full.getMessage().contains("receipt limit reached (64)"));
        assertTrue(repository.setMascot(BOT, 1, null, "op0", PAYLOAD).alreadyExisted);
        assertEquals(65, repository.definition(BOT).revision);
        assertEquals(64, repository.definition(BOT).mascotReceipts.size());
    }

    @Test public void descriptorRejectsUnknownContractsBindingsModesPathsAndValidationClaims() throws Exception {
        BotMascotDescriptor descriptor = new BotMascotDescriptor("00000000-0000-0000-0000-000000000000", PAYLOAD, PAYLOAD, DESCRIPTION);
        assertEquals(descriptor, BotMascotDescriptor.fromJson(descriptor.toJson()));
        for (String invalid : Arrays.asList("../package", "/tmp/package", "https://host/x", "file:x", "attachment:1", "uuid.riv"))
            assertThrows(IllegalArgumentException.class, () -> new BotMascotDescriptor(invalid, PAYLOAD, PAYLOAD, DESCRIPTION));
        assertThrows(IllegalArgumentException.class, () -> new BotMascotDescriptor(descriptor.packageRef, "not-a-hash", PAYLOAD, DESCRIPTION));
        for (String field : Arrays.asList("contract", "artboard", "stateMachine", "viewModel", "validationLevel")) {
            JSONObject changed = descriptor.toJson().put(field, "unsupported");
            assertThrows(IllegalArgumentException.class, () -> BotMascotDescriptor.fromJson(changed));
        }
        JSONObject bindings = descriptor.toJson(); bindings.getJSONObject("bindings").put("mode", "string");
        assertThrows(IllegalArgumentException.class, () -> BotMascotDescriptor.fromJson(bindings));
        JSONObject modes = descriptor.toJson(); modes.getJSONObject("modes").put("8", "Done");
        assertThrows(IllegalArgumentException.class, () -> BotMascotDescriptor.fromJson(modes));
        assertThrows(IllegalArgumentException.class, () -> new BotDefinition(CrewProfile.codingDefault(), 2, true, false, "", descriptor,
                Collections.singletonList(new BotMascotDescriptor.Receipt("op", PAYLOAD, 2, descriptor))));
    }

    @Test public void finalPackageCollisionCannotDeletePreviouslyAssignedBytes() {
        String fixed = "11111111-1111-1111-1111-111111111111";
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot(), () -> fixed, BotMascotStore.MAX_TOTAL_BYTES, BotMascotStore.MAX_TOTAL_PACKAGES);
        CrewProfileRepository repository = repository(); create(repository);
        BotMascotStore.ValidatedPackage original = prepare(store);
        repository.setMascot(BOT, 1, original, "original", PAYLOAD);
        assertThrows(IllegalStateException.class, () -> prepare(store));
        assertEquals(original.descriptor(), repository.definition(BOT).mascot);
        assertArrayEquals(SOURCE, store.read(BOT, original.descriptor()).source());
        assertArrayEquals(RIV, store.read(BOT, original.descriptor()).riv());
        assertFalse(new File(packageDirectory(original).getParentFile(), ".stage-" + fixed).exists());
    }

    @Test public void stagingCollisionCannotDeleteAnEarlierInterruptedWrite() throws Exception {
        String fixed = "22222222-2222-2222-2222-222222222222";
        File stage = new File(temporary.getRoot(), "bot_mascots/" + BOT + "/.stage-" + fixed);
        assertTrue(stage.mkdirs());
        File source = new File(stage, "source.json"); Files.write(source.toPath(), SOURCE);
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot(), () -> fixed, BotMascotStore.MAX_TOTAL_BYTES, BotMascotStore.MAX_TOTAL_PACKAGES);
        assertThrows(IllegalStateException.class, () -> prepare(store));
        assertArrayEquals(SOURCE, Files.readAllBytes(source.toPath()));
        assertFalse(new File(stage.getParentFile(), fixed).exists());
    }

    @Test public void descriptionsAreRequiredBoundedPlainTextAndPersistInTheReceipt() throws Exception {
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot());
        for (String invalid : Arrays.asList("", "   ", "line\nbreak", "null\u0000value", "bad\uD800",
                String.join("", Collections.nCopies(1201, "x")))) {
            assertThrows(IllegalArgumentException.class, () -> store.prepare(BOT, invalid, SOURCE, FIXTURE_GATE, CancellationToken.uncancellable()));
        }
        CrewProfileRepository repository = repository(); create(repository);
        BotMascotStore.ValidatedPackage compiled = prepare(store);
        repository.setMascot(BOT, 1, compiled, "op", PAYLOAD);
        BotDefinition reopened = repository().definition(BOT);
        assertEquals(DESCRIPTION, reopened.mascot.visualDescription);
        assertEquals(DESCRIPTION, reopened.mascotReceipts.get(0).mascot.visualDescription);
        JSONObject manifest = new JSONObject(new String(Files.readAllBytes(new File(packageDirectory(compiled), "manifest.json").toPath()), StandardCharsets.UTF_8));
        assertEquals(DESCRIPTION, manifest.getJSONObject("descriptor").getString("visualDescription"));
        JSONObject missing = compiled.descriptor().toJson(); missing.remove("visualDescription");
        assertThrows(IllegalArgumentException.class, () -> BotMascotDescriptor.fromJson(missing));
    }

    @Test public void fullLengthDescriptionRoundTripsWithoutIncreasingSceneStringBudgets() {
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot());
        String description = String.join("", Collections.nCopies(BotMascotDescriptor.MAX_DESCRIPTION_CHARS, "x"));
        BotMascotStore.ValidatedPackage prepared = store.prepare(BOT, description, SOURCE, FIXTURE_GATE, CancellationToken.uncancellable());
        assertEquals(description, prepared.descriptor().visualDescription);
        assertArrayEquals(RIV, store.read(BOT, prepared.descriptor()).riv());
        CrewProfileRepository repository = repository(); create(repository);
        repository.setMascot(BOT, 1, prepared, "op", PAYLOAD);
        assertEquals(description, repository().definition(BOT).mascot.visualDescription);
        String tooLongSceneString = "{\"name\":\"" + description + "\"}";
        assertThrows(IllegalArgumentException.class, () -> store.prepare(BOT, DESCRIPTION,
                tooLongSceneString.getBytes(StandardCharsets.UTF_8), FIXTURE_GATE, CancellationToken.uncancellable()));
    }

    @Test public void lenientJsonCannotBypassStrictDepthChecksBeforeTheCompilerOrManifestRead() throws Exception {
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot());
        AtomicInteger compiles = new AtomicInteger();
        String hostile = "{'x':'\"','y':" + String.join("", Collections.nCopies(2000, "["))
                + "0" + String.join("", Collections.nCopies(2000, "]")) + "}";
        assertThrows(IllegalArgumentException.class, () -> store.prepare(BOT, DESCRIPTION,
                hostile.getBytes(StandardCharsets.UTF_8), (source, token) -> { compiles.incrementAndGet(); return RIV; }, CancellationToken.uncancellable()));
        assertEquals(0, compiles.get());
        BotMascotStore.ValidatedPackage prepared = prepare(store);
        Files.write(new File(packageDirectory(prepared), "manifest.json").toPath(), hostile.getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> store.read(BOT, prepared.descriptor()));
    }

    @Test public void installationPackageQuotaIncludesOtherBotsAndKeepsAssignedAssets() {
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot(), () -> java.util.UUID.randomUUID().toString(), BotMascotStore.MAX_TOTAL_BYTES, 1);
        CrewProfileRepository repository = repository(); create(repository);
        BotMascotStore.ValidatedPackage saved = prepare(store);
        repository.setMascot(BOT, 1, saved, "original", PAYLOAD);
        IllegalArgumentException full = assertThrows(IllegalArgumentException.class, () -> store.prepare("custom-other", DESCRIPTION, SOURCE, FIXTURE_GATE, CancellationToken.uncancellable()));
        assertTrue(full.getMessage().contains("storage quota reached"));
        assertFalse(new File(temporary.getRoot(), "bot_mascots/custom-other").exists());
        assertEquals(saved.descriptor(), repository.definition(BOT).mascot);
        assertArrayEquals(RIV, store.read(BOT, saved.descriptor()).riv());
    }

    @Test public void totalByteQuotaIncludesManifestAndChecksCandidateBeforeWrites() {
        BotMascotStore tiny = BotMascotStoreTestSupport.store(temporary.getRoot(), () -> java.util.UUID.randomUUID().toString(), SOURCE.length + RIV.length, 256);
        assertThrows(IllegalArgumentException.class, () -> prepare(tiny));
        assertFalse(new File(temporary.getRoot(), "bot_mascots").exists());
        BotMascotStore unrestricted = BotMascotStoreTestSupport.store(temporary.getRoot());
        BotMascotStore.ValidatedPackage first = prepare(unrestricted);
        long storedBytes = 0;
        for (File file : packageDirectory(first).listFiles()) storedBytes += file.length();
        BotMascotStore full = BotMascotStoreTestSupport.store(temporary.getRoot(), () -> java.util.UUID.randomUUID().toString(), storedBytes, 256);
        assertThrows(IllegalArgumentException.class, () -> prepare(full));
        assertEquals(1, packageDirectory(first).getParentFile().list().length);
        assertArrayEquals(RIV, full.read(BOT, first.descriptor()).riv());
    }

    @Test public void interruptedStagesConsumeGlobalQuotaUntilExplicitSafeCleanup() throws Exception {
        File stage = new File(temporary.getRoot(), "bot_mascots/" + BOT + "/.stage-33333333-3333-3333-3333-333333333333");
        assertTrue(stage.mkdirs()); Files.write(new File(stage, "source.json").toPath(), SOURCE);
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot(), () -> java.util.UUID.randomUUID().toString(), BotMascotStore.MAX_TOTAL_BYTES, 1);
        assertThrows(IllegalArgumentException.class, () -> prepare(store));
        assertTrue(stage.exists());
        assertEquals(1, store.cleanupInterruptedStaging(BOT));
        assertArrayEquals(RIV, store.read(BOT, prepare(store).descriptor()).riv());
    }

    @Test public void quotaTraversalRejectsSymlinkedBotsWithoutFollowingThem() throws Exception {
        File root = new File(temporary.getRoot(), "bot_mascots"); assertTrue(root.mkdir());
        File outside = temporary.newFolder();
        File marker = new File(outside, "keep"); Files.write(marker.toPath(), SOURCE);
        Files.createSymbolicLink(new File(root, "custom-linked").toPath(), outside.toPath());
        BotMascotStore store = BotMascotStoreTestSupport.store(temporary.getRoot());
        assertThrows(IllegalArgumentException.class, () -> prepare(store));
        assertArrayEquals(SOURCE, Files.readAllBytes(marker.toPath()));
        assertFalse(new File(root, BOT).exists());
    }

    @Test public void failedDirectorySyncAfterRenameKeepsPreviousPackageAndPreservesDiagnosticCause() {
        BotMascotStore good = BotMascotStoreTestSupport.store(temporary.getRoot());
        CrewProfileRepository repository = repository(); create(repository);
        BotMascotStore.ValidatedPackage previous = prepare(good);
        repository.setMascot(BOT, 1, previous, "original", PAYLOAD);
        BotMascotStore failed = new BotMascotStore(temporary.getRoot(), () -> java.util.UUID.randomUUID().toString(),
                BotMascotStore.MAX_TOTAL_BYTES, BotMascotStore.MAX_TOTAL_PACKAGES, directory -> {
                    if (directory.getName().equals(BOT)) throw new java.io.IOException("fixture-directory-sync");
                    BotMascotStoreTestSupport.syncDirectory(directory);
                });
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> prepare(failed));
        assertNotNull(error.getCause());
        assertFalse(error.getMessage().contains("fixture-directory-sync"));
        assertEquals(previous.descriptor(), repository.definition(BOT).mascot);
        assertArrayEquals(RIV, good.read(BOT, previous.descriptor()).riv());
        assertEquals(1, packageDirectory(previous).getParentFile().list().length);
    }

    private CrewProfileRepository repository() { return new CrewProfileRepository(temporary.getRoot()); }
    private static BotDefinition create(CrewProfileRepository repository) {
        return repository.create(new CrewProfile(BOT, 1, "Mascot", "Visual fixture", "Private reusable instructions", Collections.emptyList(), Collections.emptyList()), Collections.emptyList(), Collections.emptyList());
    }
    private static BotMascotStore.ValidatedPackage prepare(BotMascotStore store) {
        return store.prepare(BOT, DESCRIPTION, SOURCE, FIXTURE_GATE, CancellationToken.uncancellable());
    }
    private File packageDirectory(BotMascotStore.ValidatedPackage prepared) { return new File(temporary.getRoot(), "bot_mascots/" + BOT + "/" + prepared.descriptor().packageRef); }
    private static String otherHash() { return BotMascotStore.sha256("changed".getBytes(StandardCharsets.UTF_8)); }
}
