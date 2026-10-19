import com.networkgame.client.GameClientFrame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.*;
import java.nio.charset.StandardCharsets;
import javax.swing.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

public final class MoveRegressionTest {
    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    private static void message(GameClientFrame frame, String text) throws Exception {
        Method m = frame.getClass().getDeclaredMethod("handleMessage", String.class);
        m.setAccessible(true);
        m.invoke(frame, text);
    }

    public static void main(String[] args) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(5000);
            GameClientFrame[] frame = new GameClientFrame[1];
            SwingUtilities.invokeAndWait(() -> frame[0] = new GameClientFrame("127.0.0.1", server.getLocalPort()));
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(5000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            message(frame[0], "GAME_START;test;opponent");
                            message(frame[0], "PUZZLE;1;What is 2 + 2?");
                            message(frame[0], "ROUND_START;1;POLICE;0;0;10;NONE;false");
                            frame[0].setVisible(true);
                            if (field(frame[0], "puzzleWindow") != null) throw new AssertionError("Puzzle did not close");
                            Object game = field(frame[0], "game");
                            JToggleButton left = (JToggleButton) field(game, "left");
                            JButton send = (JButton) field(game, "sendButton");
                            if (!left.isEnabled() || !send.isEnabled()) throw new AssertionError("Move controls disabled");
                            left.doClick();
                            new File("visual-check").mkdirs();
                            BufferedImage preview = new BufferedImage(frame[0].getWidth(), frame[0].getHeight(), BufferedImage.TYPE_INT_RGB);
                            var graphics = preview.createGraphics(); frame[0].paint(graphics); graphics.dispose();
                            ImageIO.write(preview, "png", new File("visual-check/game-window.png"));
                            send.doClick();
                        } catch (Exception e) { throw new RuntimeException(e); }
                    });
                    String actual = reader.readLine();
                    if (!"MOVE;LEFT;1".equals(actual)) throw new AssertionError(actual);
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            message(frame[0], "MOVE_ACCEPTED;1;SAVED");
                            message(frame[0], "ROUND_START;2;THIEF;1;0;10;NONE;false");
                            Object game = field(frame[0], "game");
                            JToggleButton right = (JToggleButton) field(game, "right");
                            if (((JToggleButton) field(game, "left")).isSelected()) throw new AssertionError("Previous choice retained");
                            right.doClick();
                            ((JButton) field(game, "sendButton")).doClick();
                        } catch (Exception e) { throw new RuntimeException(e); }
                    });
                    actual = reader.readLine();
                    if (!"MOVE;RIGHT;2".equals(actual)) throw new AssertionError(actual);
                    System.out.println("PASS Swing: puzzle closes, controls enable, LEFT and RIGHT send with correct round, selection resets");
                } finally {
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            ((Timer) field(field(frame[0], "game"), "timer")).stop();
                        } catch (Exception e) { throw new RuntimeException(e); }
                        frame[0].dispose();
                    });
                }
            }
        }
        System.exit(0);
    }
}
