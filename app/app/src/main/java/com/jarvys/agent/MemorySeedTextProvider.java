package com.jarvys.agent;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

/** Supplies app-language starter files and exact canonical seed variants for safe migration. */
public interface MemorySeedTextProvider {
    SeedTexts current();
    SeedTexts english();
    SeedTexts spanish();

    final class SeedTexts {
        public final String index;
        public final String human;
        public final String persona;
        public SeedTexts(String index, String human, String persona) {
            this.index = index; this.human = human; this.persona = persona;
        }
    }

    static MemorySeedTextProvider fromAppLanguage(Context context) {
        Context app = context.getApplicationContext();
        return new MemorySeedTextProvider() {
            @Override public SeedTexts current() {
                AppLanguageChoice choice = AppLanguageRuntime.INSTANCE.current(app);
                if (choice == AppLanguageChoice.ENGLISH) return english();
                if (choice == AppLanguageChoice.SPANISH) return spanish();
                return read(AppLanguageRuntime.localizedContext(app));
            }
            @Override public SeedTexts english() { return read(forLocale(Locale.ENGLISH)); }
            @Override public SeedTexts spanish() { return read(forLocale(new Locale("es"))); }

            private Context forLocale(Locale locale) {
                Configuration configuration = new Configuration(app.getResources().getConfiguration());
                configuration.setLocale(locale);
                return app.createConfigurationContext(configuration);
            }

            private SeedTexts read(Context localized) {
                return new SeedTexts(localized.getString(R.string.memory_seed_root_index),
                        localized.getString(R.string.memory_seed_human),
                        localized.getString(R.string.memory_seed_persona));
            }
        };
    }
}
