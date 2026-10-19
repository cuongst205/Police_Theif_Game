package com.networkgame.client;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/** Swing client. It never decides a winner; it displays Server events only. */
public final class GameClientFrame extends JFrame {
    private static final Color NAVY = new Color(25, 58, 91);
    private static final Color BLUE = new Color(44, 110, 164);
    private static final Color PALE = new Color(236, 244, 250);
    private final String host;
    private final int port;
    private final ClientService service;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final LoginPanel login = new LoginPanel();
    private final LobbyPanel lobby = new LobbyPanel();
    private final GamePanel game = new GamePanel();
    private String username;
    private JDialog puzzleWindow;
    private Timer puzzleTimer;

    public GameClientFrame(String host, int port) {
        super("Police vs Robber Online | " + host + ":" + port);
        this.host = host;
        this.port = port;
        this.service = new ClientService(this::onMessage);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(860, 650));
        setSize(1000, 780);
        setLocationByPlatform(true);
        content.add(login, "LOGIN");
        content.add(lobby, "LOBBY");
        content.add(game, "GAME");
        setContentPane(content);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { service.close(); }
        });
        cards.show(content, "LOGIN");
        connectAsync();
    }

    private void connectAsync() {
        if (service.isConnected()) return;
        login.setStatus("Connecting to " + host + ":" + port + "...");
        new Thread(() -> {
            try {
                service.connect(host, port);
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> login.setStatus("Cannot connect: " + ex.getMessage()));
            }
        }, "connect-client").start();
    }

    private void send(String message) {
        try {
            service.send(message);
        } catch (IOException ex) {
            login.setStatus("Connection lost. Reconnecting...");
            connectAsync();
        }
    }

    private void onMessage(String raw) {
        SwingUtilities.invokeLater(() -> handleMessage(raw));
    }

    private void handleMessage(String raw) {
        String[] parts = raw.split(";", -1);
        String command = parts[0];
        switch (command) {
            case "CONNECTED" -> login.setStatus("Connected. Register or log in.");
            case "LOGIN_OK" -> {
                username = parts[1];
                lobby.setWelcome(username, parts.length > 2 ? parts[2] : "0");
                cards.show(content, "LOBBY");
                send("GET_ONLINE");
            }
            case "REGISTER_OK" -> login.setStatus(parts.length > 1 ? parts[1] : "Registered");
            case "ONLINE_LIST" -> lobby.updatePlayers(parts.length > 1 ? parts[1] : "");
            case "INVITE_FROM" -> inviteDialog(parts[1]);
            case "INVITE_SENT" -> lobby.setInfo("Invitation sent to " + parts[1]);
            case "INVITE_REJECTED" -> lobby.setInfo(parts[1] + " rejected the invitation.");
            case "GAME_START" -> {
                game.reset(parts.length > 2 ? parts[2] : "Opponent");
                cards.show(content, "GAME");
            }
            case "PUZZLE" -> puzzleDialog(parts);
            case "AIRDROP" -> game.showAirdrop(parts.length > 2 ? parts[2] : "");
            case "ROUND_START" -> { closePuzzle(); game.startRound(parts); }
            case "MOVE_ACCEPTED" -> game.moveAccepted(parts.length > 2 ? parts[2] : "SAVED");
            case "ROUND_RESULT" -> game.roundResult(parts);
            case "BUFF" -> game.showBuff(parts.length > 2 ? pretty(parts[1]) + " - " + parts[2] : pretty(parts[1]));
            case "PUZZLE_RESULT" -> game.setInfo("Puzzle: " + parts[1]);
            case "OPPONENT_RECONNECTING" -> game.setInfo("Opponent reconnecting. Server waits 8 seconds.");
            case "OPPONENT_RECONNECTED" -> game.setInfo("Opponent reconnected.");
            case "MATCH_RESULT" -> matchResult(parts);
            case "RANKING" -> rankingDialog(parts.length > 1 ? parts[1] : "");
            case "RETURN_TO_LOBBY" -> { cards.show(content, "LOBBY"); lobby.setInfo(parts.length > 1 ? parts[1] : "Back to lobby"); send("GET_ONLINE"); }
            case "WAITING_REPLAY" -> lobby.setInfo("Waiting for " + parts[1] + " to play again.");
            case "ERROR" -> showError(parts.length > 1 ? parts[1] : "Server error");
            case "TRANSPORT_CLOSED" -> login.setStatus("Disconnected from server. Press Login after reconnection.");
            default -> { }
        }
    }

    private void inviteDialog(String challenger) {
        int choice = JOptionPane.showConfirmDialog(this, challenger + " challenges you. Accept?", "Challenge", JOptionPane.YES_NO_OPTION);
        send((choice == JOptionPane.YES_OPTION ? "ACCEPT;" : "REJECT;") + challenger);
    }

    private void puzzleDialog(String[] parts) {
        if (parts.length < 3) return;
        closePuzzle();
        puzzleWindow = new JDialog(this, "Puzzle", false);
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));
        JTextField answer = new JTextField(24);
        JButton submit = new JButton("Submit answer");
        panel.add(new JLabel(parts[2]), BorderLayout.NORTH);
        panel.add(answer, BorderLayout.CENTER);
        panel.add(submit, BorderLayout.SOUTH);
        Runnable reply = () -> {
            if (answer.getText().trim().isEmpty()) return;
            send("PUZZLE_ANSWER;" + parts[1] + ";" + answer.getText().trim());
            closePuzzle();
        };
        submit.addActionListener(event -> reply.run());
        answer.addActionListener(event -> reply.run());
        puzzleWindow.add(panel);
        puzzleWindow.pack();
        puzzleWindow.setLocationRelativeTo(this);
        puzzleTimer = new Timer(3000, event -> closePuzzle());
        puzzleTimer.setRepeats(false);
        puzzleTimer.start();
        puzzleWindow.setVisible(true);
    }

    private void closePuzzle() {
        if (puzzleTimer != null) puzzleTimer.stop();
        if (puzzleWindow != null) puzzleWindow.dispose();
        puzzleWindow = null;
    }

    private void matchResult(String[] parts) {
        closePuzzle();
        game.stopTimer();
        String status = parts.length > 1 ? parts[1] : "DRAW";
        String score = parts.length > 3 ? parts[2] + " - " + parts[3] : "";
        int choice = JOptionPane.showConfirmDialog(this, "Match result: " + status + "\nScore: " + score + "\nPlay again with the same opponent?", "Match complete", JOptionPane.YES_NO_OPTION);
        cards.show(content, "LOBBY");
        lobby.setInfo("Match finished: " + status);
        send("GET_ONLINE");
        if (choice == JOptionPane.YES_OPTION) send("PLAY_AGAIN");
    }

    private void rankingDialog(String data) {
        DefaultTableModel model = new DefaultTableModel(new String[] { "Rank", "Player", "Points", "Wins", "Losses", "Matches", "Win rate" }, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        if (!data.isBlank()) for (String entry : data.split("\\|")) model.addRow(entry.split(",", -1));
        JTable table = new JTable(model);
        JDialog dialog = new JDialog(this, "Ranking", true);
        dialog.add(new JScrollPane(table));
        dialog.setSize(690, 360);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void showError(String message) {
        if (cards != null) JOptionPane.showMessageDialog(this, message, "Game", JOptionPane.WARNING_MESSAGE);
    }

    private String pretty(String value) { return value.replace('_', ' '); }

    private JPanel titledPanel(String title) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(22, 28, 22, 28));
        panel.setBackground(PALE);
        JLabel heading = new JLabel(title, SwingConstants.CENTER);
        heading.setFont(new Font("SansSerif", Font.BOLD, 24));
        heading.setForeground(NAVY);
        heading.setBorder(new EmptyBorder(0, 0, 16, 0));
        panel.add(heading, BorderLayout.NORTH);
        return panel;
    }

    private final class LoginPanel extends JPanel {
        private final JTextField usernameField = new JTextField(18);
        private final JPasswordField passwordField = new JPasswordField(18);
        private final JLabel status = new JLabel("Connecting...", SwingConstants.CENTER);

        LoginPanel() {
            setLayout(new GridBagLayout());
            setBackground(PALE);
            JPanel box = new JPanel(new GridBagLayout());
            box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BLUE), new EmptyBorder(28, 36, 28, 36)));
            box.setBackground(Color.WHITE);
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(7, 7, 7, 7);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
            JLabel title = new JLabel("Police vs Robber", SwingConstants.CENTER);
            title.setFont(new Font("SansSerif", Font.BOLD, 26)); title.setForeground(NAVY); box.add(title, c);
            c.gridwidth = 1; c.gridy++;
            box.add(new JLabel("Username"), c); c.gridx = 1; box.add(usernameField, c);
            c.gridx = 0; c.gridy++; box.add(new JLabel("Password"), c); c.gridx = 1; box.add(passwordField, c);
            c.gridx = 0; c.gridy++; JButton loginButton = new JButton("Login"); box.add(loginButton, c);
            c.gridx = 1; JButton registerButton = new JButton("Register"); box.add(registerButton, c);
            c.gridx = 0; c.gridy++; c.gridwidth = 2; status.setForeground(NAVY); box.add(status, c);
            loginButton.addActionListener(event -> authenticate("LOGIN"));
            registerButton.addActionListener(event -> authenticate("REGISTER"));
            passwordField.addActionListener(event -> authenticate("LOGIN"));
            add(box);
        }

        private void authenticate(String command) {
            String user = usernameField.getText().trim();
            String pass = new String(passwordField.getPassword());
            if (user.isEmpty() || pass.isEmpty()) { setStatus("Enter username and password."); return; }
            connectAsync();
            send(command + ";" + user + ";" + pass);
        }

        void setStatus(String text) { status.setText(text); }
    }

    private final class LobbyPanel extends JPanel {
        private final DefaultTableModel model = new DefaultTableModel(new String[] { "Player", "Points", "Status" }, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        private final JTable players = new JTable(model);
        private final JLabel welcome = new JLabel("", SwingConstants.LEFT);
        private final JLabel info = new JLabel("Select an idle player to challenge.");

        LobbyPanel() {
            JPanel panel = titledPanel("Lobby");
            panel.add(new JScrollPane(players), BorderLayout.CENTER);
            JPanel bottom = new JPanel(new BorderLayout(8, 8));
            bottom.setOpaque(false);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton challenge = new JButton("Challenge");
            JButton refresh = new JButton("Refresh");
            JButton ranking = new JButton("Ranking");
            JButton logout = new JButton("Logout");
            buttons.add(challenge); buttons.add(refresh); buttons.add(ranking); buttons.add(logout);
            bottom.add(welcome, BorderLayout.NORTH); bottom.add(info, BorderLayout.CENTER); bottom.add(buttons, BorderLayout.SOUTH);
            panel.add(bottom, BorderLayout.SOUTH);
            setLayout(new BorderLayout()); add(panel);
            challenge.addActionListener(event -> challenge());
            refresh.addActionListener(event -> send("GET_ONLINE"));
            ranking.addActionListener(event -> send("GET_RANKING"));
            logout.addActionListener(event -> { service.close(); username = null; cards.show(content, "LOGIN"); connectAsync(); });
        }

        void setWelcome(String name, String points) { welcome.setText("Logged in as " + name + " | Total points: " + points); }
        void setInfo(String text) { info.setText(text); }

        void updatePlayers(String data) {
            model.setRowCount(0);
            if (!data.isBlank()) for (String entry : data.split("\\|")) model.addRow(entry.split(",", -1));
        }

        private void challenge() {
            int row = players.getSelectedRow();
            if (row < 0) { setInfo("Select an idle opponent first."); return; }
            String target = String.valueOf(model.getValueAt(row, 0));
            String state = String.valueOf(model.getValueAt(row, 2));
            if (target.equals(username) || !"IDLE".equals(state)) { setInfo("Choose another idle player."); return; }
            send("INVITE;" + target);
        }
    }

    private final class GamePanel extends JPanel {
        private final JLabel opponent = new JLabel("Opponent: -");
        private final JLabel role = new JLabel("Role: -", SwingConstants.CENTER);
        private final JLabel round = new JLabel("Round: -", SwingConstants.CENTER);
        private final JLabel score = new JLabel("Score: 0 - 0", SwingConstants.CENTER);
        private final JLabel timerLabel = new JLabel("10", SwingConstants.CENTER);
        private final JLabel airdropLabel = new JLabel("Airdrop: none", SwingConstants.CENTER);
        private final JLabel buffLabel = new JLabel("Buff: none", SwingConstants.CENTER);
        private final JLabel info = new JLabel("Waiting for Server", SwingConstants.CENTER);
        private final JTextArea result = new JTextArea(4, 30);
        private final JToggleButton left = new JToggleButton("Left");
        private final JToggleButton middle = new JToggleButton("Middle");
        private final JToggleButton right = new JToggleButton("Right");
        private final JButton sendButton = new JButton("Send move");
        private final JButton exitButton = new JButton("Exit game");
        private final Timer timer;
        private int currentRound;
        private boolean canChange;
        private int remaining;
        private final ButtonGroup directionsGroup = new ButtonGroup();
        private final GameScene scene = new GameScene(lane -> {
            JToggleButton button = new JToggleButton[] {left, middle, right}[lane];
            if (button.isEnabled()) button.doClick();
        });

        GamePanel() {
            JPanel panel = titledPanel("Game Room");
            JPanel status = new JPanel(new GridLayout(2, 3, 8, 8)); status.setOpaque(false);
            for (JLabel label : new JLabel[] { opponent, role, round, score, timerLabel, buffLabel }) {
                label.setOpaque(true); label.setBackground(Color.WHITE); label.setBorder(BorderFactory.createLineBorder(BLUE)); label.setHorizontalAlignment(SwingConstants.CENTER); label.setFont(new Font("SansSerif", Font.BOLD, 14)); status.add(label);
            }
            panel.add(status, BorderLayout.NORTH);
            JPanel center = new JPanel(new BorderLayout(8, 8)); center.setOpaque(false); center.setBorder(new EmptyBorder(12, 0, 10, 0));
            JPanel directions = new JPanel(new GridLayout(1, 3, 16, 0)); directions.setOpaque(false);
            directionsGroup.add(left); directionsGroup.add(middle); directionsGroup.add(right);
            JToggleButton[] laneButtons = {left, middle, right};
            for (int i = 0; i < laneButtons.length; i++) {
                JToggleButton button = laneButtons[i];
                final int lane = i;
                button.setFont(new Font("SansSerif", Font.BOLD, 16));
                button.setPreferredSize(new Dimension(150, 38));
                button.setToolTipText("Select " + button.getText() + " road");
                button.addActionListener(event -> scene.select(lane));
                directions.add(button);
            }
            center.add(scene, BorderLayout.CENTER);
            JPanel history = new JPanel(new BorderLayout(0, 6)); history.setOpaque(false);
            history.add(directions, BorderLayout.NORTH);
            result.setEditable(false); result.setLineWrap(true); result.setWrapStyleWord(true); result.setBackground(Color.WHITE);
            JScrollPane log = new JScrollPane(result); log.setPreferredSize(new Dimension(600, 72));
            history.add(log, BorderLayout.CENTER); center.add(history, BorderLayout.SOUTH);
            panel.add(center, BorderLayout.CENTER);
            JPanel bottom = new JPanel(new GridLayout(3, 1, 6, 6)); bottom.setOpaque(false);
            bottom.add(airdropLabel); bottom.add(info);
            JPanel actions = new JPanel(new FlowLayout()); actions.setOpaque(false); actions.add(sendButton); actions.add(exitButton); bottom.add(actions);
            panel.add(bottom, BorderLayout.SOUTH);
            setLayout(new BorderLayout()); add(panel);
            sendButton.addActionListener(event -> sendMove());
            exitButton.addActionListener(event -> exit());
            timer = new Timer(1000, event -> tick());
            disableMoves();
        }

        void reset(String opponentName) {
            scene.prepare();
            stopTimer(); currentRound = 0; opponent.setText("Opponent: " + opponentName); result.setText(""); buffLabel.setText("Buff: none"); airdropLabel.setText("Airdrop: none"); setInfo("Server is preparing the first round.");
        }

        void startRound(String[] data) {
            if (data.length < 8) return;
            currentRound = parse(data[1]);
            role.setText("Role: " + data[2]);
            round.setText("Round: " + currentRound);
            score.setText("Score: " + data[3] + " - " + data[4]);
            remaining = parse(data[5]);
            timerLabel.setText(String.valueOf(remaining));
            String blocked = data[6];
            scene.startRound(data[2], blocked);
            canChange = Boolean.parseBoolean(data[7]);
            stopTimer();
            directionsGroup.clearSelection();
            left.setEnabled(!"LEFT".equals(blocked)); middle.setEnabled(!"MIDDLE".equals(blocked)); right.setEnabled(!"RIGHT".equals(blocked)); sendButton.setEnabled(true);
            setInfo("Choose a direction" + ("NONE".equals(blocked) ? "." : "; " + blocked + " is blocked.") + (canChange ? " You may change once." : ""));
            timer.restart();
        }

        void showAirdrop(String path) { scene.setAirdrop(path); airdropLabel.setText("Airdrop: " + path); }
        void showBuff(String text) { buffLabel.setText("Buff: " + text); }
        void setInfo(String text) { info.setText(text); }
        void stopTimer() { timer.stop(); }

        void moveAccepted(String state) {
            if (!canChange || "CHANGED".equals(state)) disableMoves();
            setInfo("Move " + state.toLowerCase() + ". " + (canChange && !"CHANGED".equals(state) ? "You may change it once." : "Waiting for opponent..."));
        }

        void roundResult(String[] data) {
            stopTimer(); disableMoves();
            if (data.length < 10) return;
            score.setText("Score: " + data[7] + " - " + data[8]);
            scene.reveal(data[5], data[6], data[2]);
            result.append("Round " + data[1] + ": " + data[2] + " | Police: " + data[3] + ", Thief: " + data[4] + "\nYour move: " + data[5] + " | Opponent move: " + data[6] + " | " + data[9] + "\n\n");
            setInfo("Round resolved. Preparing the next round...");
        }

        private void sendMove() {
            String direction = left.isSelected() ? "LEFT" : middle.isSelected() ? "MIDDLE" : right.isSelected() ? "RIGHT" : null;
            if (direction == null) { setInfo("Select a direction first."); return; }
            send("MOVE;" + direction + ";" + currentRound);
            setInfo("Sending " + direction + " to Server...");
        }

        private void exit() {
            if (JOptionPane.showConfirmDialog(GameClientFrame.this, "Leave this match? You will forfeit.", "Exit game", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) send("EXIT_GAME");
        }

        private void tick() {
            remaining--;
            timerLabel.setText(String.valueOf(Math.max(remaining, 0)));
            if (remaining <= 0) { stopTimer(); disableMoves(); setInfo("Server is resolving timeout..."); }
        }

        private void disableMoves() { scene.setAccepting(false); left.setEnabled(false); middle.setEnabled(false); right.setEnabled(false); sendButton.setEnabled(false); }
        private int parse(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException ex) { return 0; } }
    }

    private static final class ClientService {
        private final Consumer<String> listener;
        private volatile Socket socket;
        private volatile PrintWriter writer;

        ClientService(Consumer<String> listener) { this.listener = listener; }
        boolean isConnected() { return socket != null && socket.isConnected() && !socket.isClosed(); }

        synchronized void connect(String host, int port) throws IOException {
            if (isConnected()) return;
            Socket connection = new Socket();
            try {
                connection.connect(new InetSocketAddress(host, port), 5000);
            } catch (IOException ex) {
                connection.close();
                throw ex;
            }
            socket = connection;
            writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            Thread receiver = new Thread(this::receiveLoop, "server-receiver");
            receiver.setDaemon(true);
            receiver.start();
        }

        synchronized void send(String message) throws IOException {
            if (!isConnected() || writer == null) throw new IOException("Not connected");
            writer.println(message);
            if (writer.checkError()) throw new IOException("Cannot write to server");
        }

        private void receiveLoop() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) listener.accept(line);
            } catch (IOException ignored) {
            } finally {
                close();
                listener.accept("TRANSPORT_CLOSED");
            }
        }

        synchronized void close() {
            try { if (socket != null) socket.close(); } catch (IOException ignored) { }
            socket = null;
            writer = null;
        }
    }
}
