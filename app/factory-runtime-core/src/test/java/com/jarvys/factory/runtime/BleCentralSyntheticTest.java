package com.jarvys.factory.runtime;

import org.junit.Test;

/** Conventional module-test entry point for the same hardware-free deterministic suite. */
public final class BleCentralSyntheticTest {
    @Test public void genericCentralRegressionSuite() throws Exception { BleCentralTest.main(new String[0]); }
}
