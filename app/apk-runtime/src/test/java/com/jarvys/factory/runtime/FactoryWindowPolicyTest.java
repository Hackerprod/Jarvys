package com.jarvys.factory.runtime;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.DisplayCutoutCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;

/**
 * Host-side regression tests of the production native root, manifest, and inset dispatch.
 * Seven methods run on six SDKs, one on three SDKs, and two native-render methods on SDK 35:
 * 47 deterministic test cases. No emulator, physical-device, System UI, or Chromium claim.
 * Optional JARVYS_UX35_CAPTURE_DIR images contain the actual policy root and a labeled native
 * content fixture. They deliberately do not pretend to render WebView or a real keyboard.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 26, 28, 29, 34, 35}, qualifiers = "notnight")
@LooperMode(LooperMode.Mode.PAUSED)
@SuppressWarnings("deprecation")
public class FactoryWindowPolicyTest {
    private static final int BARS_AND_CUTOUT = WindowInsetsCompat.Type.systemBars()
            | WindowInsetsCompat.Type.displayCutout();
    private static final int HANDLED = BARS_AND_CUTOUT | WindowInsetsCompat.Type.ime();
    private static final Insets PORTRAIT_BARS = Insets.of(0, 24, 0, 48);
    private static final Insets PORTRAIT_CUTOUT = Insets.of(0, 40, 0, 0);
    private final List<ActivityController<FactoryActivity>> controllers = new ArrayList<>();

    @After public void destroyActivities() {
        for (ActivityController<FactoryActivity> controller : controllers) controller.destroy();
        controllers.clear();
    }

    private static void assertLightThemeAttribute(FactoryActivity activity, boolean expected) {
        android.content.res.TypedArray attrs=activity.obtainStyledAttributes(new int[]{android.R.attr.isLightTheme});
        try { assertEquals(expected,attrs.getBoolean(0,!expected)); } finally { attrs.recycle(); }
        assertTrue(FactoryPresentation.DEFAULT.same(activity.appliedPresentation()));
    }

    @Test public void manifestHasNoActionBarAndLaterErrorKeepsTheProtectedRoot() throws Exception {
        FactoryActivity activity = activity();
        ActivityInfo info = activity.getPackageManager().getActivityInfo(
                new ComponentName(activity, FactoryActivity.class), 0);
        assertEquals(android.R.style.Theme_Material_Light_NoActionBar, info.getThemeResource());
        assertEquals(35, activity.getApplicationInfo().targetSdkVersion);
        assertNull(activity.getActionBar());
        TypedArray theme = activity.obtainStyledAttributes(
                new int[]{android.R.attr.windowActionBar, android.R.attr.windowNoTitle});
        try {
            assertFalse(theme.getBoolean(0, true));
            assertTrue(theme.getBoolean(1, false));
        } finally { theme.recycle(); }

        FrameLayout root = root(activity);
        // Robolectric cannot provide Chromium's supported web-message-listener implementation.
        // The real onCreate error path must already be inside the real native protected root.
        assertEquals(1, root.getChildCount());
        assertTrue(root.getChildAt(0) instanceof TextView);
        assertFalse(((TextView) root.getChildAt(0)).getText().toString().isEmpty());
        FactoryWindowPolicy.applyInsets(root, insets(PORTRAIT_BARS, PORTRAIT_CUTOUT, 300));
        Insets expected = Insets.of(0, cutoutTop(), 0, 300);
        assertPadding(root, expected);

        // Exercise a later error (for example blocked navigation), after safe-area ownership
        // already exists. Reflection invokes production showError; it does not replace it.
        java.lang.reflect.Field runtimeField = FactoryActivity.class.getDeclaredField("runtime");
        runtimeField.setAccessible(true);
        FactoryRuntime runtime = (FactoryRuntime) runtimeField.get(activity);
        Method showError = FactoryRuntime.class.getDeclaredMethod("showError", String.class);
        showError.setAccessible(true);
        for (String message : new String[]{"First runtime error", "Second runtime error"}) {
            showError.invoke(runtime, message);
            assertSame(root, root(activity));
            assertEquals(1, root.getChildCount());
            TextView error = (TextView) root.getChildAt(0);
            assertEquals(message, error.getText().toString());
            assertEquals(FactoryWindowPolicy.LIGHT_TEXT, error.getCurrentTextColor());
            assertPadding(root, expected);
            measureAndLayout(root, 400, 800);
            assertChildBounds(root, error, 400, 800, expected);
        }
    }

    @Test public void lightPolicyHasContrastingVisibleBarsAndOneEdgeToEdgeOwner() {
        FactoryActivity activity = activity();
        assertFalse(FactoryWindowPolicy.isDark(activity));
        assertLightThemeAttribute(activity, true);
        assertWindowPolicy(activity, false);
    }

    @Test @Config(qualifiers = "night")
    public void darkPolicyHasContrastingVisibleBarsAndDarkErrorText() {
        FactoryActivity activity = activity();
        assertTrue(FactoryWindowPolicy.isDark(activity));
        assertLightThemeAttribute(activity, false);
        assertWindowPolicy(activity, true);
        FrameLayout root = root(activity);
        assertEquals(1, root.getChildCount());
        assertTrue(root.getChildAt(0) instanceof TextView);
        assertEquals(FactoryWindowPolicy.DARK_TEXT,
                ((TextView) root.getChildAt(0)).getCurrentTextColor());
    }

    @Test public void barsCutoutAndImeUseAnEdgeWiseUnionRatherThanAddingHeights() throws Exception {
        FactoryActivity activity = activity();
        FrameLayout root = root(activity);
        View content = replaceContent(root);
        WindowInsetsCompat input = insets(PORTRAIT_BARS, PORTRAIT_CUTOUT, 300);
        assertEquals(Insets.of(0, 0, 0, 300), input.getInsets(WindowInsetsCompat.Type.ime()));
        if (Build.VERSION.SDK_INT >= 28) assertNotNull(input.getDisplayCutout());
        FactoryWindowPolicy.applyInsets(root, input);
        Insets expected = Insets.of(0, cutoutTop(), 0, 300);
        assertPadding(root, expected);
        measureAndLayout(root, 400, 800);
        assertChildBounds(root, content, 400, 800, expected);
        assertEquals(800 - cutoutTop() - 300, content.getHeight());
        assertNotEquals("Navigation must not be added to the IME height", 348,
                root.getPaddingBottom());
    }

    @Test public void repeatedDismissedAndRotatedInsetsNeverAccumulateOrLeaveGhostPadding()
            throws Exception {
        FrameLayout root = root(activity());
        View content = replaceContent(root);
        WindowInsetsCompat keyboard = insets(PORTRAIT_BARS, PORTRAIT_CUTOUT, 300);
        for (int i = 0; i < 5; i++) {
            FactoryWindowPolicy.applyInsets(root, keyboard);
            assertPadding(root, Insets.of(0, cutoutTop(), 0, 300));
        }
        FactoryWindowPolicy.applyInsets(root, insets(PORTRAIT_BARS, PORTRAIT_CUTOUT, 0));
        assertPadding(root, Insets.of(0, cutoutTop(), 0, 48));

        Insets landscapeBars = Insets.of(0, 24, 48, 0);
        Insets landscapeCutout = Insets.of(32, 0, 0, 0);
        int left = Build.VERSION.SDK_INT >= 28 ? 32 : 0;
        FactoryWindowPolicy.applyInsets(root, insets(landscapeBars, landscapeCutout, 240));
        assertPadding(root, Insets.of(left, 24, 48, 240));
        FactoryWindowPolicy.applyInsets(root, insets(landscapeBars, landscapeCutout, 0));
        Insets landscape = Insets.of(left, 24, 48, 0);
        assertPadding(root, landscape);
        measureAndLayout(root, 800, 400);
        assertChildBounds(root, content, 800, 400, landscape);

        FactoryWindowPolicy.applyInsets(root, insets(Insets.NONE, Insets.NONE, 0));
        assertPadding(root, Insets.NONE);
        measureAndLayout(root, 800, 400);
        assertChildBounds(root, content, 800, 400, Insets.NONE);
        // A second keyboard cycle must still work after all types disappear.
        FactoryWindowPolicy.applyInsets(root, keyboard);
        assertPadding(root, Insets.of(0, cutoutTop(), 0, 300));
    }

    @Test public void forwardedInsetsClearEveryHandledTypeAndNativeLegacyMetadata()
            throws Exception {
        FrameLayout root = root(activity());
        WindowInsetsCompat original = insets(PORTRAIT_BARS, PORTRAIT_CUTOUT, 300);
        assertEquals(48, original.toWindowInsets().getStableInsetBottom());
        WindowInsetsCompat forwarded = FactoryWindowPolicy.applyInsets(root, original);
        assertZeroHandled(forwarded);
        assertFalse("Zero-valued insets must still be dispatched to the child", forwarded.isConsumed());
        assertNull(forwarded.getDisplayCutout());
        WindowInsets nativeResult = forwarded.toWindowInsets();
        assertNotNull(nativeResult);
        assertEquals(0, nativeResult.getSystemWindowInsetLeft());
        assertEquals(0, nativeResult.getSystemWindowInsetTop());
        assertEquals(0, nativeResult.getSystemWindowInsetRight());
        assertEquals(0, nativeResult.getSystemWindowInsetBottom());
        assertEquals(0, nativeResult.getStableInsetLeft());
        assertEquals(0, nativeResult.getStableInsetTop());
        assertEquals(0, nativeResult.getStableInsetRight());
        assertEquals(0, nativeResult.getStableInsetBottom());
        if (Build.VERSION.SDK_INT >= 28) assertNull(nativeResult.getDisplayCutout());
        assertZeroHandled(WindowInsetsCompat.toWindowInsetsCompat(nativeResult));
        // Ownership must not mutate the incoming object that another sibling may observe.
        assertEquals(48, original.toWindowInsets().getStableInsetBottom());
        assertEquals(300, original.getInsets(WindowInsetsCompat.Type.ime()).bottom);
        if (Build.VERSION.SDK_INT >= 28) assertNotNull(original.getDisplayCutout());
    }

    @Test public void actualRootListenerDispatchesZeroInsetsToChildWithoutDoublePadding()
            throws Exception {
        FrameLayout root = root(activity());
        View child = replaceContent(root);
        final WindowInsetsCompat[] seen = new WindowInsetsCompat[1];
        final int[] dispatches = {0};
        child.setOnApplyWindowInsetsListener((view, nativeInsets) -> {
            dispatches[0]++;
            seen[0] = WindowInsetsCompat.toWindowInsetsCompat(nativeInsets);
            Insets extra = seen[0].getInsets(HANDLED);
            view.setPadding(extra.left, extra.top, extra.right, extra.bottom);
            return nativeInsets;
        });
        WindowInsets nativeInput = insets(PORTRAIT_BARS, PORTRAIT_CUTOUT, 0).toWindowInsets();
        assertNotNull(nativeInput);
        for (int i = 0; i < 2; i++) {
            root.dispatchApplyWindowInsets(nativeInput);
            assertEquals(i + 1, dispatches[0]);
            assertNotNull(seen[0]);
            assertZeroHandled(seen[0]);
            assertPadding(root, Insets.of(0, cutoutTop(), 0, 48));
            assertPadding(child, Insets.NONE);
        }
        root.dispatchApplyWindowInsets(insets(Insets.NONE, Insets.NONE, 0).toWindowInsets());
        assertEquals(3, dispatches[0]);
        assertZeroHandled(seen[0]);
        assertPadding(root, Insets.NONE);
        assertPadding(child, Insets.NONE);
    }

    @Test @Config(sdk = {29, 34, 35})
    public void unrelatedGestureAndTappableTypesSurviveForwardingAndNativeRoundtrip()
            throws Exception {
        FrameLayout root = root(activity());
        int[] types = {WindowInsetsCompat.Type.systemGestures(),
                WindowInsetsCompat.Type.mandatorySystemGestures(),
                WindowInsetsCompat.Type.tappableElement()};
        Insets[] values = {Insets.of(17, 3, 19, 20), Insets.of(7, 2, 8, 12),
                Insets.of(1, 4, 2, 30)};
        WindowInsetsCompat.Builder builder = new WindowInsetsCompat.Builder(
                insets(PORTRAIT_BARS, PORTRAIT_CUTOUT, 300));
        for (int i = 0; i < types.length; i++) {
            builder.setInsets(types[i], values[i]);
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setInsetsIgnoringVisibility(types[i], values[i]);
                builder.setVisible(types[i], true);
            }
        }
        // A copied pre-30 Builder preserves native fields, but not the compat-only IME
        // override. Reapply it at this final fixture boundary and verify the input explicitly.
        builder.setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 300));
        WindowInsetsCompat input = builder.build();
        assertEquals(Insets.of(0, 0, 0, 300), input.getInsets(WindowInsetsCompat.Type.ime()));
        WindowInsetsCompat forwarded = FactoryWindowPolicy.applyInsets(root, input);
        WindowInsetsCompat roundtrip = WindowInsetsCompat.toWindowInsetsCompat(
                forwarded.toWindowInsets());
        assertPadding(root, Insets.of(0, 40, 0, 300));
        assertZeroHandled(forwarded);
        assertZeroHandled(roundtrip);
        assertFalse(forwarded.isConsumed());
        for (int i = 0; i < types.length; i++) {
            assertEquals(values[i], input.getInsets(types[i]));
            assertEquals(values[i], forwarded.getInsets(types[i]));
            assertEquals(values[i], roundtrip.getInsets(types[i]));
            if (Build.VERSION.SDK_INT >= 30) {
                assertEquals(values[i], forwarded.getInsetsIgnoringVisibility(types[i]));
                assertEquals(values[i], roundtrip.getInsetsIgnoringVisibility(types[i]));
            }
        }
    }

    @Test @Config(sdk = 35, qualifiers = "notnight-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nativeLightRootRendersContentOnlyInsideItsSafeBounds() throws Exception {
        renderNativeRoot(false);
    }

    @Test @Config(sdk = 35, qualifiers = "night-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nativeDarkRootRendersContentOnlyInsideItsSafeBounds() throws Exception {
        renderNativeRoot(true);
    }

    private FactoryActivity activity() {
        ActivityController<FactoryActivity> controller = Robolectric.buildActivity(FactoryActivity.class);
        controllers.add(controller);
        return controller.create().get();
    }

    private static FrameLayout root(FactoryActivity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        assertNotNull(content);
        assertEquals("The Activity content has exactly one safe-area owner", 1, content.getChildCount());
        assertTrue(content.getChildAt(0) instanceof FrameLayout);
        return (FrameLayout) content.getChildAt(0);
    }

    private static void assertWindowPolicy(FactoryActivity activity, boolean dark) {
        FrameLayout root = root(activity);
        Window window = activity.getWindow();
        int surface = dark ? FactoryWindowPolicy.DARK_SURFACE : FactoryWindowPolicy.LIGHT_SURFACE;
        assertEquals(surface, ((ColorDrawable) root.getBackground()).getColor());
        assertEquals(surface, ((ColorDrawable) window.getDecorView().getBackground()).getColor());
        assertFalse(root.getFitsSystemWindows());
        assertEquals(Color.TRANSPARENT, window.getStatusBarColor());
        assertEquals(Build.VERSION.SDK_INT >= 26 ? Color.TRANSPARENT : Color.BLACK,
                window.getNavigationBarColor());
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                window.getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
        int forbiddenUi = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LOW_PROFILE;
        assertEquals(0, window.getDecorView().getSystemUiVisibility() & forbiddenUi);
        assertEquals(0, window.getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT < 30) {
            int edgeToEdge = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            assertEquals(edgeToEdge, window.getDecorView().getSystemUiVisibility() & edgeToEdge);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            assertEquals(Build.VERSION.SDK_INT >= 30
                            ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                            : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                    window.getAttributes().layoutInDisplayCutoutMode);
        }
        WindowInsetsControllerCompat icons = WindowCompat.getInsetsController(window, window.getDecorView());
        assertEquals(!dark, icons.isAppearanceLightStatusBars());
        assertEquals(!dark && Build.VERSION.SDK_INT >= 26, icons.isAppearanceLightNavigationBars());
        if (Build.VERSION.SDK_INT >= 29) {
            assertFalse(window.isStatusBarContrastEnforced());
            assertFalse(window.isNavigationBarContrastEnforced());
        }
    }

    /** API 24-28 fixtures carry real platform stable/cutout metadata, not only compat overrides.
     * The explicit compat IME override is intentional: legacy WindowInsets has no typed IME API. */
    private static WindowInsetsCompat insets(Insets bars, Insets cutout, int imeBottom)
            throws Exception {
        WindowInsetsCompat base;
        Rect stable = new Rect(bars.left, bars.top, bars.right, bars.bottom);
        if (Build.VERSION.SDK_INT < 29) {
            WindowInsets nativeInsets;
            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutout nativeCutout = cutout.equals(Insets.NONE) ? null : new DisplayCutout(
                        new Rect(cutout.left, cutout.top, cutout.right, cutout.bottom),
                        Collections.singletonList(new Rect(0, 0,
                                Math.max(1, cutout.left), Math.max(1, cutout.top))));
                nativeInsets = WindowInsets.class.getConstructor(Rect.class, Rect.class, Rect.class,
                                boolean.class, boolean.class, DisplayCutout.class)
                        .newInstance(new Rect(stable), new Rect(), new Rect(stable), false, false,
                                nativeCutout);
            } else {
                nativeInsets = WindowInsets.class.getConstructor(Rect.class, Rect.class, Rect.class,
                                boolean.class, boolean.class)
                        .newInstance(new Rect(stable), new Rect(), new Rect(stable), false, false);
            }
            base = WindowInsetsCompat.toWindowInsetsCompat(nativeInsets);
        } else {
            WindowInsetsCompat.Builder builder = new WindowInsetsCompat.Builder()
                    .setSystemWindowInsets(bars).setStableInsets(bars);
            if (!cutout.equals(Insets.NONE)) {
                builder.setDisplayCutout(new DisplayCutoutCompat(
                        new Rect(cutout.left, cutout.top, cutout.right, cutout.bottom),
                        Collections.singletonList(new Rect(0, 0,
                                Math.max(1, cutout.left), Math.max(1, cutout.top)))));
            }
            base = builder.build();
        }
        Insets status = Insets.of(0, bars.top, 0, 0);
        Insets navigation = Insets.of(bars.left, 0, bars.right, bars.bottom);
        return new WindowInsetsCompat.Builder(base)
                .setInsets(WindowInsetsCompat.Type.statusBars(), status)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), navigation)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottom))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), status)
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), navigation)
                .setVisible(WindowInsetsCompat.Type.statusBars(), !status.equals(Insets.NONE))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), !navigation.equals(Insets.NONE))
                .setVisible(WindowInsetsCompat.Type.ime(), imeBottom > 0)
                .build();
    }

    private static void assertZeroHandled(WindowInsetsCompat insets) {
        for (int type : new int[]{WindowInsetsCompat.Type.statusBars(),
                WindowInsetsCompat.Type.navigationBars(), WindowInsetsCompat.Type.captionBar(),
                WindowInsetsCompat.Type.displayCutout(), WindowInsetsCompat.Type.ime()}) {
            assertEquals("Handled type " + type + " must not reach child twice", Insets.NONE,
                    insets.getInsets(type));
        }
        assertEquals(Insets.NONE, insets.getInsetsIgnoringVisibility(BARS_AND_CUTOUT));
    }

    private static View replaceContent(FrameLayout root) {
        root.removeAllViews();
        View child = new View(root.getContext());
        root.addView(child, new FrameLayout.LayoutParams(-1, -1));
        return child;
    }

    private static int cutoutTop() { return Build.VERSION.SDK_INT >= 28 ? 40 : 24; }

    private static void assertPadding(View view, Insets expected) {
        assertEquals(expected.left, view.getPaddingLeft());
        assertEquals(expected.top, view.getPaddingTop());
        assertEquals(expected.right, view.getPaddingRight());
        assertEquals(expected.bottom, view.getPaddingBottom());
    }

    private static void measureAndLayout(View root, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private static void assertChildBounds(FrameLayout root, View child, int width, int height,
            Insets expected) {
        assertEquals(width, root.getWidth());
        assertEquals(height, root.getHeight());
        assertEquals(expected.left, child.getLeft());
        assertEquals(expected.top, child.getTop());
        assertEquals(width - expected.right, child.getRight());
        assertEquals(height - expected.bottom, child.getBottom());
    }

    private void renderNativeRoot(boolean dark) throws Exception {
        FactoryActivity activity = activity();
        assertEquals(dark, FactoryWindowPolicy.isDark(activity));
        FrameLayout root = root(activity);
        root.removeAllViews();
        int surface = dark ? FactoryWindowPolicy.DARK_SURFACE : FactoryWindowPolicy.LIGHT_SURFACE;
        int contentColor = dark ? 0xff24282d : Color.WHITE;
        int textColor = dark ? FactoryWindowPolicy.DARK_TEXT : FactoryWindowPolicy.LIGHT_TEXT;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(contentColor);
        content.setPadding(20, 20, 20, 20);
        addLabel(content, "Jarvys native root", 25, textColor);
        addLabel(content, "API 35 / " + (dark ? "dark" : "light") + " safe-area fixture", 16, textColor);
        addLabel(content, "Generic native content\nStatus, cutout, and navigation bounds are owned by the runtime.",
                17, textColor);
        content.addView(new View(activity), new LinearLayout.LayoutParams(-1, 0, 1));
        addLabel(content, "Native host rendering only\nNo Chromium or physical-device validation", 13, textColor);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        for (int imeBottom : new int[]{280, 0}) {
            WindowInsetsCompat input = insets(PORTRAIT_BARS, PORTRAIT_CUTOUT, imeBottom);
            // Use the installed production listener, including its native dispatch boundary.
            root.dispatchApplyWindowInsets(input.toWindowInsets());
            Insets expected = Insets.of(0, 40, 0, Math.max(48, imeBottom));
            assertPadding(root, expected);
            measureAndLayout(root, 400, 800);
            assertChildBounds(root, content, 400, 800, expected);
            Bitmap bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888);
            try {
                root.draw(new Canvas(bitmap));
                assertEquals(surface, bitmap.getPixel(200, 20));
                assertEquals(surface, bitmap.getPixel(200, 799));
                assertEquals(contentColor, bitmap.getPixel(1, 41));
                assertEquals(contentColor, bitmap.getPixel(1, 799 - expected.bottom));
                String destination = System.getenv("JARVYS_UX35_CAPTURE_DIR");
                if (destination != null && !destination.trim().isEmpty()) {
                    File directory = new File(destination);
                    assertTrue("Cannot create native capture directory", directory.isDirectory()
                            || directory.mkdirs());
                    String name = "native-root-api35-" + (dark ? "dark" : "light")
                            + (imeBottom > 0 ? "-ime-inset-open.png" : "-ime-inset-dismissed.png");
                    try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
                    }
                }
            } finally { bitmap.recycle(); }
        }
    }

    private static void addLabel(LinearLayout parent, String text, int sp, int color) {
        TextView label = new TextView(parent.getContext());
        label.setText(text);
        label.setTextSize(sp);
        label.setTextColor(color);
        label.setPadding(0, 0, 0, 18);
        parent.addView(label, new LinearLayout.LayoutParams(-1, -2));
    }
}
