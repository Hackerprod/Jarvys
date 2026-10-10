package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import org.json.JSONException;
import org.json.JSONObject;

/** Closed, per-installed-app presentation preferences; no host broker or global settings. */
public final class FactoryPresentation {
    static final String PREFERENCES = "factory_presentation_v1";
    private FactoryPresentation() { }
    /** Implemented only by the generated entry point, never a Jarvys preview Activity. */
    public interface Owner { State appliedPresentation(); }
    public static final class State {
        public final String theme, orientation;
        private State(String theme, String orientation) { this.theme=theme; this.orientation=orientation; }
        public boolean same(State other) {
            return other != null && theme.equals(other.theme) && orientation.equals(other.orientation);
        }
    }
    public static final State DEFAULT = new State("system", "system");
    public static State parse(JSONObject args) throws FactoryException {
        FactoryConfig.exactKeys(args,"theme","orientation");
        try {
            String theme=FactoryConfig.string(args,"theme"), orientation=FactoryConfig.string(args,"orientation");
            if (!(theme.equals("system") || theme.equals("light") || theme.equals("dark")) ||
                    !(orientation.equals("system") || orientation.equals("portrait") || orientation.equals("landscape")))
                throw new FactoryException("INVALID_ARGUMENT","Unsupported presentation choice.");
            return new State(theme,orientation);
        } catch(JSONException missing) { throw new FactoryException("INVALID_ARGUMENT","Both theme and orientation strings are required."); }
    }
    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE);
    }
    public static State read(Context context) {
        try {
            SharedPreferences prefs=preferences(context);
            return parse(new JSONObject().put("theme",prefs.getString("theme","system"))
                    .put("orientation",prefs.getString("orientation","system")));
        } catch (Exception corrupt) { return DEFAULT; }
    }
    static void save(Context context, State state) throws FactoryException {
        // commit may change process memory even when disk persistence fails. Never report saved
        // or request recreation on that path; get/relaunch is required to inspect the outcome.
        if (!preferences(context).edit().putString("theme",state.theme).putString("orientation",state.orientation).commit())
            throw new FactoryException("PRESENTATION_STORAGE_ERROR","Preferences may have changed in memory but were not confirmed saved; inspect state before retrying.");
    }
    public static final class Bootstrap {
        public final Context context;
        public final State state;
        public final boolean enabled;
        private Bootstrap(Context context,State state,boolean enabled) { this.context=context;this.state=state;this.enabled=enabled; }
    }
    public static Bootstrap bootstrap(Context base) {
        try {
            return bootstrap(base,FactoryRuntime.readConfiguration(base.getAssets().open("factory-app.json")));
        } catch(Exception invalid) { return new Bootstrap(base,DEFAULT,false); }
    }
    static Bootstrap bootstrap(Context base,String configuration) {
        try {
            FactoryConfig config=FactoryConfig.parse(configuration,base.getPackageName());
            if (!config.capabilities.contains("presentation")) return new Bootstrap(base,DEFAULT,false);
            State state=read(base);
            return new Bootstrap(themedContext(base,state),state,true);
        } catch(Exception invalid) { return new Bootstrap(base,DEFAULT,false); }
    }
    static Context themedContext(Context base, State state) {
        if (state.theme.equals("system")) return base;
        Configuration override=new Configuration(base.getResources().getConfiguration());
        override.uiMode=(override.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) |
                (state.theme.equals("dark") ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return base.createConfigurationContext(override);
    }
    public static boolean applyOrientation(Activity activity,State state) {
        int requested=state.orientation.equals("portrait") ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT :
                state.orientation.equals("landscape") ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        // This is only a request. Android/OEM/window policies may ignore it, even if this getter agrees.
        try {
            if (activity.getRequestedOrientation()!=requested) activity.setRequestedOrientation(requested);
            return true; // Request path completed, never proof of actual rotation.
        } catch(RuntimeException unavailable) {
            return false; // Unsupported/OEM/window policy must not crash the otherwise usable app.
        }
    }
    static JSONObject result(Activity activity,State requested,boolean recreate) throws JSONException {
        int observed=activity.getResources().getConfiguration().orientation;
        return new JSONObject().put("theme",requested.theme).put("orientation",requested.orientation)
                .put("effectiveTheme",FactoryWindowPolicy.isDark(activity)?"dark":"light")
                .put("configurationOrientation",observed==Configuration.ORIENTATION_PORTRAIT?"portrait":
                        observed==Configuration.ORIENTATION_LANDSCAPE?"landscape":"undefined")
                .put("orientationGuaranteed",false).put("recreationRequested",recreate);
    }
}
