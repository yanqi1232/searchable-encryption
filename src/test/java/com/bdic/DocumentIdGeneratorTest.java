package com.bdic;

import com.bdic.model.DocumentIdGenerator;
import junit.framework.TestCase;

import java.util.HashSet;
import java.util.Set;

public class DocumentIdGeneratorTest extends TestCase {

    public void testGeneratedDocumentIdFormatAndUniqueness() {
        Set<String> generatedIds = new HashSet<>();

        for (int i = 0; i < 1000; i++) {
            String docId = DocumentIdGenerator.generate();

            assertTrue(docId.matches("doc-[0-9a-f]{32}"));
            assertTrue(generatedIds.add(docId));
        }
    }
}
