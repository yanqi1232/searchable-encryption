package com.bdic;

import com.bdic.crypto.DESUtil;
import com.bdic.crypto.PEKSUtil;
import com.bdic.crypto.PasswordUtil;
import junit.framework.TestCase;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

public class CryptoTest extends TestCase {

    public void testDesRoundTrip() throws Exception {
        SecretKey desKey = DESUtil.generateKey();
        String plaintext = "Hello World Data";

        byte[] encrypted = DESUtil.encrypt(plaintext.getBytes(StandardCharsets.UTF_8), desKey);
        byte[] decrypted = DESUtil.decrypt(encrypted, desKey);

        assertEquals(plaintext, new String(decrypted, StandardCharsets.UTF_8));
    }

    public void testPeksTrapdoorMatchesOnlySameKeyword() throws Exception {
        SecretKey peksKey = PEKSUtil.generateKey();

        byte[] peksCiphertext = PEKSUtil.encrypt(peksKey, "Secret");
        byte[] trapdoor = PEKSUtil.getTrapdoor(peksKey, "secret");
        byte[] wrongTrapdoor = PEKSUtil.getTrapdoor(peksKey, "wrong");

        assertTrue(PEKSUtil.test(peksCiphertext, trapdoor));
        assertFalse(PEKSUtil.test(peksCiphertext, wrongTrapdoor));
    }

    public void testPasswordHashVerification() {
        byte[] salt = PasswordUtil.generateSalt();
        byte[] hash = PasswordUtil.hashPassword("correct horse battery staple", salt);

        assertTrue(PasswordUtil.matches("correct horse battery staple", salt, hash));
        assertFalse(PasswordUtil.matches("wrong password", salt, hash));
    }
}
