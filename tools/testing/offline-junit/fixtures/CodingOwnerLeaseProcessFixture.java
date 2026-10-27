package com.jarvys.agent.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Manually compiled/selected by the lead via OfflineJUnit; outside normal Gradle test discovery. */
public final class CodingOwnerLeaseProcessFixture {
    private static final byte[] SENTINEL = "synthetic-no-mutation-sentinel-v1".getBytes(StandardCharsets.US_ASCII);
    @Test public void exerciseConfiguredEndpoint() throws Exception {
        String mode = required("JARVYS_CODING_LEASE_FIXTURE_MODE");
        String nonce = required("JARVYS_CODING_LEASE_FIXTURE_NONCE");
        assertEquals("Nonce must be canonical UUID", UUID.fromString(nonce).toString(), nonce);
        Path root = Paths.get(required("JARVYS_CODING_LEASE_FIXTURE_ROOT"));
        assertTrue("Fixture root must be absolute and precreated", root.isAbsolute() && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS));
        assertEquals("Fixture root must be canonical", root, root.toRealPath());
        for (Path cursor=root; cursor!=null; cursor=cursor.getParent()) assertFalse("No symlink ancestry", Files.isSymbolicLink(cursor));
        switch(mode) {
            case "holder": hold(root,nonce); break;
            case "probe-live": probeLive(root,nonce); break;
            case "probe-dead": probeDead(root,nonce); break;
            default: fail("Unknown fixture mode");
        }
    }
    private static void hold(Path root,String nonce) throws Exception {
        write(root,nonce,"sentinel",SENTINEL);
        CodingJobOwnerLease lease=CodingJobOwnerLease.create(root.toFile());
        CodingJobOwnerLease.Pin pin=lease.pinSubmission();
        try {
            pin.requireLive();
            write(root,nonce,"ready",(nonce+"\n"+pin.processId()+"\n"+pin.managerId()+"\n").getBytes(StandardCharsets.US_ASCII));
            waitFor(root,nonce,"probed-before");
            // Closing an overlapping same-process probe must not accidentally release the OS lock.
            try(CodingJobOwnerLease.Probe probe=CodingJobOwnerLease.probe(root.toFile(),pin.processId(),pin.managerId())) {
                assertEquals(CodingJobOwnerLease.Liveness.ALIVE,probe.liveness());
            }
            pin.requireLive();
            write(root,nonce,"after-internal",nonce.getBytes(StandardCharsets.US_ASCII));
            waitFor(root,nonce,"probed-after");
            assertArrayEquals(SENTINEL,Files.readAllBytes(marker(root,nonce,"sentinel")));
            // Parent either sends release for clean shutdown or terminates its own holder process.
            waitFor(root,nonce,"release");
            java.util.concurrent.CompletableFuture<Void> drained=lease.requestQuiesce();
            assertFalse("A live pin must prevent owner release",drained.isDone());
            assertThrows(IllegalStateException.class,lease::pinSubmission);
            pin.close();
            drained.get(10,TimeUnit.SECONDS);
            assertTrue("Stable owner file must remain after close",Files.isRegularFile(lease.file().toPath(),LinkOption.NOFOLLOW_LINKS));
            write(root,nonce,"closed",nonce.getBytes(StandardCharsets.US_ASCII));
        } finally {pin.close();lease.requestQuiesce();}
    }
    private static void probeLive(Path root,String nonce) throws Exception {
        List<String> owner=owner(root,nonce);
        assertLiveness(root,owner,CodingJobOwnerLease.Liveness.ALIVE);
        assertArrayEquals(SENTINEL,Files.readAllBytes(marker(root,nonce,"sentinel")));
        write(root,nonce,"probed-before",nonce.getBytes(StandardCharsets.US_ASCII));
        waitFor(root,nonce,"after-internal");
        assertLiveness(root,owner,CodingJobOwnerLease.Liveness.ALIVE);
        assertArrayEquals(SENTINEL,Files.readAllBytes(marker(root,nonce,"sentinel")));
        write(root,nonce,"probed-after",nonce.getBytes(StandardCharsets.US_ASCII));
    }
    private static void probeDead(Path root,String nonce) throws Exception {
        // This marker is supplied ONLY by the lead after observing the holder's process termination.
        waitFor(root,nonce,"owner-exited");
        List<String> owner=owner(root,nonce);
        assertLiveness(root,owner,CodingJobOwnerLease.Liveness.DEAD);
        assertArrayEquals(SENTINEL,Files.readAllBytes(marker(root,nonce,"sentinel")));
        Path lock=root.resolve("jarvys/runtime-owners/coding/"+owner.get(2)+".lock");
        assertTrue("Probe must not unlink the stable lock file",Files.isRegularFile(lock,LinkOption.NOFOLLOW_LINKS));
    }
    private static void assertLiveness(Path root,List<String> owner,CodingJobOwnerLease.Liveness expected) throws Exception {
        try(CodingJobOwnerLease.Probe probe=CodingJobOwnerLease.probe(root.toFile(),owner.get(1),owner.get(2))) {
            assertEquals(expected,probe.liveness());
        }
    }
    private static List<String> owner(Path root,String nonce) throws Exception {
        waitFor(root,nonce,"ready");
        List<String> lines=Files.readAllLines(marker(root,nonce,"ready"),StandardCharsets.US_ASCII);
        assertEquals(3,lines.size());assertEquals(nonce,lines.get(0));
        assertEquals(UUID.fromString(lines.get(1)).toString(),lines.get(1));
        assertEquals(UUID.fromString(lines.get(2)).toString(),lines.get(2));
        return lines;
    }
    private static Path marker(Path root,String nonce,String phase){return root.resolve(nonce+"."+phase);}
    private static void write(Path root,String nonce,String phase,byte[] bytes) throws Exception {
        Path target=marker(root,nonce,phase);
        assertFalse("Protocol marker must not be replaced",Files.exists(target,LinkOption.NOFOLLOW_LINKS));
        Path staged=Files.createTempFile(root,nonce+"-marker-",".tmp");
        try {
            Files.write(staged,bytes,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);
            Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE);
        } finally {Files.deleteIfExists(staged);}
    }
    private static void waitFor(Path root,String nonce,String phase) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        Path path=marker(root,nonce,phase);
        while(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) {
            if(System.nanoTime()>=end)throw new AssertionError("Fixture protocol deadline exceeded; liveness is not inferred");
            Thread.sleep(20);
        }
        if(!"ready".equals(phase))assertArrayEquals(nonce.getBytes(StandardCharsets.US_ASCII),Files.readAllBytes(path));
    }
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isEmpty())throw new AssertionError("Missing fixture configuration");return value;}
}
