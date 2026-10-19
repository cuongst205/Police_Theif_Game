package com.networkgame.server;

import com.mongodb.client.MongoClients;
import org.bson.Document;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

public final class MongoRepositoryTest {
    public static void main(String[] args) throws Exception {
        String database = "police_robber_repository_test_" + System.currentTimeMillis();
        Path fixture = Files.createTempDirectory(Path.of("."), "mongo-migration-test-");
        String legacyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("oldpass".getBytes(StandardCharsets.UTF_8)));
        Files.writeString(fixture.resolve("users.tsv"), "legacy\t" + legacyHash + "\t7\t1\t0\t1\n");
        Files.writeString(fixture.resolve("matches.tsv"), "2026-10-01T00:00:00Z\told-match\tlegacy\topponent\t7\t0\tlegacy\t6\tSCORE\n");
        String uri = "mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=5000";
        try {
            try (UserRepository repo = new UserRepository(fixture, uri, database)) {
                check(repo.scoreOf("legacy") == 7, "Migration must not double-count history");
                check(repo.verify("legacy", "oldpass"), "Old password works");
                check(!repo.verify("legacy", "wrong"), "Wrong password denied");
                check(repo.register("newuser", "newpass"), "Registration succeeds");
                check(!repo.register("newuser", "newpass"), "Username unique");
                check(repo.verify("newuser", "newpass"), "New password works");
                List<Document> rounds = List.of(new Document("number", 1).append("policeMove", "LEFT").append("thiefMove", "RIGHT"));
                repo.recordMatch("new-match", "legacy", "newuser", 2, 4, "newuser", 6, "SCORE", Instant.now(), rounds);
                repo.recordMatch("new-match", "legacy", "newuser", 2, 4, "newuser", 6, "SCORE", Instant.now(), rounds);
                check(repo.scoreOf("legacy") == 9, "Retry must not double-count");
                UserRecord legacy = repo.ranking().stream().filter(u -> u.username.equals("legacy")).findFirst().orElseThrow();
                check(legacy.matches == 2 && legacy.wins == 1 && legacy.losses == 1, "Win/loss totals derived correctly");
                check(repo.scoreOf("newuser") == 4, "Opponent points persisted");
            }
            try (UserRepository reopened = new UserRepository(fixture, uri, database)) {
                check(reopened.scoreOf("legacy") == 9, "Reopening must not reimport or lose scores");
                check(reopened.verify("legacy", "oldpass"), "Upgraded password persists");
            }
            try (var client = MongoClients.create(uri)) {
                var db = client.getDatabase(database);
                check(db.getCollection("matches").countDocuments() == 2, "History contains exactly two matches");
                check(db.getCollection("users").find(new Document("_id", "legacy")).first().getString("passwordHash").startsWith("pbkdf2$"), "Legacy password upgraded");
                check(db.getCollection("matches").find(new Document("_id", "new-match")).first().getList("rounds", Document.class).size() == 1, "Round details saved");
            }
            System.out.println("PASS migration, password upgrade, duplicate user, match retry, standings, restart, embedded rounds");
        } finally {
            // Only this test's uniquely named database is deleted.
            try (var client = MongoClients.create(uri)) { client.getDatabase(database).drop(); }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
