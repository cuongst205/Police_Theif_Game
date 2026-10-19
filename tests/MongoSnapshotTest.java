package com.networkgame.server;

import com.mongodb.client.MongoClients;
import org.bson.Document;
import java.nio.file.Path;
import java.util.ArrayList;

public final class MongoSnapshotTest {
    public static void main(String[] args) throws Exception {
        String uri = "mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=5000";
        String testName = "police_robber_snapshot_test_" + System.currentTimeMillis();
        Path snapshot = Path.of("database", "seed");
        try (var client = MongoClients.create(uri)) {
            var source = client.getDatabase("police_robber");
            var restored = client.getDatabase(testName);
            try {
                DatabaseSnapshot.restoreData(restored, snapshot);
                for (String name : source.listCollectionNames()) {
                    var original = source.getCollection(name).find().sort(new Document("_id", 1)).into(new ArrayList<>());
                    var copy = restored.getCollection(name).find().sort(new Document("_id", 1)).into(new ArrayList<>());
                    if (!original.equals(copy)) throw new AssertionError("Snapshot mismatch: " + name);
                    long originalIndexes = source.getCollection(name).listIndexes().into(new ArrayList<>()).size();
                    long copyIndexes = restored.getCollection(name).listIndexes().into(new ArrayList<>()).size();
                    if (originalIndexes != copyIndexes) throw new AssertionError("Missing index: " + name);
                }
                boolean refused = false;
                try { DatabaseSnapshot.restoreData(restored, snapshot); }
                catch (IllegalStateException expected) { refused = true; }
                if (!refused) throw new AssertionError("Restore must refuse nonempty databases");
                try (var repo = new UserRepository(Path.of("data"), uri, testName)) {
                    if (repo.ranking().size() != 2) throw new AssertionError("Game cannot read restored accounts");
                }
                System.out.println("PASS full snapshot restore: all documents and indexes match; dates/hashes preserved; existing data protected; game reads restored users");
            } finally {
                restored.drop();
            }
        }
    }
}
