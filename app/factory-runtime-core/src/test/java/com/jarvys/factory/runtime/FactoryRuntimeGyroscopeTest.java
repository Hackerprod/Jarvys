package com.jarvys.factory.runtime;

import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Repeats the complete sensor authority/lifecycle regression contract with gyroscope input. */
@Config(sdk={24,28,35},manifest=Config.NONE,shadows=FactoryRuntimeDocumentsTest.Features.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FactoryRuntimeGyroscopeTest extends FactoryRuntimeSensorsTest {
    @Override String arguments(){return super.arguments().replace("light","gyroscope");}
}
