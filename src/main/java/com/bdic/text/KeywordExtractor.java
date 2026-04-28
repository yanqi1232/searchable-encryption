package com.bdic.text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KeywordExtractor {

    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final int MIN_TOKEN_LENGTH = 2;

    private KeywordExtractor() {
    }

    public static List<String> extractCommaSeparated(String input) {
        Set<String> keywords = new LinkedHashSet<>();
        if (input == null || input.isBlank()) {
            return new ArrayList<>();
        }

        for (String rawKeyword : input.split(",")) {
            addKeyword(keywords, rawKeyword);
        }
        return new ArrayList<>(keywords);
    }

    public static List<String> extractWords(String text) {
        Set<String> keywords = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }

        Matcher matcher = WORD_PATTERN.matcher(text);
        while (matcher.find()) {
            String word = normalize(matcher.group());
            if (!addKeyword(keywords, word) || !containsCjk(word)) {
                continue;
            }
            addCjkBigrams(keywords, word);
        }
        return new ArrayList<>(keywords);
    }

    public static List<String> extractFileNameKeywords(String fileName) {
        Set<String> keywords = new LinkedHashSet<>();
        if (fileName == null || fileName.isBlank()) {
            return new ArrayList<>();
        }

        String normalizedName = fileName.trim();
        int extensionSeparator = normalizedName.lastIndexOf('.');
        String baseName = extensionSeparator > 0 ? normalizedName.substring(0, extensionSeparator) : normalizedName;
        String extension = extensionSeparator > 0 && extensionSeparator < normalizedName.length() - 1
                ? normalizedName.substring(extensionSeparator + 1)
                : "";

        keywords.addAll(extractWords(baseName.replaceAll("[_\\-\\.\\(\\)\\[\\]\\{\\}]+", " ")));
        addKeyword(keywords, baseName);
        addKeyword(keywords, extension);
        return new ArrayList<>(keywords);
    }

    private static boolean addKeyword(Set<String> keywords, String rawKeyword) {
        String keyword = normalize(rawKeyword);
        if (keyword.length() < MIN_TOKEN_LENGTH) {
            return false;
        }
        keywords.add(keyword);
        return true;
    }

    private static String normalize(String rawKeyword) {
        if (rawKeyword == null) {
            return "";
        }
        return rawKeyword.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsCjk(String word) {
        return word.codePoints().anyMatch(KeywordExtractor::isCjkCodePoint);
    }

    private static boolean isCjkCodePoint(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static void addCjkBigrams(Set<String> keywords, String word) {
        int[] codePoints = word.codePoints().toArray();
        if (codePoints.length <= MIN_TOKEN_LENGTH) {
            return;
        }

        for (int i = 0; i < codePoints.length - 1; i++) {
            String bigram = new String(codePoints, i, 2);
            addKeyword(keywords, bigram);
        }
    }
}
