package com.jarvys.agent;

import android.content.Context;
import android.content.SharedPreferences;

/** Non-secret provider/model choices; credential material is kept in SecretStore only. */
public final class ProviderSettings {
    public enum Provider { OPENAI_CODEX, OPENAI_API, OPENROUTER, CUSTOM }
    public enum OpenAiAuthMethod { BROWSER, DEVICE_CODE, API_KEY }

    private static final String PREFS = "jarvys_provider_settings";
    private final SharedPreferences preferences;

    public ProviderSettings(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public Provider getProvider() {
        String value = preferences.getString("provider", Provider.OPENROUTER.name());
        try { return Provider.valueOf(value); }
        catch (IllegalArgumentException e) { return Provider.OPENROUTER; }
    }

    public void setProvider(Provider provider) {
        SharedPreferences.Editor editor = preferences.edit();
        if (provider == Provider.CUSTOM && getProvider() != Provider.CUSTOM) {
            editor.putString("custom_previous_provider", getProvider().name());
        }
        editor.putString("provider", provider.name()).apply();
    }

    public OpenAiAuthMethod getOpenAiAuthMethod() {
        String value = preferences.getString("openai_auth_method", OpenAiAuthMethod.BROWSER.name());
        try { return OpenAiAuthMethod.valueOf(value); }
        catch (IllegalArgumentException e) { return OpenAiAuthMethod.BROWSER; }
    }

    public void setOpenAiAuthMethod(OpenAiAuthMethod method) {
        preferences.edit().putString("openai_auth_method", method.name()).apply();
    }

    public String getModel() {
        Provider provider = getProvider();
        if (provider == Provider.OPENAI_CODEX) {
            String configured = preferences.getString("model_openai", "gpt-5.2-codex");
            if (CodexModelCatalog.find(configured) != null) return configured;
            for (ModelInfo info : CodexModelCatalog.currentModels()) {
                for (ModelVariant variant : info.getVariants()) {
                    if ((info.getId() + "-" + variant.getId()).equals(configured)) {
                        preferences.edit()
                                .putString("model_openai", info.getId())
                                .putString("reasoning_variant_openai", variant.getId())
                                .apply();
                        return info.getId();
                    }
                }
            }
            for (ModelInfo info : CodexModelCatalog.currentModels()) {
                if ("gpt-5.4".equals(info.getId())) {
                    preferences.edit()
                            .putString("model_openai", info.getId())
                            .putString("reasoning_variant_openai", info.getDefaultVariant().getId())
                            .apply();
                    return info.getId();
                }
            }
            ModelInfo fallback = CodexModelCatalog.currentModels().get(0);
            preferences.edit()
                    .putString("model_openai", fallback.getId())
                    .putString("reasoning_variant_openai", fallback.getDefaultVariant().getId())
                    .apply();
            return fallback.getId();
        }
        if (provider == Provider.OPENAI_API) {
            String configured = preferences.getString("model_openai_api", "gpt-4.1");
            return configured == null ? "gpt-4.1" : configured;
        }
        if (provider == Provider.CUSTOM) return getConfiguredCustomModel();
        return preferences.getString("model_openrouter", "openrouter/free");
    }

    public String getConfiguredOpenAiModel() {
        String model = preferences.getString("model_openai", "gpt-5.2-codex");
        return model == null ? "gpt-5.2-codex" : model;
    }

    public String getConfiguredOpenRouterModel() {
        String model = preferences.getString("model_openrouter", "openrouter/free");
        return model == null ? "openrouter/free" : model;
    }

    public String getConfiguredOpenAiReasoningVariant() {
        String variant = preferences.getString("reasoning_variant_openai", "medium");
        return variant == null ? "medium" : variant;
    }

    public void setModel(String model) {
        if (model == null || model.trim().isEmpty()) throw new IllegalArgumentException("Model name is empty");
        Provider provider = getProvider();
        String key = provider == Provider.OPENAI_CODEX ? "model_openai"
                : provider == Provider.OPENAI_API ? "model_openai_api"
                : provider == Provider.CUSTOM ? "model_custom" : "model_openrouter";
        preferences.edit().putString(key, model.trim()).apply();
    }

    public void setOpenAiModel(String model) {
        if (model == null || model.trim().isEmpty()) throw new IllegalArgumentException("Model name is empty");
        preferences.edit().putString("model_openai", model.trim()).apply();
    }

    public void setOpenRouterModel(String model) {
        if (model == null || model.trim().isEmpty()) throw new IllegalArgumentException("Model name is empty");
        preferences.edit().putString("model_openrouter", model.trim()).apply();
    }

    public String getConfiguredOpenAiApiModel() {
        String model = preferences.getString("model_openai_api", "gpt-4.1");
        return model == null ? "gpt-4.1" : model;
    }

    public void setOpenAiApiModel(String model) {
        if (model == null || model.trim().isEmpty()) throw new IllegalArgumentException("Model name is empty");
        preferences.edit().putString("model_openai_api", model.trim()).apply();
    }

    public String getCustomName() {
        String value = preferences.getString("custom_name", "");
        return value == null ? "" : value;
    }

    public String getCustomBaseUrl() {
        String value = preferences.getString("custom_base_url", "");
        return value == null ? "" : value;
    }

    public String getCustomCompatibility() {
        String value = preferences.getString("custom_compat", "OPENAI_CHAT_COMPLETIONS");
        return value == null ? "OPENAI_CHAT_COMPLETIONS" : value;
    }

    public String getConfiguredCustomModel() {
        String value = preferences.getString("model_custom", "");
        return value == null ? "" : value;
    }

    public String getCustomPreviousProvider() { return preferences.getString("custom_previous_provider", null); }

    public void saveCustomEndpoint(String name, String baseUrl, String compatibility, String model) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) throw new IllegalArgumentException("Custom endpoint URL is empty");
        if (compatibility == null || compatibility.trim().isEmpty()) throw new IllegalArgumentException("Custom endpoint compatibility is empty");
        preferences.edit()
                .putString("custom_name", name == null ? "" : name.trim())
                .putString("custom_base_url", baseUrl.trim())
                .putString("custom_compat", compatibility.trim())
                .putString("model_custom", model == null ? "" : model.trim())
                .apply();
    }

    public void setCustomModel(String model) {
        preferences.edit().putString("model_custom", model == null ? "" : model.trim()).apply();
    }

    public void clearCustomEndpoint() {
        preferences.edit().remove("custom_name").remove("custom_base_url").remove("custom_compat")
                .remove("model_custom").remove("custom_previous_provider").apply();
    }

    public String getReasoningVariant() {
        String modelId = getModel();
        ModelInfo info = CodexModelCatalog.find(modelId);
        if (info == null) return "medium";
        String selected = preferences.getString("reasoning_variant_openai", info.getDefaultVariant().getId());
        for (ModelVariant variant : info.getVariants()) {
            if (variant.getId().equals(selected)) return selected;
        }
        String fallback = info.getDefaultVariant().getId();
        preferences.edit().putString("reasoning_variant_openai", fallback).apply();
        return fallback;
    }

    public void setCodexModelAndVariant(String modelId, String variantId) {
        ModelInfo info = CodexModelCatalog.INSTANCE.find(modelId);
        if (info == null) throw new IllegalArgumentException("Unknown OpenAI Codex model");
        boolean supported = false;
        for (ModelVariant variant : info.getVariants()) {
            if (variant.getId().equals(variantId)) supported = true;
        }
        if (!supported) throw new IllegalArgumentException("Unsupported reasoning variant for model");
        preferences.edit()
                .putString("model_openai", modelId)
                .putString("reasoning_variant_openai", variantId)
                .apply();
    }

    public ModelVariant getSelectedCodexVariant() {
        ModelInfo info = CodexModelCatalog.find(getModel());
        if (info == null) return CodexModelCatalog.currentModels().get(0).getDefaultVariant();
        String selectedId = getReasoningVariant();
        for (ModelVariant variant : info.getVariants()) {
            if (variant.getId().equals(selectedId)) return variant;
        }
        return info.getDefaultVariant();
    }
}
