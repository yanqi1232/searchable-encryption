package com.bdic;

import com.bdic.text.KeywordExtractor;
import junit.framework.TestCase;

import java.util.Arrays;
import java.util.List;

public class KeywordExtractorTest extends TestCase {

    public void testExtractWordsFromEnglishText() {
        List<String> keywords = KeywordExtractor.extractWords("searchable encryption protects data privacy");

        assertEquals(Arrays.asList("searchable", "encryption", "protects", "data", "privacy"), keywords);
    }

    public void testCommaSeparatedKeywordsAreNormalizedAndDeduplicated() {
        List<String> keywords = KeywordExtractor.extractCommaSeparated(" Alpha, beta , ALPHA,, a ");

        assertEquals(Arrays.asList("alpha", "beta"), keywords);
    }

    public void testExtractWordsAddsCjkBigrams() {
        List<String> keywords = KeywordExtractor.extractWords("\u53ef\u641c\u7d22\u52a0\u5bc6");

        assertTrue(keywords.contains("\u53ef\u641c\u7d22\u52a0\u5bc6"));
        assertTrue(keywords.contains("\u641c\u7d22"));
        assertTrue(keywords.contains("\u52a0\u5bc6"));
    }
}
