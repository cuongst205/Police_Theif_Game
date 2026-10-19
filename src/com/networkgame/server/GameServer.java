package com.networkgame.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import org.bson.Document;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Server-authoritative implementation of the game rules. */
public final class GameServer {
    private static final int ROUND_SECONDS = 10;
    private final int port;
    private final UserRepository users = new UserRepository(Path.of("data"));
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private final Map<String, String> pendingInvites = new ConcurrentHashMap<>();
    private final Map<String, String> lastOpponent = new ConcurrentHashMap<>();
    private final Map<String, String> replayRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final MatchManager matches = new MatchManager();
    private final Random random = new Random();

    public GameServer(int port) {
        this.port = port;
        Runtime.getRuntime().addShutdownHook(new Thread(users::close, "close-mongodb"));
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Police vs Robber server listening on port " + port);
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(new ClientHandler(socket), "client-" + socket.getPort()).start();
            }
        }
    }

    private void handle(ClientHandler client, String raw) {
        String[] parts = raw.split(";", -1);
        String command = parts[0].trim().toUpperCase();
        try {
            switch (command) {
                case "REGISTER" -> register(client, parts);
                case "LOGIN" -> login(client, parts);
                case "LOGOUT" -> client.close();
                case "GET_ONLINE" -> sendOnlineList(client);
                case "GET_RANKING" -> sendRanking(client);
                case "INVITE" -> invite(client, parts);
                case "ACCEPT" -> acceptInvite(client, parts);
                case "REJECT" -> rejectInvite(client, parts);
                case "MOVE" -> move(client, parts);
                case "PUZZLE_ANSWER" -> puzzleAnswer(client, parts);
                case "EXIT_GAME" -> exitGame(client);
                case "PLAY_AGAIN" -> playAgain(client);
                default -> client.send("ERROR;Unknown command");
            }
        } catch (RuntimeException ex) {
            ex.printStackTrace();
            client.send("ERROR;Request could not be processed");
        }
    }

    private void register(ClientHandler client, String[] parts) {
        if (parts.length < 3) {
            client.send("ERROR;Usage REGISTER;username;password");
            return;
        }
        if (users.register(parts[1], parts[2])) {
            client.send("REGISTER_OK;Account created. Please log in.");
        } else {
            client.send("ERROR;Username is unavailable or invalid");
        }
    }

    private void login(ClientHandler client, String[] parts) {
        if (parts.length < 3 || client.username != null) {
            client.send("ERROR;Invalid login request");
            return;
        }
        String username = parts[1];
        if (!users.verify(username, parts[2])) {
            client.send("ERROR;Invalid username or password");
            return;
        }
        if (clients.putIfAbsent(username, client) != null) {
            client.send("ERROR;This account is already online");
            return;
        }
        client.username = username;
        matches.playerReconnected(username);
        client.send("LOGIN_OK;" + username + ";" + users.scoreOf(username));
        broadcastOnlineList();
    }

    private boolean requireLogin(ClientHandler client) {
        if (client.username == null) {
            client.send("ERROR;Please log in first");
            return false;
        }
        return true;
    }

    private void invite(ClientHandler client, String[] parts) {
        if (!requireLogin(client) || parts.length < 2) return;
        String target = parts[1];
        if (target.equals(client.username)) {
            client.send("ERROR;You cannot challenge yourself");
            return;
        }
        ClientHandler targetClient = clients.get(target);
        if (targetClient == null || matches.inMatch(target)) {
            client.send("ERROR;Opponent is unavailable");
            return;
        }
        if (matches.inMatch(client.username) || pendingInvites.containsValue(client.username)) {
            client.send("ERROR;You are already waiting for a game");
            return;
        }
        if (pendingInvites.putIfAbsent(target, client.username) != null) {
            client.send("ERROR;Opponent already has an invitation");
            return;
        }
        targetClient.send("INVITE_FROM;" + client.username);
        client.send("INVITE_SENT;" + target);
    }

    private void acceptInvite(ClientHandler client, String[] parts) {
        if (!requireLogin(client) || parts.length < 2) return;
        String challenger = parts[1];
        if (!challenger.equals(pendingInvites.remove(client.username))) {
            client.send("ERROR;Invitation expired");
            return;
        }
        if (!clients.containsKey(challenger) || matches.inMatch(client.username) || matches.inMatch(challenger)) {
            client.send("ERROR;Opponent is unavailable");
            return;
        }
        matches.createMatch(challenger, client.username);
    }

    private void rejectInvite(ClientHandler client, String[] parts) {
        if (!requireLogin(client) || parts.length < 2) return;
        String challenger = parts[1];
        if (challenger.equals(pendingInvites.remove(client.username))) {
            sendTo(challenger, "INVITE_REJECTED;" + client.username);
            client.send("INVITE_REJECTED_OK;" + challenger);
        } else {
            client.send("ERROR;Invitation expired");
        }
    }

    private void move(ClientHandler client, String[] parts) {
        if (!requireLogin(client) || parts.length < 3) return;
        Direction direction = Direction.parse(parts[1]);
        if (direction == null) {
            client.send("ERROR;Direction must be LEFT, MIDDLE or RIGHT");
            return;
        }
        matches.move(client.username, direction, parseInt(parts[2], -1));
    }

    private void puzzleAnswer(ClientHandler client, String[] parts) {
        if (!requireLogin(client) || parts.length < 3) return;
        matches.puzzleAnswer(client.username, parseInt(parts[1], -1), parts[2]);
    }

    private void exitGame(ClientHandler client) {
        if (requireLogin(client)) matches.exit(client.username);
    }

    private void playAgain(ClientHandler client) {
        if (!requireLogin(client)) return;
        String opponent = lastOpponent.get(client.username);
        if (opponent == null || !clients.containsKey(opponent) || matches.inMatch(opponent)) {
            client.send("RETURN_TO_LOBBY;Opponent is unavailable");
            return;
        }
        String requestedBy = replayRequests.put(client.username, opponent);
        if (client.username.equals(replayRequests.get(opponent))) {
            replayRequests.remove(client.username);
            replayRequests.remove(opponent);
            matches.createMatch(client.username, opponent);
        } else {
            client.send("WAITING_REPLAY;" + opponent);
        }
    }

    private void sendOnlineList(ClientHandler client) {
        List<String> names = new ArrayList<>(clients.keySet());
        Collections.sort(names);
        List<String> entries = new ArrayList<>();
        for (String name : names) {
            entries.add(name + "," + users.scoreOf(name) + "," + (matches.inMatch(name) ? "PLAYING" : "IDLE"));
        }
        client.send("ONLINE_LIST;" + String.join("|", entries));
    }

    private void broadcastOnlineList() {
        for (ClientHandler client : clients.values()) sendOnlineList(client);
    }

    private void sendRanking(ClientHandler client) {
        List<UserRecord> ranking = users.ranking();
        List<String> rows = new ArrayList<>();
        int rank = 1;
        for (UserRecord user : ranking) {
            rows.add(rank++ + "," + user.username + "," + user.totalPoints + "," + user.wins + "," + user.losses + "," + user.matches + "," + user.winRate());
        }
        client.send("RANKING;" + String.join("|", rows));
    }

    private void sendTo(String username, String message) {
        ClientHandler handler = clients.get(username);
        if (handler != null) handler.send(message);
    }

    private int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    private void disconnected(ClientHandler client) {
        if (client.username != null && clients.remove(client.username, client)) {
            pendingInvites.remove(client.username);
            pendingInvites.values().removeIf(value -> value.equals(client.username));
            matches.playerDisconnected(client.username);
            broadcastOnlineList();
        }
    }

    private enum Direction {
        LEFT, MIDDLE, RIGHT;
        static Direction parse(String value) {
            try { return valueOf(value.toUpperCase()); } catch (IllegalArgumentException ex) { return null; }
        }
    }

    private enum Role { POLICE, THIEF }
    private enum Phase { PREPARING, WAITING_MOVES, FINISHED }
    private enum Buff { EXTRA_2_SECONDS, CHANGE_ONCE, REMOVE_ONE_DIRECTION }

    private final class MatchManager {
        private final Map<String, GameRoom> byPlayer = new HashMap<>();
        private final Map<String, GameRoom> byRoom = new HashMap<>();

        synchronized boolean inMatch(String username) { return byPlayer.containsKey(username); }

        synchronized void createMatch(String first, String second) {
            if (byPlayer.containsKey(first) || byPlayer.containsKey(second)) return;
            GameRoom room = new GameRoom(first, second, random.nextBoolean() ? first : second);
            byPlayer.put(first, room);
            byPlayer.put(second, room);
            byRoom.put(room.roomId, room);
            sendTo(first, "GAME_START;" + room.roomId + ";" + second);
            sendTo(second, "GAME_START;" + room.roomId + ";" + first);
            broadcastOnlineList();
            room.startNextRound();
        }

        synchronized GameRoom roomFor(String username) { return byPlayer.get(username); }

        void move(String username, Direction direction, int round) {
            GameRoom room = roomFor(username);
            if (room == null) { sendTo(username, "ERROR;You are not in a match"); return; }
            room.move(username, direction, round);
        }

        void puzzleAnswer(String username, int round, String answer) {
            GameRoom room = roomFor(username);
            if (room == null) { sendTo(username, "ERROR;No active puzzle"); return; }
            room.puzzleAnswer(username, round, answer);
        }

        void exit(String username) {
            GameRoom room = roomFor(username);
            if (room != null) room.forfeit(username, "FORFEIT");
        }

        void playerDisconnected(String username) {
            GameRoom room = roomFor(username);
            if (room != null) room.playerDisconnected(username);
        }

        void playerReconnected(String username) {
            GameRoom room = roomFor(username);
            if (room != null) room.playerReconnected(username);
        }

        synchronized void roomFinished(GameRoom room, String winner, String reason) {
            users.recordMatch(room.roomId, room.first, room.second, room.score(room.first), room.score(room.second), winner, room.round, reason, room.startedAt, room.roundHistory);
            byPlayer.remove(room.first);
            byPlayer.remove(room.second);
            byRoom.remove(room.roomId);
            lastOpponent.put(room.first, room.second);
            lastOpponent.put(room.second, room.first);
            broadcastOnlineList();
        }

        private String firstOf(GameRoom room) { return room.first; }
        private String secondOf(GameRoom room) { return room.second; }
    }

    private final class GameRoom {
        private final String roomId = UUID.randomUUID().toString();
        private final Instant startedAt = Instant.now();
        private final List<Document> roundHistory = new ArrayList<>();
        private final String first;
        private final String second;
        private final String initialPolice;
        private final Object lock = new Object();
        private final Map<String, Integer> scores = new HashMap<>();
        private final Map<String, Direction> moves = new HashMap<>();
        private final Map<String, Role> roles = new HashMap<>();
        private final Map<String, RoundSettings> settings = new HashMap<>();
        private final Map<String, List<Buff>> pendingBuffs = new HashMap<>();
        private final Set<String> changeUsed = new HashSet<>();
        private final Map<String, Long> deadlines = new HashMap<>();
        private Phase phase = Phase.PREPARING;
        private int round;
        private long roundVersion;
        private ScheduledFuture<?> prepareTimer;
        private ScheduledFuture<?> moveTimer;
        private Direction airdrop;
        private Puzzle puzzle;
        private String puzzleWinner;
        private String disconnectedUser;
        private long disconnectVersion;

        GameRoom(String first, String second, String initialPolice) {
            this.first = first;
            this.second = second;
            this.initialPolice = initialPolice;
            scores.put(first, 0);
            scores.put(second, 0);
            pendingBuffs.put(first, new ArrayList<>());
            pendingBuffs.put(second, new ArrayList<>());
        }

        int score(String username) { return scores.get(username); }
        String opponentOf(String username) { return first.equals(username) ? second : first; }

        void startNextRound() {
            synchronized (lock) {
                if (phase == Phase.FINISHED) return;
                round++;
                roundVersion++;
                moves.clear();
                changeUsed.clear();
                phase = Phase.PREPARING;
                String police = (round % 2 == 1) ? initialPolice : opponentOf(initialPolice);
                roles.put(police, Role.POLICE);
                roles.put(opponentOf(police), Role.THIEF);
                settings.put(first, consumeSettings(first));
                settings.put(second, consumeSettings(second));
                airdrop = random.nextInt(100) < 40 ? Direction.values()[random.nextInt(Direction.values().length)] : null;
                puzzle = random.nextInt(100) < 35 ? Puzzle.random(random) : null;
                puzzleWinner = null;
                if (puzzle != null) {
                    sendTo(first, "PUZZLE;" + round + ";" + puzzle.question);
                    sendTo(second, "PUZZLE;" + round + ";" + puzzle.question);
                }
                String airdropPath = airdrop == null ? "NONE" : airdrop.name();
                sendTo(first, "AIRDROP;" + round + ";" + airdropPath);
                sendTo(second, "AIRDROP;" + round + ";" + airdropPath);
                long version = roundVersion;
                prepareTimer = scheduler.schedule(() -> openMoveWindow(version), 3, TimeUnit.SECONDS);
            }
        }

        private RoundSettings consumeSettings(String username) {
            RoundSettings result = new RoundSettings();
            for (Buff buff : pendingBuffs.get(username)) {
                if (buff == Buff.EXTRA_2_SECONDS) result.seconds += 2;
                if (buff == Buff.CHANGE_ONCE) result.changeOnce = true;
                if (buff == Buff.REMOVE_ONE_DIRECTION) result.blocked = Direction.values()[random.nextInt(Direction.values().length)];
            }
            pendingBuffs.get(username).clear();
            return result;
        }

        private void openMoveWindow(long version) {
            synchronized (lock) {
                if (phase != Phase.PREPARING || version != roundVersion) return;
                phase = Phase.WAITING_MOVES;
                long now = System.nanoTime();
                deadlines.put(first, now + TimeUnit.SECONDS.toNanos(settings.get(first).seconds));
                deadlines.put(second, now + TimeUnit.SECONDS.toNanos(settings.get(second).seconds));
                sendRoundStart(first);
                sendRoundStart(second);
                int seconds = Math.max(settings.get(first).seconds, settings.get(second).seconds);
                moveTimer = scheduler.schedule(() -> timeout(version), seconds, TimeUnit.SECONDS);
            }
        }

        private void sendRoundStart(String username) {
            RoundSettings current = settings.get(username);
            String blocked = current.blocked == null ? "NONE" : current.blocked.name();
            sendTo(username, "ROUND_START;" + round + ";" + roles.get(username) + ";" + score(username) + ";" + score(opponentOf(username)) + ";" + current.seconds + ";" + blocked + ";" + current.changeOnce);
        }

        void move(String username, Direction direction, int requestRound) {
            synchronized (lock) {
                if (phase != Phase.WAITING_MOVES || requestRound != round) {
                    sendTo(username, "ERROR;This round is no longer active");
                    return;
                }
                RoundSettings current = settings.get(username);
                if (System.nanoTime() >= deadlines.get(username)) {
                    sendTo(username, "ERROR;Your move deadline has expired");
                    return;
                }
                if (direction == current.blocked) {
                    sendTo(username, "ERROR;This direction is blocked for this round");
                    return;
                }
                boolean changed = moves.containsKey(username);
                if (changed && (!current.changeOnce || changeUsed.contains(username))) {
                    sendTo(username, "ERROR;Move already sent");
                    return;
                }
                moves.put(username, direction);
                if (changed) changeUsed.add(username);
                sendTo(username, "MOVE_ACCEPTED;" + round + ";" + (changed ? "CHANGED" : "SAVED"));
                if (moves.size() == 2) resolveRound("NORMAL");
            }
        }

        void puzzleAnswer(String username, int requestRound, String answer) {
            synchronized (lock) {
                if (puzzle == null || puzzleWinner != null || requestRound != round || phase != Phase.PREPARING) {
                    sendTo(username, "PUZZLE_RESULT;IGNORED");
                    return;
                }
                if (puzzle.answer.equalsIgnoreCase(answer.trim())) {
                    puzzleWinner = username;
                    grant(username, Buff.REMOVE_ONE_DIRECTION);
                    sendTo(username, "PUZZLE_RESULT;WIN");
                    sendTo(opponentOf(username), "PUZZLE_RESULT;LOSE");
                    sendTo(username, "BUFF;REMOVE_ONE_DIRECTION;Next round");
                } else {
                    sendTo(username, "PUZZLE_RESULT;WRONG");
                }
            }
        }

        private void timeout(long version) {
            synchronized (lock) {
                if (phase == Phase.WAITING_MOVES && version == roundVersion) resolveRound("TIMEOUT");
            }
        }

        private void resolveRound(String cause) {
            if (phase != Phase.WAITING_MOVES) return;
            phase = Phase.PREPARING;
            cancel(moveTimer);
            String police = roles.get(first) == Role.POLICE ? first : second;
            String thief = opponentOf(police);
            Direction policeMove = moves.get(police);
            Direction thiefMove = moves.get(thief);
            String winner = null;
            if (policeMove != null && thiefMove != null) winner = policeMove == thiefMove ? police : thief;
            else if (policeMove != null) winner = police;
            else if (thiefMove != null) winner = thief;
            if (winner != null) scores.put(winner, score(winner) + 1);
            roundHistory.add(new Document("number", round).append("police", police).append("thief", thief)
                .append("policeMove", policeMove == null ? null : policeMove.name())
                .append("thiefMove", thiefMove == null ? null : thiefMove.name())
                .append("winner", winner).append("cause", cause)
                .append("airdrop", airdrop == null ? null : airdrop.name())
                .append("puzzleWinner", puzzleWinner));
            grantAirdrop(first);
            grantAirdrop(second);
            sendRoundResult(first, police, thief, winner, cause);
            sendRoundResult(second, police, thief, winner, cause);
            if (shouldFinish()) finish(null, "SCORE");
            else scheduler.schedule(this::startNextRound, 1100, TimeUnit.MILLISECONDS);
        }

        private void grantAirdrop(String username) {
            if (airdrop == null || moves.get(username) != airdrop) return;
            Buff buff = Buff.values()[random.nextInt(Buff.values().length)];
            grant(username, buff);
            sendTo(username, "BUFF;" + buff + ";Airdrop reward for next round");
        }

        private void grant(String username, Buff buff) {
            List<Buff> buffs = pendingBuffs.get(username);
            if (!buffs.contains(buff)) buffs.add(buff);
        }

        private void sendRoundResult(String username, String police, String thief, String winner, String cause) {
            Direction own = moves.get(username);
            Direction other = moves.get(opponentOf(username));
            String result = winner == null ? "DRAW" : winner.equals(username) ? "WIN" : "LOSE";
            sendTo(username, "ROUND_RESULT;" + round + ";" + result + ";" + police + ";" + thief + ";" + display(own) + ";" + display(other) + ";" + score(username) + ";" + score(opponentOf(username)) + ";" + cause);
        }

        private String display(Direction direction) { return direction == null ? "TIMEOUT" : direction.name(); }

        private boolean shouldFinish() {
            if (round < 6) return false;
            if (round == 6) return score(first) != score(second);
            return round % 2 == 0 && score(first) != score(second);
        }

        void forfeit(String leaver, String reason) {
            synchronized (lock) {
                if (phase != Phase.FINISHED) finish(opponentOf(leaver), reason);
            }
        }

        private void finish(String forcedWinner, String reason) {
            if (phase == Phase.FINISHED) return;
            phase = Phase.FINISHED;
            cancel(prepareTimer);
            cancel(moveTimer);
            String winner = forcedWinner;
            if (winner == null && score(first) != score(second)) winner = score(first) > score(second) ? first : second;
            matches.roomFinished(this, winner, reason);
            sendMatchResult(first, winner, reason);
            sendMatchResult(second, winner, reason);
        }

        private void sendMatchResult(String username, String winner, String reason) {
            String result = winner == null ? "DRAW" : winner.equals(username) ? "WIN" : "LOSE";
            sendTo(username, "MATCH_RESULT;" + result + ";" + score(username) + ";" + score(opponentOf(username)) + ";" + reason);
        }

        void playerDisconnected(String username) {
            synchronized (lock) {
                if (phase == Phase.FINISHED) return;
                disconnectedUser = username;
                long version = ++disconnectVersion;
                sendTo(opponentOf(username), "OPPONENT_RECONNECTING;" + username);
                scheduler.schedule(() -> {
                    synchronized (lock) {
                        if (version == disconnectVersion && username.equals(disconnectedUser) && !clients.containsKey(username)) {
                            forfeit(username, "DISCONNECTED");
                        }
                    }
                }, 8, TimeUnit.SECONDS);
            }
        }

        void playerReconnected(String username) {
            synchronized (lock) {
                if (username.equals(disconnectedUser)) {
                    disconnectedUser = null;
                    disconnectVersion++;
                    sendTo(opponentOf(username), "OPPONENT_RECONNECTED;" + username);
                }
            }
        }

        private void cancel(ScheduledFuture<?> future) {
            if (future != null) future.cancel(false);
        }
    }

    private static final class RoundSettings {
        int seconds = ROUND_SECONDS;
        boolean changeOnce;
        Direction blocked;
    }

    private record Puzzle(String question, String answer) {
        static Puzzle random(Random random) {
            List<Puzzle> puzzles = List.of(
                new Puzzle("What is 2 + 2?", "4"),
                new Puzzle("How many directions are available?", "3"),
                new Puzzle("What protocol does this game use: TCP or UDP?", "TCP")
            );
            return puzzles.get(random.nextInt(puzzles.size()));
        }
    }

    private final class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter writer;
        private String username;

        ClientHandler(Socket socket) { this.socket = socket; }

        @Override public void run() {
            try (socket;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter output = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                writer = output;
                send("CONNECTED;Police vs Robber Server");
                String line;
                while ((line = reader.readLine()) != null) handle(this, line);
            } catch (IOException ignored) {
            } finally {
                disconnected(this);
            }
        }

        synchronized void send(String message) {
            if (writer != null) writer.println(message);
        }

        void close() {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

}
