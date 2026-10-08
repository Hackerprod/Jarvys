package com.jarvys.agent.crew;

/** Display metadata only. Meaning comes from the captain, never from keyword or role rules. */
public final class CrewMissionTitle {
    public static final int MAX_CODE_POINTS = 60;
    public static final String AGENT = "agent";
    public static final String LEGACY = "legacy";
    public static final String FALLBACK = "fallback";
    private static final CrewMissionTitle EMPTY = new CrewMissionTitle("", FALLBACK);

    public final String title;
    public final String source;

    private CrewMissionTitle(String title, String source) {
        this.title = title;
        this.source = source;
    }

    public static CrewMissionTitle fallback() { return EMPTY; }

    public static CrewMissionTitle agent(Object candidate) {
        return validated(candidate, AGENT);
    }

    public static CrewMissionTitle legacy(Object candidate) {
        return validated(candidate, LEGACY);
    }

    public static CrewMissionTitle stored(Object candidate, String source) {
        return AGENT.equals(source) || LEGACY.equals(source) ? validated(candidate, source) : EMPTY;
    }

    private static CrewMissionTitle validated(Object candidate, String source) {
        if (!(candidate instanceof String)) return EMPTY;
        String value = (String) candidate;
        StringBuilder normalized = new StringBuilder();
        boolean space = false;
        boolean hasBaseCharacter = false;
        int count = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                space = normalized.length() > 0;
                continue;
            }
            // Reject malformed Unicode and hidden control characters. Punctuation is plain text;
            // this validates presentation syntax without interpreting markup or task meaning.
            if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.SURROGATE
                    || (Character.getType(codePoint) == Character.FORMAT && codePoint != 0x200C && codePoint != 0x200D)) return EMPTY;
            if (space) {
                normalized.append(' ');
                count++;
                space = false;
            }
            normalized.appendCodePoint(codePoint);
            int type = Character.getType(codePoint);
            hasBaseCharacter |= type != Character.FORMAT && type != Character.NON_SPACING_MARK
                    && type != Character.COMBINING_SPACING_MARK && type != Character.ENCLOSING_MARK;
            if (++count > MAX_CODE_POINTS) return EMPTY;
        }
        return !hasBaseCharacter ? EMPTY : new CrewMissionTitle(normalized.toString(), source);
    }
}
