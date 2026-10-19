package com.networkgame.server;

import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Portable logical snapshot; BSON types are preserved with MongoDB Extended JSON. */
public final class DatabaseSnapshot {
    private static final JsonWriterSettings JSON = JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || !(args[0].equals("export") || args[0].equals("restore"))) {
            throw new IllegalArgumentException("Usage: DatabaseSnapshot export|restore [snapshot-directory]");
        }
        Path directory = Path.of(args.length > 1 ? args[1] : "database/seed");
        String uri = System.getenv().getOrDefault("GAME_MONGO_URI", "mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=5000");
        String database = System.getenv().getOrDefault("GAME_MONGO_DATABASE", "police_robber");
        try (var client = MongoClients.create(uri)) {
            MongoDatabase db = client.getDatabase(database);
            db.runCommand(new Document("ping", 1));
            if (args[0].equals("export")) exportData(db, directory);
            else restoreData(db, directory);
        }
    }

    static void exportData(MongoDatabase db, Path directory) throws IOException {
        Files.createDirectories(directory);
        List<String> collections = db.listCollectionNames().into(new ArrayList<>());
        Collections.sort(collections);
        List<Document> manifestEntries = new ArrayList<>();
        for (String name : collections) {
            validateName(name);
            var collection = db.getCollection(name);
            List<String> lines = new ArrayList<>();
            for (Document document : collection.find().sort(new Document("_id", 1))) lines.add(document.toJson(JSON));
            Files.write(directory.resolve(name + ".jsonl"), lines, StandardCharsets.UTF_8);
            manifestEntries.add(new Document("name", name).append("documents", lines.size())
                .append("indexes", collection.listIndexes().into(new ArrayList<>())));
            System.out.println("Exported " + name + ": " + lines.size() + " documents");
        }
        Document manifest = new Document("formatVersion", 1).append("database", db.getName())
            .append("exportedAt", new Date()).append("collections", manifestEntries);
        Files.writeString(directory.resolve("manifest.json"), manifest.toJson(JSON), StandardCharsets.UTF_8);
    }

    static void restoreData(MongoDatabase db, Path directory) throws IOException {
        Document manifest = Document.parse(Files.readString(directory.resolve("manifest.json"), StandardCharsets.UTF_8));
        if (manifest.getInteger("formatVersion", 0) != 1) throw new IllegalArgumentException("Unsupported snapshot format");
        for (String name : db.listCollectionNames()) {
            if (db.getCollection(name).countDocuments() > 0) {
                throw new IllegalStateException("Restore requires an empty database. Use a new GAME_MONGO_DATABASE; existing data is preserved.");
            }
        }
        Map<String, List<Document>> payload = new LinkedHashMap<>();
        List<Document> entries = manifest.getList("collections", Document.class);
        for (Document entry : entries) {
            String name = entry.getString("name"); validateName(name);
            List<Document> documents = new ArrayList<>();
            for (String line : Files.readAllLines(directory.resolve(name + ".jsonl"), StandardCharsets.UTF_8)) {
                if (!line.isBlank()) documents.add(Document.parse(line));
            }
            if (documents.size() != entry.getInteger("documents")) throw new IllegalArgumentException("Document count mismatch: " + name);
            payload.put(name, documents);
        }
        for (Document entry : entries) {
            String name = entry.getString("name");
            var collection = db.getCollection(name);
            if (!payload.get(name).isEmpty()) collection.insertMany(payload.get(name));
            for (Document index : entry.getList("indexes", Document.class)) {
                if ("_id_".equals(index.getString("name"))) continue;
                collection.createIndex(index.get("key", Document.class), new IndexOptions().name(index.getString("name"))
                    .unique(index.getBoolean("unique", false)).sparse(index.getBoolean("sparse", false)));
            }
            System.out.println("Restored " + name + ": " + payload.get(name).size() + " documents");
        }
    }

    private static void validateName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Unsupported collection name");
    }
}
