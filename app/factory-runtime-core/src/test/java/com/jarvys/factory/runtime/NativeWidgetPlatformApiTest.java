package com.jarvys.factory.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertFalse;

/** Legacy API guard must finish before provider/resource lookup, rendering or a lease check. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 25}, manifest = Config.NONE)
public final class NativeWidgetPlatformApiTest {
    @Test public void pinBeforeApi26ReturnsWithoutTouchingProviderOrAuthorization() throws Exception {
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public PackageManager getPackageManager() {
                throw new AssertionError("Legacy pin must stop before provider lookup");
            }
        };
        WidgetEngine.Source source = new WidgetEngine.Source(context.getPackageName(),
                WidgetEngineTest.repeat('a'), WidgetEngineTest.repeat('b'), WidgetEngineTest.repeat('c'),
                1, WidgetModel.Limits.defaults());
        NativeWidgetPlatform platform = new NativeWidgetPlatform(context, source);
        assertFalse(platform.requestPin(null, () -> {
            throw new AssertionError("Unsupported pin must not request authorization");
        }));
    }
}
