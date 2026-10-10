package com.jarvys.factory.runtime;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.widget.FrameLayout;
import androidx.core.view.ViewCompat;

/** Installed entry point. Identity always comes from this real Android Context. */
public final class FactoryActivity extends Activity {
    private FactoryRuntime runtime;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = FactoryWindowPolicy.createRoot(this);
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
        runtime = new FactoryRuntime(this, root, FactoryRuntime.installedHost(this));
        runtime.start();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (runtime != null) runtime.onActivityResult(request, result, data);
    }
    @Override protected void onDestroy() {
        if (runtime != null) runtime.close();
        super.onDestroy();
    }
}
