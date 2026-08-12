package com.orbit.assistant;

import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.service.voice.VoiceInteractionSession;
import android.text.TextUtils;

public final class ScreenContextExtractor {
    private static final int MAX = 18000;
    private ScreenContextExtractor() {}

    public static String extract(VoiceInteractionSession.AssistState state) {
        StringBuilder out = new StringBuilder();
        try {
            AssistContent content = state.getAssistContent();
            if (content != null) {
                if (content.getWebUri() != null) append(out, "URL: " + content.getWebUri());
                if (content.getIntent() != null) {
                    android.content.Intent intent = content.getIntent();
                    ComponentName component = intent.getComponent();
                    if (component != null && component.getPackageName() != null) {
                        append(out, "APP_PACKAGE: " + component.getPackageName());
                    } else if (intent.getPackage() != null) {
                        append(out, "APP_PACKAGE: " + intent.getPackage());
                    }
                    if (intent.getDataString() != null) append(out, "Intent data: " + intent.getDataString());
                }
            }
            AssistStructure structure = state.getAssistStructure();
            if (structure != null) {
                ComponentName activity = structure.getActivityComponent();
                if (activity != null && activity.getPackageName() != null) {
                    append(out, "APP_PACKAGE: " + activity.getPackageName());
                }
                int windows = structure.getWindowNodeCount();
                for (int i = 0; i < windows && out.length() < MAX; i++) {
                    AssistStructure.WindowNode window = structure.getWindowNodeAt(i);
                    if (window != null) walk(window.getRootViewNode(), out, 0);
                }
            }
        } catch (Throwable ignored) {}
        return out.toString().trim();
    }

    private static void walk(AssistStructure.ViewNode node, StringBuilder out, int depth) {
        if (node == null || out.length() >= MAX || depth > 60) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String id = node.getIdEntry();
        if (!TextUtils.isEmpty(text)) append(out, text.toString());
        if (!TextUtils.isEmpty(desc) && (text == null || !desc.toString().contentEquals(text))) append(out, "[" + desc + "]");
        if (id != null && (id.toLowerCase().contains("title") || id.toLowerCase().contains("url"))) append(out, "{" + id + "}");
        int count = node.getChildCount();
        for (int i = 0; i < count && out.length() < MAX; i++) walk(node.getChildAt(i), out, depth + 1);
    }

    private static void append(StringBuilder out, String value) {
        if (value == null) return;
        String v = value.replaceAll("\\s+", " ").trim();
        if (v.isEmpty()) return;
        if (out.length() > 0) out.append('\n');
        int remaining = MAX - out.length();
        if (remaining <= 0) return;
        out.append(v, 0, Math.min(v.length(), remaining));
    }
}
