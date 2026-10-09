package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** One native safe-area owner for every generated app, independent of its HTML and identity. */
public final class FactoryWindowPolicy {
    public static final int LIGHT_SURFACE = 0xfffafafa;
    public static final int DARK_SURFACE = 0xff121212;
    public static final int LIGHT_TEXT = 0xff202124;
    public static final int DARK_TEXT = 0xfff5f5f5;
    private FactoryWindowPolicy() {}

    public static boolean isDark(Activity activity) {
        return (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation") // Colors are the compatibility path before target-35 edge-to-edge.
    public static FrameLayout createRoot(Activity activity) {
        boolean dark = isDark(activity);
        int surface = dark ? DARK_SURFACE : LIGHT_SURFACE;
        Window window = activity.getWindow();
        // Materialize the theme/decor first: API 35's edge-to-edge setter no longer does this,
        // and later decor creation would otherwise overwrite our navigation-contrast setting.
        View decor = window.getDecorView();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        window.setBackgroundDrawable(new ColorDrawable(surface));
        window.setStatusBarColor(Color.TRANSPARENT);
        // API 24/25 cannot draw dark navigation icons. Keep their navigation surface black.
        window.setNavigationBarColor(Build.VERSION.SDK_INT >= 26 ? Color.TRANSPARENT : Color.BLACK);
        if (Build.VERSION.SDK_INT >= 29) {
            // The runtime owns an opaque, contrasting surface behind both visible bars.
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, decor);
        controller.setAppearanceLightStatusBars(!dark);
        controller.setAppearanceLightNavigationBars(Build.VERSION.SDK_INT >= 26 && !dark);
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(surface);
        ViewCompat.setOnApplyWindowInsetsListener(root, FactoryWindowPolicy::applyInsets);
        return root;
    }

    @SuppressWarnings("deprecation") // Clear native stable/cutout metadata as well on API 24–28.
    static WindowInsetsCompat applyInsets(View view, WindowInsetsCompat insets) {
        int barsAndCutout = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();
        int handled = barsAndCutout | WindowInsetsCompat.Type.ime();
        // getInsets takes the union (edge-wise maximum), not the sum of keyboard and navigation.
        Insets safe = insets.getInsets(handled);
        view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
        // The WebView already fits inside these bounds. Forward zeroes, including on keyboard
        // dismissal, rather than blocking dispatch with CONSUMED or applying the same inset twice.
        WindowInsetsCompat forwarded = new WindowInsetsCompat.Builder(insets)
                .setInsets(handled, Insets.NONE)
                .setInsetsIgnoringVisibility(barsAndCutout, Insets.NONE)
                .setDisplayCutout(null)
                .build();
        // Compat Builder cannot clear native stable insets before API 29, or cutouts on API 28.
        // Keep the fresh, zero-valued system window insets unconsumed so dispatch still reaches
        // the child; consume only the already-handled legacy stable/cutout metadata.
        return forwarded.consumeStableInsets().consumeDisplayCutout();
    }
}
