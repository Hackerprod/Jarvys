package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.content.Intent;
import android.widget.FrameLayout;
import androidx.core.view.ViewCompat;

/** Installed entry point. Identity always comes from this real Android Context. */
public final class FactoryActivity extends Activity implements FactoryPresentation.Owner {
    private FactoryPresentation.Bootstrap presentation;
    @Override protected void attachBaseContext(Context base) {
        presentation = FactoryPresentation.bootstrap(base);
        super.attachBaseContext(presentation.context);
    }
    @Override public FactoryPresentation.State appliedPresentation() { return presentation.state; }
    private FactoryRuntime runtime;
    @Override public void onCreate(Bundle state) {
        setTheme(FactoryWindowPolicy.isDark(this) ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        FrameLayout root = FactoryWindowPolicy.createRoot(this);
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
        runtime = new FactoryRuntime(this, root, FactoryRuntime.installedHost(this));
        if (runtime.start() && presentation.enabled && !FactoryPresentation.applyOrientation(this, presentation.state))
            android.util.Log.w("FactoryPresentation", "Orientation request unconfirmed by the current window policy.");
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (runtime != null) runtime.onActivityResult(request, result, data);
    }
    @Override protected void onResume() {
        super.onResume();
        if (runtime != null) runtime.onResume();
    }
    @Override protected void onPause() {
        if (runtime != null) runtime.onPause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (runtime != null) runtime.close();
        super.onDestroy();
    }
}
