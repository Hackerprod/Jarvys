package com.jarvys.agent;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NonInterruptibleLedgerFileTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void positionalAndBoundedStreamReadsPreserveInterruptFlag() throws Exception {
        File ledger = temporary.newFile("ledger.jsonl");
        byte[] expected = "first\nsecond\nthird\n".getBytes(StandardCharsets.UTF_8);
        Files.write(ledger.toPath(), expected);

        Thread.currentThread().interrupt();
        try {
            try (NonInterruptibleLedgerFile input = NonInterruptibleLedgerFile.open(ledger)) {
                assertEquals(expected.length, input.length());
                ByteBuffer target = ByteBuffer.allocate(6);
                assertEquals(6, input.read(target, 6L));
                assertTrue("positional read must retain the interrupted status",
                        Thread.currentThread().isInterrupted());
                assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), target.array());
            }

            try (InputStream input = NonInterruptibleLedgerFile.open(ledger)
                    .inputStream(6L, 6L)) {
                byte[] actual = new byte[10];
                assertEquals(6, input.read(actual));
                assertEquals(-1, input.read());
                assertTrue("bounded stream read must retain the interrupted status",
                        Thread.currentThread().isInterrupted());
                assertArrayEquals("second".getBytes(StandardCharsets.UTF_8),
                        java.util.Arrays.copyOf(actual, 6));
            }
        } finally {
            Thread.interrupted();
        }
    }
}
