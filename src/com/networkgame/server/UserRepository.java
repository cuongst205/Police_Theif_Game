package com.networkgame.server;

import com.mongodb.MongoWriteException;
import com.mongodb.client.*;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import static com.mongodb.client.model.Filters.eq;

/** Users plus immutable match documents; standings are derived from match results. */
public final class UserRepository implements AutoCloseable {
    private final MongoClient client;
    private final MongoDatabase database;
    private final MongoCollection<Document> users, matches;
    private final SecureRandom random = new SecureRandom();

    public UserRepository(Path legacyDirectory) {
        this(legacyDirectory, System.getenv().getOrDefault("GAME_MONGO_URI", "mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=5000"),
             System.getenv().getOrDefault("GAME_MONGO_DATABASE", "police_robber"));
    }

    public UserRepository(Path legacyDirectory, String uri, String name) {
        client = MongoClients.create(uri);
        database = client.getDatabase(name);
        users = database.getCollection("users");
        matches = database.getCollection("matches");
        try {
            database.runCommand(new Document("ping", 1));
            // Usernames and match IDs are MongoDB _id values, already uniquely indexed.
            matches.createIndex(Indexes.ascending("participants.username"));
            matches.createIndex(Indexes.descending("endedAt"));
            importLegacy(legacyDirectory);
            System.out.println("MongoDB connected: " + name);
        } catch (RuntimeException ex) {
            client.close();
            throw new IllegalStateException("MongoDB is unavailable or migration failed. Start MongoDB and check GAME_MONGO_URI.", ex);
        }
    }

    public boolean register(String username, String password) {
        if (!username.matches("[A-Za-z0-9_]{3,20}") || password.length() < 3) return false;
        try {
            users.insertOne(new Document("_id", username).append("username", username)
                .append("passwordHash", hash(password)).append("createdAt", new Date())
                .append("legacyStats", stats(0, 0, 0, 0)));
            return true;
        } catch (MongoWriteException ex) {
            if (ex.getError().getCode() == 11000) return false;
            throw ex;
        }
    }

    public boolean verify(String username, String password) {
        Document user = users.find(eq("_id", username)).first();
        if (user == null) return false;
        String stored = user.getString("passwordHash");
        if (stored.startsWith("pbkdf2$")) {
            String[] pieces = stored.split("\\$");
            byte[] salt = Base64.getDecoder().decode(pieces[2]);
            byte[] actual = derive(password, salt, Integer.parseInt(pieces[1]));
            return MessageDigest.isEqual(Base64.getDecoder().decode(pieces[3]), actual);
        }
        if (!MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), sha256(password).getBytes(StandardCharsets.UTF_8))) return false;
        users.updateOne(eq("_id", username), new Document("$set", new Document("passwordHash", hash(password))));
        return true;
    }

    public int scoreOf(String username) {
        return standings(username).stream().findFirst().map(u -> u.totalPoints).orElse(0);
    }

    public List<UserRecord> ranking() {
        List<UserRecord> result = standings(null);
        result.sort(Comparator.comparingInt((UserRecord u) -> u.totalPoints).reversed()
            .thenComparing(Comparator.comparingInt((UserRecord u) -> u.wins).reversed())
            .thenComparing(u -> u.username));
        return result;
    }

    private List<UserRecord> standings(String username) {
        Map<String, int[]> totals = new HashMap<>();
        FindIterable<Document> found = username == null ? users.find() : users.find(eq("_id", username));
        for (Document user : found) {
            Document base = user.get("legacyStats", Document.class);
            totals.put(user.getString("username"), new int[] {base.getInteger("points", 0), base.getInteger("wins", 0), base.getInteger("losses", 0), base.getInteger("matches", 0)});
        }
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", new Document("countForRanking", true)));
        pipeline.add(new Document("$unwind", "$participants"));
        if (username != null) pipeline.add(new Document("$match", new Document("participants.username", username)));
        pipeline.add(new Document("$group", new Document("_id", "$participants.username")
            .append("points", new Document("$sum", "$participants.score"))
            .append("wins", new Document("$sum", new Document("$cond", List.of(new Document("$eq", List.of("$participants.result", "WIN")), 1, 0))))
            .append("losses", new Document("$sum", new Document("$cond", List.of(new Document("$eq", List.of("$participants.result", "LOSE")), 1, 0))))
            .append("matches", new Document("$sum", 1))));
        for (Document sum : matches.aggregate(pipeline)) {
            int[] target = totals.get(sum.getString("_id"));
            if (target != null) {
                String[] fields = {"points", "wins", "losses", "matches"};
                for (int i = 0; i < 4; i++) target[i] += ((Number) sum.get(fields[i])).intValue();
            }
        }
        List<UserRecord> result = new ArrayList<>();
        totals.forEach((name, values) -> result.add(new UserRecord(name, values[0], values[1], values[2], values[3])));
        return result;
    }

    public void recordMatch(String id, String first, String second, int firstScore, int secondScore, String winner,
                            int rounds, String reason, Instant startedAt, List<Document> roundHistory) {
        Document match = matchDocument(id, first, second, firstScore, secondScore, winner, rounds, reason, startedAt, Instant.now())
            .append("countForRanking", true).append("rounds", new ArrayList<>(roundHistory));
        // One atomic document write also becomes the source of ranking; retries cannot double-count.
        matches.updateOne(eq("_id", id), new Document("$setOnInsert", match), new UpdateOptions().upsert(true));
    }

    private Document matchDocument(String id, String first, String second, int a, int b, String winner,
                                   int rounds, String reason, Instant start, Instant end) {
        return new Document("_id", id).append("participants", List.of(participant(first, a, winner), participant(second, b, winner)))
            .append("winner", winner).append("roundsPlayed", rounds).append("reason", reason)
            .append("startedAt", start == null ? null : Date.from(start)).append("endedAt", Date.from(end));
    }

    private Document participant(String name, int score, String winner) {
        return new Document("username", name).append("score", score)
            .append("result", winner == null ? "DRAW" : winner.equals(name) ? "WIN" : "LOSE");
    }

    private void importLegacy(Path directory) {
        MongoCollection<Document> metadata = database.getCollection("metadata");
        if (metadata.find(eq("_id", "tsv-import-v1")).first() != null) return;
        try {
            Path oldUsers = directory.resolve("users.tsv"), oldMatches = directory.resolve("matches.tsv");
            if (Files.exists(oldUsers)) {
                for (String line : Files.readAllLines(oldUsers, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 6) throw new IllegalStateException("Invalid users.tsv row");
                    Document user = new Document("_id", fields[0]).append("username", fields[0]).append("passwordHash", fields[1])
                        .append("createdAt", new Date()).append("legacyStats", stats(Integer.parseInt(fields[2]), Integer.parseInt(fields[3]), Integer.parseInt(fields[4]), Integer.parseInt(fields[5])));
                    users.updateOne(eq("_id", fields[0]), new Document("$setOnInsert", user), new UpdateOptions().upsert(true));
                }
            }
            if (Files.exists(oldMatches)) {
                for (String line : Files.readAllLines(oldMatches, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    String[] f = line.split("\t", -1);
                    if (f.length != 9) throw new IllegalStateException("Invalid matches.tsv row");
                    Document match = matchDocument(f[1], f[2], f[3], Integer.parseInt(f[4]), Integer.parseInt(f[5]), "DRAW".equals(f[6]) ? null : f[6], Integer.parseInt(f[7]), f[8], null, Instant.parse(f[0]))
                        .append("countForRanking", false).append("rounds", List.of()).append("source", "legacy-tsv");
                    matches.updateOne(eq("_id", f[1]), new Document("$setOnInsert", match), new UpdateOptions().upsert(true));
                }
            }
            metadata.updateOne(eq("_id", "tsv-import-v1"), new Document("$setOnInsert", new Document("completedAt", new Date())), new UpdateOptions().upsert(true));
        } catch (IOException ex) { throw new IllegalStateException("Cannot read legacy TSV data", ex); }
    }

    private Document stats(int points, int wins, int losses, int games) {
        return new Document("points", points).append("wins", wins).append("losses", losses).append("matches", games);
    }

    private String hash(String password) {
        byte[] salt = new byte[16]; random.nextBytes(salt);
        return "pbkdf2$210000$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(derive(password, salt, 210000));
    }

    private byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException ex) { throw new IllegalStateException(ex); }
        finally { spec.clearPassword(); }
    }

    private String sha256(String password) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    @Override public void close() { client.close(); }
}
