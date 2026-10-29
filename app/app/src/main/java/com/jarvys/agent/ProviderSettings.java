package com.jarvys.agent;

import android.content.Context;
import android.content.SharedPreferences;

/** Non-secret provider/model choices; credential material is kept in SecretStore only. */
public final class ProviderSettings {
    public enum Provider { OPENAI_CODEX, OPENAI_API, OPENROUTER, CUSTOM }
    public enum OpenAiAuthMethod { BROWSER, DEVICE_CODE, API_KEY }

    private static final String PREFS = "jarvys_provider_settings";
    private final SharedPreferences preferences;
    private final java.util.Map<String, ?> frozenValues;
    private String frozenModel;
    private String frozenReasoning;
    private ModelVariant frozenVariant;
    private final boolean suppressSensitiveDiagnostics;


    public ProviderSettings(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        frozenValues = null;
        suppressSensitiveDiagnostics = false;
    }

    /** Local, non-secret, read-only route snapshot; never constructs a client or resolves metadata. */
    public ProviderSettings snapshot() {
        return frozenValues == null ? new ProviderSettings(preferences.getAll(), false) : this;
    }

    /** Same frozen routing, with payload diagnostics disabled for the explicit semantic request. */
    public ProviderSettings snapshotForPrivateSemanticRequest() {
        return new ProviderSettings(frozenValues == null ? preferences.getAll() : frozenValues, true);
    }

    public boolean suppressesSensitiveDiagnostics() { return suppressSensitiveDiagnostics; }

    private ProviderSettings(java.util.Map<String, ?> values, boolean suppressDiagnostics) {
        suppressSensitiveDiagnostics = suppressDiagnostics;
        preferences = null;
        java.util.Map<String,Object> nonSecret = new java.util.HashMap<>();
        for (String key : new String[] {"provider", "openai_auth_method", "model_openai",
            "reasoning_variant_openai", "model_openai_api", "model_openrouter", "custom_name",
            "custom_base_url", "custom_compat", "model_custom", "custom_previous_provider"}) {
            if (values.containsKey(key)) nonSecret.put(key, values.get(key));
        }
        frozenValues = java.util.Collections.unmodifiableMap(nonSecret);
        frozenModel = getModel();
        frozenReasoning = getReasoningVariant();
        frozenVariant = getSelectedCodexVariant();
    }

    public boolean isSnapshot() { return frozenValues != null; }

    private String string(String key, String fallback) {
        if (frozenValues == null) return preferences.getString(key, fallback);
        Object value = frozenValues.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String)) throw new IllegalStateException("Provider setting has invalid type");
        return (String) value;
    }

    private SharedPreferences.Editor edit() {
        if (preferences == null) throw new IllegalStateException("Provider routing snapshot is read-only");
        return preferences.edit();
    }

    public Provider getProvider() {
        String value = string("provider", Provider.OPENROUTER.name());
        try { return Provider.valueOf(value); }
        catch (IllegalArgumentException e) { return Provider.OPENROUTER; }
    }

    public void setProvider(Provider provider) {
        SharedPreferences.Editor editor = edit();
        if (provider == Provider.CUSTOM && getProvider() != Provider.CUSTOM) {
            editor.putString("custom_previous_provider", getProvider().name());
        }
        editor.putString("provider", provider.name()).apply();
    }

    public OpenAiAuthMethod getOpenAiAuthMethod() {
        String value = string("openai_auth_method", OpenAiAuthMethod.BROWSER.name());
        try { return OpenAiAuthMethod.valueOf(value); }
        catch (IllegalArgumentException e) { return OpenAiAuthMethod.BROWSER; }
    }

    public void setOpenAiAuthMethod(OpenAiAuthMethod method) {
        edit().putString("openai_auth_method", method.name()).apply();
    }

    public String getModel() {
        if (frozenModel != null) return frozenModel;
        Provider provider = getProvider();
        if (provider == Provider.OPENAI_CODEX) {
            String configured = string("model_openai", "gpt-5.2-codex");
            if (CodexModelCatalog.find(configured) != null) return configured;
            for (ModelInfo info : CodexModelCatalog.currentModels()) {
                for (ModelVariant variant : info.getVariants()) {
                    if ((info.getId() + "-" + variant.getId()).equals(configured)) {
                        if (preferences != null) edit()
                                .putString("model_openai", info.getId())
                                .putString("reasoning_variant_openai", variant.getId())
                                .apply();
                        else frozenReasoning = variant.getId();
                        return info.getId();
                    }
                }
            }
            for (ModelInfo info : CodexModelCatalog.currentModels()) {
                if ("gpt-5.4".equals(info.getId())) {
                    if (preferences != null) edit()
                            .putString("model_openai", info.getId())
                            .putString("reasoning_variant_openai", info.getDefaultVariant().getId())
                            .apply();
                    else frozenReasoning = info.getDefaultVariant().getId();
                    return info.getId();
                }
            }
            ModelInfo fallback = CodexModelCatalog.currentModels().get(0);
            if (preferences != null) edit()
                    .putString("model_openai", fallback.getId())
                    .putString("reasoning_variant_openai", fallback.getDefaultVariant().getId())
                    .apply();
            else frozenReasoning = fallback.getDefaultVariant().getId();
            return fallback.getId();
        }
        if (provider == Provider.OPENAI_API) {
            String configured = string("model_openai_api", "gpt-4.1");
            return configured == null ? "gpt-4.1" : configured;
        }
        if (provider == Provider.CUSTOM) return getConfiguredCustomModel();
        return string("model_openrouter", "openrouter/free");
    }

    public String getConfiguredOpenAiModel() {
        String model = string("model_openai", "gpt-5.2-codex");
        return model == null ? "gpt-5.2-codex" : model;
    }

    public String getConfiguredOpenRouterModel() {
        String model = string("model_openrouter", "openrouter/free");
        return model == null ? "openrouter/free" : model;
    }

    public String getConfiguredOpenAiReasoningVariant() {
        String variant = string("reasoning_variant_openai", "medium");
        return variant == null ? "medium" : variant;
    }

    public void setModel(String model) {
        if (model == null || model.trim().isEmpty()) throw new IllegalArgumentException("Model name is empty");
        Provider provider = getProvider();
        String key = provider == Provider.OPENAI_CODEX ? "model_openai"
                : provider == Provider.OPENAI_API ? "model_openai_api"
                : provider == Provider.CUSTOM ? "model_custom" : "model_openrouter";
        edit().putString(key, model.trim()).apply();
    }

    public void setOpenAiModel(String model) {
        if (model == null || model.trim().isEmpty()) throw new IllegalArgumentException("Model name is empty");
        edit().putString("model_openai", model.trim()).apply();
    }

    public void setOpenRouterModel(String model) {
        if (model == null || model.trim().isEmpty()) throw new IllegalArgumentException("Model name is empty");
        edit().putString("model_openrouter", model.trim()).apply();
    }

    public String getConfiguredOpenAiApiModel() {
        String model = string("model_openai_api", "gpt-4.1");
        return model == null ? "gpt-4.1" : model;
    }

    public void setOpenAiApiModel(String model) {
        if (model == null || model.trim().isEmpty()) throw new IllegalArgumentException("Model name is empty");
        edit().putString("model_openai_api", model.trim()).apply();
    }

    public String getCustomName() {
        String value = string("custom_name", "");
        return value == null ? "" : value;
    }

    public String getCustomBaseUrl() {
        String value = string("custom_base_url", "");
        return value == null ? "" : value;
    }

    public String getCustomCompatibility() {
        String value = string("custom_compat", "OPENAI_CHAT_COMPLETIONS");
        return value == null ? "OPENAI_CHAT_COMPLETIONS" : value;
    }

    public String getConfiguredCustomModel() {
        String value = string("model_custom", "");
        return value == null ? "" : value;
    }

    public String getCustomPreviousProvider() { return string("custom_previous_provider", null); }

    public void saveCustomEndpoint(String name, String baseUrl, String compatibility, String model) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) throw new IllegalArgumentException("Custom endpoint URL is empty");
        if (compatibility == null || compatibility.trim().isEmpty()) throw new IllegalArgumentException("Custom endpoint compatibility is empty");
        edit()
                .putString("custom_name", name == null ? "" : name.trim())
                .putString("custom_base_url", baseUrl.trim())
                .putString("custom_compat", compatibility.trim())
                .putString("model_custom", model == null ? "" : model.trim())
                .apply();
    }

    public void setCustomModel(String model) {
        edit().putString("model_custom", model == null ? "" : model.trim()).apply();
    }

    public void clearCustomEndpoint() {
        edit().remove("custom_name").remove("custom_base_url").remove("custom_compat")
                .remove("model_custom").remove("custom_previous_provider").apply();
    }

    public String getReasoningVariant() {
        if (frozenReasoning != null) return frozenReasoning;
        String modelId = getModel();
        ModelInfo info = CodexModelCatalog.find(modelId);
        if (info == null) return "medium";
        String selected = string("reasoning_variant_openai", info.getDefaultVariant().getId());
        for (ModelVariant variant : info.getVariants()) {
            if (variant.getId().equals(selected)) return selected;
        }
        String fallback = info.getDefaultVariant().getId();
        if (preferences != null) edit().putString("reasoning_variant_openai", fallback).apply();
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
        edit()
                .putString("model_openai", modelId)
                .putString("reasoning_variant_openai", variantId)
                .apply();
    }

    public ModelVariant getSelectedCodexVariant() {
        if (frozenVariant != null) return frozenVariant;
        ModelInfo info = CodexModelCatalog.find(getModel());
        if (info == null) return CodexModelCatalog.currentModels().get(0).getDefaultVariant();
        String selectedId = getReasoningVariant();
        for (ModelVariant variant : info.getVariants()) {
            if (variant.getId().equals(selectedId)) return variant;
        }
        return info.getDefaultVariant();
    }
}
