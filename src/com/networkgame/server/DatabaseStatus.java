package com.networkgame.server;

import com.mongodb.client.MongoClients;
import org.bson.Document;

/** Read-only local connectivity and migration check. */
public final class DatabaseStatus {
    public static void main(String[] args) {
        String uri = System.getenv().getOrDefault("GAME_MONGO_URI", "mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=5000");
        String name = System.getenv().getOrDefault("GAME_MONGO_DATABASE", "police_robber");
        try (var client = MongoClients.create(uri)) {
            var db = client.getDatabase(name);
            System.out.println("MongoDB ping: " + db.runCommand(new Document("ping", 1)).get("ok"));
            System.out.println("Database: " + name);
            System.out.println("Users: " + db.getCollection("users").countDocuments());
            System.out.println("Matches: " + db.getCollection("matches").countDocuments());
            System.out.println("Legacy import completed: " + (db.getCollection("metadata").find(new Document("_id", "tsv-import-v1")).first() != null));
        }
    }
}
