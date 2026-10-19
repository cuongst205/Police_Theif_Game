import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/** Launch the shipped JAR alone, without the project's server or driver classpath. */
public final class ClientDistributionTest {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of("client-game", "PoliceRobberClient.jar").toAbsolutePath();
        try (JarFile file = new JarFile(jar.toFile())) {
            if (file.stream().anyMatch(entry -> entry.getName().contains("/server/") || entry.getName().startsWith("org/bson/") || entry.getName().startsWith("com/mongodb/"))) {
                throw new AssertionError("Server or MongoDB code leaked into client package");
            }
        }
        Path directory = Files.createTempDirectory(Path.of("."), "client-package-test-");
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(7000);
            String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
            Process process = new ProcessBuilder(java, "-jar", jar.toString(), "127.0.0.1", String.valueOf(server.getLocalPort()))
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("client.log").toFile()).start();
            try (Socket client = server.accept()) {
                client.setSoTimeout(7000);
                PrintWriter writer = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8);
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                writer.println("CONNECTED;Distribution test");
                writer.println("LOGIN_OK;package_test;0");
                if (!"GET_ONLINE".equals(reader.readLine())) throw new AssertionError("Client did not handle server login event");
                writer.println("ONLINE_LIST;package_test,0,IDLE");
                if (!process.isAlive()) throw new AssertionError("Client exited unexpectedly");
                System.out.println("PASS standalone client JAR: no server/driver dependency; connects to supplied host and port; handles login event");
            } finally {
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("Test client did not close");
            }
        }
    }
}
