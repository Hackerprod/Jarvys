package com.jarvys.agent.apkfactory;

/** Records every platform structure call without exporting any real user data. */
public final class FactoryEditorRecordingViewStructure extends android.view.ViewStructure {
    public final java.util.List<String> calls = new java.util.ArrayList<>();
    public android.view.autofill.AutofillId recordedId;
    @Override public void setId(int a0, java.lang.String a1, java.lang.String a2, java.lang.String a3) { calls.add("setId"); }
    @Override public void setDimens(int a0, int a1, int a2, int a3, int a4, int a5) { calls.add("setDimens"); }
    @Override public void setTransformation(android.graphics.Matrix a0) { calls.add("setTransformation"); }
    @Override public void setElevation(float a0) { calls.add("setElevation"); }
    @Override public void setAlpha(float a0) { calls.add("setAlpha"); }
    @Override public void setVisibility(int a0) { calls.add("setVisibility"); }
    public void setAssistBlocked(boolean a0) { calls.add("setAssistBlocked"); }
    @Override public void setEnabled(boolean a0) { calls.add("setEnabled"); }
    @Override public void setClickable(boolean a0) { calls.add("setClickable"); }
    @Override public void setLongClickable(boolean a0) { calls.add("setLongClickable"); }
    @Override public void setContextClickable(boolean a0) { calls.add("setContextClickable"); }
    @Override public void setFocusable(boolean a0) { calls.add("setFocusable"); }
    @Override public void setFocused(boolean a0) { calls.add("setFocused"); }
    @Override public void setAccessibilityFocused(boolean a0) { calls.add("setAccessibilityFocused"); }
    @Override public void setCheckable(boolean a0) { calls.add("setCheckable"); }
    @Override public void setChecked(boolean a0) { calls.add("setChecked"); }
    @Override public void setSelected(boolean a0) { calls.add("setSelected"); }
    @Override public void setActivated(boolean a0) { calls.add("setActivated"); }
    @Override public void setOpaque(boolean a0) { calls.add("setOpaque"); }
    @Override public void setClassName(java.lang.String a0) { calls.add("setClassName"); }
    @Override public void setContentDescription(java.lang.CharSequence a0) { calls.add("setContentDescription"); }
    @Override public void setText(java.lang.CharSequence a0) { calls.add("setText"); }
    @Override public void setText(java.lang.CharSequence a0, int a1, int a2) { calls.add("setText"); }
    @Override public void setTextStyle(float a0, int a1, int a2, int a3) { calls.add("setTextStyle"); }
    @Override public void setTextLines(int[] a0, int[] a1) { calls.add("setTextLines"); }
    @Override public void setHint(java.lang.CharSequence a0) { calls.add("setHint"); }
    @Override public java.lang.CharSequence getText() { calls.add("getText"); return null; }
    @Override public int getTextSelectionStart() { calls.add("getTextSelectionStart"); return 0; }
    @Override public int getTextSelectionEnd() { calls.add("getTextSelectionEnd"); return 0; }
    @Override public java.lang.CharSequence getHint() { calls.add("getHint"); return null; }
    @Override public android.os.Bundle getExtras() { calls.add("getExtras"); return null; }
    @Override public boolean hasExtras() { calls.add("hasExtras"); return false; }
    @Override public void setChildCount(int a0) { calls.add("setChildCount"); }
    @Override public int addChildCount(int a0) { calls.add("addChildCount"); return 0; }
    @Override public int getChildCount() { calls.add("getChildCount"); return 0; }
    @Override public android.view.ViewStructure newChild(int a0) { calls.add("newChild"); return null; }
    @Override public android.view.ViewStructure asyncNewChild(int a0) { calls.add("asyncNewChild"); return null; }
    @Override public android.view.autofill.AutofillId getAutofillId() { calls.add("getAutofillId"); return null; }
    @Override public void setAutofillId(android.view.autofill.AutofillId a0) { calls.add("setAutofillId"); recordedId = a0; }
    @Override public void setAutofillId(android.view.autofill.AutofillId a0, int a1) { calls.add("setAutofillId"); }
    @Override public void setAutofillType(int a0) { calls.add("setAutofillType"); }
    @Override public void setAutofillHints(java.lang.String[] a0) { calls.add("setAutofillHints"); }
    @Override public void setAutofillValue(android.view.autofill.AutofillValue a0) { calls.add("setAutofillValue"); }
    @Override public void setAutofillOptions(java.lang.CharSequence[] a0) { calls.add("setAutofillOptions"); }
    @Override public void setInputType(int a0) { calls.add("setInputType"); }
    @Override public void setDataIsSensitive(boolean a0) { calls.add("setDataIsSensitive"); }
    @Override public void asyncCommit() { calls.add("asyncCommit"); }
    public android.graphics.Rect getTempRect() { calls.add("getTempRect"); return null; }
    @Override public void setWebDomain(java.lang.String a0) { calls.add("setWebDomain"); }
    @Override public void setLocaleList(android.os.LocaleList a0) { calls.add("setLocaleList"); }
    @Override public android.view.ViewStructure.HtmlInfo.Builder newHtmlInfoBuilder(java.lang.String a0) { calls.add("newHtmlInfoBuilder"); return null; }
    @Override public void setHtmlInfo(android.view.ViewStructure.HtmlInfo a0) { calls.add("setHtmlInfo"); }
}
