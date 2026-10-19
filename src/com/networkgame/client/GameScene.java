package com.networkgame.client;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;
import javax.swing.JPanel;
import javax.swing.Timer;

/** Animated arena; only revealed Server results can move the opponent. */
public final class GameScene extends JPanel {
    private static final String[] LANES = {"LEFT", "MIDDLE", "RIGHT"};
    private final Timer animation = new Timer(33, event -> repaint());
    private final IntConsumer chooseLane;
    private String ownRole = "POLICE";
    private String blocked = "NONE";
    private String airdrop = "NONE";
    private String outcome = "";
    private String policeMove = "NONE", thiefMove = "NONE";
    private int selected = -1;
    private boolean revealed, accepting;
    private long revealedAt;

    public GameScene(IntConsumer chooseLane) {
        this.chooseLane = chooseLane;
        setPreferredSize(new Dimension(820, 340));
        setMinimumSize(new Dimension(480, 220));
        setToolTipText("Select a road");
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (!accepting || event.getY() < getHeight() * .26) return;
                int lane = Math.min(2, Math.max(0, (int) (event.getX() * 3.0 / getWidth())));
                if (!LANES[lane].equals(blocked)) chooseLane.accept(lane);
            }
        });
    }

    @Override public void addNotify() { super.addNotify(); animation.start(); }
    @Override public void removeNotify() { animation.stop(); super.removeNotify(); }

    public void prepare() {
        accepting = false; revealed = false; selected = -1; outcome = ""; airdrop = "NONE";
        repaint();
    }

    public void startRound(String role, String blocked) {
        ownRole = role; this.blocked = blocked;
        revealed = false; selected = -1; outcome = ""; accepting = true;
        repaint();
    }

    public void select(int lane) { if (accepting) { selected = lane; repaint(); } }
    public void setAccepting(boolean value) { accepting = value; }
    public void setAirdrop(String lane) { airdrop = lane; repaint(); }

    public void reveal(String ownMove, String otherMove, String result) {
        policeMove = "POLICE".equals(ownRole) ? ownMove : otherMove;
        thiefMove = "THIEF".equals(ownRole) ? ownMove : otherMove;
        revealed = true; accepting = false; outcome = result;
        revealedAt = System.nanoTime(); repaint();
    }

    @Override protected void paintComponent(Graphics original) {
        super.paintComponent(original);
        Graphics2D g = (Graphics2D) original.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(getWidth() / 900.0, getHeight() / 340.0);
        double time = System.nanoTime() / 1e9;
        g.setColor(new Color(178, 227, 239)); g.fillRect(0, 0, 900, 95);
        g.setColor(new Color(255, 220, 110)); g.fillOval(780, 14, 46, 46);
        cloud(g, 65, 24); cloud(g, 390, 15); cloud(g, 650, 32);
        for (int i = 0; i < 10; i++) {
            int x = i * 96;
            g.setColor(i % 2 == 0 ? new Color(105, 165, 159) : new Color(133, 188, 177));
            g.fillRect(x, 49 + i % 3 * 8, 68, 51);
            g.setColor(new Color(227, 243, 220));
            for (int col = 0; col < 3; col++) g.fillRect(x + 10 + col * 17, 62 + i % 3 * 8, 8, 10);
        }
        g.setColor(new Color(106, 175, 115)); g.fillRect(0, 93, 900, 247);
        g.setColor(new Color(78, 143, 95)); g.fillRect(0, 94, 900, 8);
        for (int lane = 0; lane < 3; lane++) {
            int x = 40 + lane * 300;
            g.setColor(new Color(210, 223, 209)); g.fillRoundRect(x - 6, 99, 232, 241, 14, 14);
            g.setColor(new Color(79, 86, 92)); g.fillRoundRect(x, 104, 220, 236, 10, 10);
            g.setColor(new Color(245, 232, 162));
            for (int y = 125; y < 340; y += 49) g.fillRoundRect(x + 107, y, 6, 24, 3, 3);
            if (selected == lane && !revealed) {
                g.setColor(new Color(61, 207, 181)); g.setStroke(new BasicStroke(4));
                g.drawRoundRect(x + 3, 107, 214, 229, 10, 10);
            }
            if (LANES[lane].equals(blocked) && !revealed) {
                g.setColor(new Color(236, 109, 91)); g.fillRoundRect(x + 45, 166, 130, 25, 4, 4);
                g.setColor(Color.WHITE); centered(g, "BLOCKED", x + 110, 184, 12);
            }
            g.setColor(new Color(245, 250, 246)); g.fillRoundRect(x + 62, 112, 96, 25, 6, 6);
            g.setColor(new Color(34, 59, 59)); centered(g, LANES[lane], x + 110, 130, 13);
            if (LANES[lane].equals(airdrop)) chest(g, x + 164, 202, time);
        }
        for (int x : new int[] {15, 287, 315, 587, 615, 887}) tree(g, x, 180);
        if (revealed) {
            double progress = Math.min(1, (System.nanoTime() - revealedAt) / 850_000_000.0);
            int policeLane = laneOf(policeMove), thiefLane = laneOf(thiefMove);
            if (policeLane >= 0) character(g, 150 + 300 * policeLane, 305 - progress * 30, true, time, true);
            if (thiefLane >= 0) character(g, 150 + 300 * thiefLane, 230 - progress * (policeLane == thiefLane ? 0 : 14), false, time, true);
            g.setColor("WIN".equals(outcome) ? new Color(20, 104, 77) : "LOSE".equals(outcome) ? new Color(159, 60, 57) : new Color(50, 68, 76));
            g.fillRoundRect(330, 8, 240, 34, 8, 8);
            g.setColor(Color.WHITE);
            String caption = policeLane < 0 || thiefLane < 0 ? "TIME LIMIT REACHED" : policeLane == thiefLane ? "CAUGHT!" : "ESCAPED!";
            centered(g, caption + "  " + outcome, 450, 31, 15);
        } else {
            int x = selected < 0 ? 450 : 150 + selected * 300;
            boolean police = "POLICE".equals(ownRole);
            character(g, x, 277, police, time, selected >= 0);
            g.setColor(new Color(245, 250, 246)); centered(g, "YOU / " + ownRole, x, 327, 12);
            g.setColor(new Color(50, 68, 76)); g.fillRoundRect(358, 10, 184, 29, 6, 6);
            g.setColor(Color.WHITE); centered(g, "OPPONENT HIDDEN", 450, 30, 12);
        }
        g.dispose();
    }

    private static int laneOf(String move) {
        for (int i = 0; i < LANES.length; i++) if (LANES[i].equals(move)) return i;
        return -1;
    }

    private static void centered(Graphics2D g, String text, int x, int y, int size) {
        g.setFont(new Font("SansSerif", Font.BOLD, size));
        g.drawString(text, x - g.getFontMetrics().stringWidth(text) / 2, y);
    }

    private static void cloud(Graphics2D g, int x, int y) {
        g.setColor(new Color(243, 251, 251));
        g.fillOval(x, y + 7, 66, 19); g.fillOval(x + 12, y, 28, 27); g.fillOval(x + 33, y + 3, 24, 23);
    }

    private static void tree(Graphics2D g, int x, int y) {
        g.setColor(new Color(93, 111, 74)); g.fillRect(x - 3, y, 7, 45);
        g.setColor(new Color(43, 115, 81)); g.fillOval(x - 20, y - 29, 40, 44);
        g.setColor(new Color(64, 139, 88)); g.fillOval(x - 14, y - 41, 30, 38);
    }

    private static void chest(Graphics2D g, int x, int y, double time) {
        int bob = (int) (Math.sin(time * 3) * 3);
        g.setColor(new Color(244, 191, 69)); g.fillRoundRect(x - 18, y + bob, 36, 29, 4, 4);
        g.setColor(new Color(145, 104, 47)); g.setStroke(new BasicStroke(3)); g.drawRoundRect(x - 18, y + bob, 36, 29, 4, 4);
        g.drawLine(x - 18, y + bob + 10, x + 18, y + bob + 10);
        g.setColor(new Color(255, 244, 153)); g.fillRect(x - 3, y + bob + 8, 6, 10);
    }

    private static void character(Graphics2D original, double x, double y, boolean police, double time, boolean running) {
        Graphics2D g = (Graphics2D) original.create();
        double stride = running ? Math.sin(time * 12) * 8 : Math.sin(time * 3) * 1.4;
        g.setColor(new Color(0, 0, 0, 45)); g.fillOval((int) x - 24, (int) y + 14, 48, 12);
        g.translate(x, y + (running ? Math.abs(stride) * -.3 : stride));
        g.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(39, 52, 67));
        g.drawLine(-8, 0, (int) (-11 - stride), 19); g.drawLine(8, 0, (int) (11 + stride), 19);
        g.setColor(police ? new Color(42, 101, 174) : new Color(229, 232, 225));
        g.fillRoundRect(-19, -37, 38, 42, 12, 12);
        g.setStroke(new BasicStroke(8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(-19, -29, (int) (-26 + stride), -10); g.drawLine(19, -29, (int) (26 - stride), -10);
        if (!police) {
            g.setColor(new Color(48, 58, 64));
            for (int j = 0; j < 4; j++) g.fillRect(-18, -32 + j * 9, 36, 4);
            g.setColor(new Color(191, 154, 88)); g.fillOval(21, -23, 23, 29);
            g.setColor(new Color(95, 88, 55)); centered(g, "$", 33, -3, 17);
        } else {
            g.setColor(new Color(254, 217, 95)); g.fillOval(6, -28, 9, 11);
            g.setColor(new Color(33, 55, 91)); g.fillRect(-19, -4, 38, 6);
        }
        g.setColor(new Color(238, 185, 143)); g.fillOval(-17, -66, 34, 34);
        g.setColor(new Color(42, 48, 58));
        if (police) {
            g.setColor(new Color(27, 67, 117)); g.fillRoundRect(-21, -72, 42, 15, 5, 5); g.fillRect(-22, -60, 44, 5);
            g.setColor(new Color(254, 217, 95)); g.fillOval(-4, -69, 8, 8);
        } else {g.fillRoundRect(-18, -71, 36, 12, 5, 5); g.fillRoundRect(-17, -54, 34, 10, 3, 3);}
        g.setColor(Color.WHITE); g.fillOval(-11, -53, 8, 6); g.fillOval(3, -53, 8, 6);
        g.setColor(new Color(30, 42, 48)); g.fillOval(-8, -52, 3, 4); g.fillOval(6, -52, 3, 4);
        g.setStroke(new BasicStroke(2)); g.drawArc(-5, -44, 10, 6, 190, 160);
        g.dispose();
    }
}
