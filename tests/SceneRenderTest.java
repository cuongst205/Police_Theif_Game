import com.networkgame.client.GameScene;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

public final class SceneRenderTest {
    public static void main(String[] args) throws Exception {
        new File("visual-check").mkdirs();
        SwingUtilities.invokeAndWait(() -> {
            try {
                GameScene scene = new GameScene(lane -> {});
                scene.setSize(900, 340);
                scene.startRound("POLICE", "NONE"); scene.select(0); scene.setAirdrop("RIGHT");
                save(scene, "police");
                scene.startRound("THIEF", "LEFT"); scene.select(2);
                save(scene, "thief");
                scene.reveal("RIGHT", "LEFT", "WIN");
                save(scene, "escaped");
                scene.reveal("MIDDLE", "MIDDLE", "LOSE");
                scene.setSize(480, 220);
                save(scene, "compact-caught");
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        System.out.println("PASS four scene states rendered at large and compact sizes");
    }

    private static void save(GameScene scene, String name) throws Exception {
        BufferedImage image = new BufferedImage(scene.getWidth(), scene.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); scene.paint(graphics); graphics.dispose();
        if (image.getRGB(20, 20) == image.getRGB(20, 200)) throw new AssertionError("Blank arena");
        ImageIO.write(image, "png", new File("visual-check/" + name + ".png"));
    }
}
