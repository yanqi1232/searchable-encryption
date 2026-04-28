package com.bdic.model;

import java.security.SecureRandom;
import java.util.HexFormat;

public final class DocumentIdGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int RANDOM_BYTE_LENGTH = 16;

    private DocumentIdGenerator() {
    }

    public static String generate() {
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return "doc-" + HexFormat.of().formatHex(bytes);
    }
}
