package com.jarvys.agent;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** User-visible always-on-top STOP affordance, present only while a run is active. */
public final class StopOverlayService extends Service {
    public static final String ACTION_SHOW = "com.jarvys.agent.SHOW_STOP";
    private static final String ACTION_STOP = "com.jarvys.agent.OVERLAY_STOP";

    private WindowManager windowManager;
    private LinearLayout root;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguageRuntime.localizedContext(base));
    }

    public static void updateStatus(Context context, String text) {
        // The overlay is intentionally a status-free STOP control; progress stays in chat.
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, StopOverlayService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            com.jarvys.agent.proactive.BackgroundRunController.INSTANCE.cancelAll();
            StopController.getInstance().stopRun();
            stopService(new Intent(this, AgentForegroundService.class));
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (root == null) {
            attachOverlay();
        }
        return START_NOT_STICKY;
    }

    private void attachOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            stopSelf();
            return;
        }

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.setBackground(roundRect(0xEE17212B, dp(16)));

        TextView stop = new TextView(this);
        stop.setText(R.string.stop_overlay_button);
        stop.setTextColor(Color.WHITE);
        stop.setTextSize(18f);
        stop.setTypeface(null, android.graphics.Typeface.BOLD);
        stop.setGravity(Gravity.CENTER);
        stop.setContentDescription("Stop Jarvys immediately");
        stop.setBackground(roundRect(0xFFE53935, dp(12)));
        root.addView(stop, new LinearLayout.LayoutParams(dp(150), dp(52)));
        stop.setOnClickListener(view -> {
            // Close the action gate synchronously in the click handler, before service teardown.
            com.jarvys.agent.proactive.BackgroundRunController.INSTANCE.cancelAll();
            StopController.getInstance().stopRun();
            stopService(new Intent(this, AgentForegroundService.class));
            stopSelf();
        });

        int windowType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(170), dp(72), windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.x = dp(16);
        params.y = dp(70);
        try {
            windowManager.addView(root, params);
        } catch (RuntimeException e) {
            root = null;
            stopSelf();
            stopService(new Intent(this, AgentForegroundService.class));
        }
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        if (windowManager != null && root != null) {
            try {
                windowManager.removeView(root);
            } catch (IllegalArgumentException ignored) {
                // Window was already removed by Android during teardown.
            }
        }
        root = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
