package com.nexora.connect;

import java.util.Set;
import java.util.regex.Pattern;

/** Everything that comes from outside is cleaned here before it reaches the game. */
final class Text {
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1f\\x7f\\u00a7]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern SECTION_CODES = Pattern.compile("\\u00a7x(\\u00a7[0-9a-fA-F]){6}|\\u00a7[0-9a-fk-orA-FK-OR]");
    private static final Pattern COLOR_CODES = Pattern.compile("[\\u00a7&][0-9a-fk-orA-FK-OR]|&#[0-9a-fA-F]{6}|\\u00a7x(\\u00a7[0-9a-fA-F]){6}");
    static final Pattern PLAYER = Pattern.compile("[A-Za-z0-9_.*-]{1,32}");
    static final Set<String> TELL_COLORS = Set.of("green", "red", "gray", "white", "yellow", "aqua");

    private Text() {}

    /** One line, no colour codes or control characters, at most max characters. */
    static String line(String s, int max) {
        if (s == null) return "";
        String t = SPACES.matcher(CONTROL.matcher(SECTION_CODES.matcher(s).replaceAll("")).replaceAll(" ")).replaceAll(" ").trim();
        return t.length() <= max ? t : t.substring(0, max - 1).trim() + "\u2026";
    }

    static String hex(String c) {
        return c != null && HEX.matcher(c).matches() ? c : "#ffffff";
    }

    static String stripColors(String s) {
        return s == null ? "" : COLOR_CODES.matcher(s).replaceAll("");
    }
}
