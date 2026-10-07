package com.jarvys.agent;

public final class ImageEditInput {
    public final String base64;
    public final String mimeType;

    public ImageEditInput(String mimeType, String base64) {
        if (!"image/png".equals(mimeType) && !"image/jpeg".equals(mimeType)) {
            throw new IllegalArgumentException("Image edit references require PNG or JPEG data");
        }
        if (!isCanonicalBase64(base64)) {
            throw new IllegalArgumentException("Image edit references require image data");
        }
        this.mimeType = mimeType;
        this.base64 = base64;
    }

    static boolean isCanonicalBase64(String value) {
        int padding;
        int digit;
        if (value == null || value.isEmpty() || value.length() % 4 != 0) {
            return false;
        }
        if (value.endsWith("==")) {
            padding = 2;
        } else {
            padding = value.endsWith("=") ? 1 : 0;
        }
        for (int i = 0; i < value.length() - padding; i++) {
            char c = value.charAt(i);
            if ((c < 'A' || c > 'Z') && ((c < 'a' || c > 'z') && ((c < '0' || c > '9') && c != '+' && c != '/'))) {
                return false;
            }
        }
        int i2 = value.length();
        if (i2 <= padding) {
            return false;
        }
        if (padding > 0) {
            char last = value.charAt((value.length() - padding) - 1);
            if (last >= 'A' && last <= 'Z') {
                digit = last - 'A';
            } else if (last >= 'a' && last <= 'z') {
                digit = (last - 'a') + 26;
            } else if (last < '0' || last > '9') {
                digit = last == '+' ? 62 : 63;
            } else {
                digit = (last - '0') + 52;
            }
            if (((padding == 2 ? 15 : 3) & digit) != 0) {
                return false;
            }
        }
        return true;
    }

    public String dataUrl() {
        return "data:" + this.mimeType + ";base64," + this.base64;
    }
}
