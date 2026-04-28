package com.bdic;

import com.bdic.crypto.PEKSUtil;
import com.bdic.db.DatabaseManager;
import com.bdic.db.EncryptedDataRepository;
import com.bdic.model.EncryptedData;
import com.mysql.cj.jdbc.AbandonedConnectionCleanupThread;
import junit.framework.TestCase;

import javax.crypto.SecretKey;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class DatabaseRepositoryTest extends TestCase {

    public void testStorageDocumentIdsAreScopedByUser() {
        String aliceDoc = EncryptedDataRepository.toStorageDocId("alice", "shared-doc");
        String bobDoc = EncryptedDataRepository.toStorageDocId("bob", "shared-doc");

        assertFalse(aliceDoc.equals(bobDoc));
        assertTrue(aliceDoc.startsWith("doc-"));
        assertEquals(aliceDoc, EncryptedDataRepository.toStorageDocId("alice", "shared-doc"));
    }

    public void testRepositoryRoundTripWhenDatabaseIntegrationIsEnabled() throws Exception {
        if (!Boolean.getBoolean("se.integration.db")) {
            return;
        }

        String host = System.getProperty("se.db.host", "localhost");
        int port = Integer.parseInt(System.getProperty("se.db.port", "3306"));
        String databaseName = System.getProperty("se.db.name", "searchable_encryption_test");
        String username = System.getProperty("se.db.user", "root");
        String password = System.getProperty("se.db.password", "123456ysy");
        String ownerUsername = "repository_test_user";

        try {
            DatabaseManager databaseManager = new DatabaseManager(host, port, databaseName, username, password);
            databaseManager.initialize();
            EncryptedDataRepository repository = new EncryptedDataRepository(databaseManager);
            repository.deleteByOwnerAndDocId(ownerUsername, "doc-1");
            repository.deleteByOwnerAndDocId(ownerUsername, "doc-2");
            repository.deleteByOwnerAndDocId(ownerUsername, "doc-3");

            try (Connection connection = databaseManager.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM users WHERE username = '" + ownerUsername + "'");
                statement.executeUpdate(
                        "INSERT INTO users (username, password_hash, password_salt) VALUES ('" + ownerUsername + "', X'01', X'01')"
                );
            }

            SecretKey peksKey = PEKSUtil.generateKey();

            EncryptedData firstDocument = new EncryptedData(
                    "doc-1",
                    "encrypted-1".getBytes(),
                    encryptKeywords(peksKey, "alpha", "beta")
            );

            EncryptedData secondDocument = new EncryptedData(
                    "doc-2",
                    "encrypted-2".getBytes(),
                    encryptKeywords(peksKey, "gamma")
            );

            EncryptedData binaryDocument = new EncryptedData(
                    "doc-3",
                    "screenshot.png",
                    "image/png",
                    "image",
                    3,
                    null,
                    new byte[]{0x01, 0x02, 0x03},
                    encryptKeywords(peksKey, "screenshot")
            );

            repository.save(ownerUsername, firstDocument);
            repository.save(ownerUsername, secondDocument);
            repository.save(ownerUsername, binaryDocument);

            byte[] trapdoor = PEKSUtil.getTrapdoor(peksKey, "alpha");
            List<EncryptedData> searchResults = repository.searchByTrapdoor(ownerUsername, trapdoor);
            byte[] prefixTrapdoor = PEKSUtil.getTrapdoor(peksKey, "alp");
            List<EncryptedData> prefixSearchResults = repository.searchByTrapdoor(ownerUsername, prefixTrapdoor);
            byte[] imageTrapdoor = PEKSUtil.getTrapdoor(peksKey, "screenshot");
            List<EncryptedData> imageSearchResults = repository.searchByTrapdoor(ownerUsername, imageTrapdoor);

            assertEquals(1, searchResults.size());
            assertEquals("doc-1", searchResults.get(0).getDocId());
            assertEquals(1, prefixSearchResults.size());
            assertEquals("doc-1", prefixSearchResults.get(0).getDocId());
            assertEquals(1, imageSearchResults.size());
            assertEquals("doc-3", imageSearchResults.get(0).getDocId());
            assertNull(imageSearchResults.get(0).getEncryptedContent());
        } finally {
            AbandonedConnectionCleanupThread.checkedShutdown();
        }
    }

    private static List<byte[]> encryptKeywords(SecretKey peksKey, String... keywords) throws Exception {
        Set<String> tokens = new LinkedHashSet<>();
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }

            String normalizedKeyword = keyword.trim().toLowerCase();
            tokens.add(normalizedKeyword);
            if (normalizedKeyword.length() <= 2) {
                continue;
            }

            for (int i = 2; i < normalizedKeyword.length(); i++) {
                tokens.add(normalizedKeyword.substring(0, i));
            }
        }

        List<byte[]> encryptedKeywords = new ArrayList<>();
        for (String token : tokens) {
            encryptedKeywords.add(PEKSUtil.encrypt(peksKey, token));
        }
        return encryptedKeywords;
    }
}
