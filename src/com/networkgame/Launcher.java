package com.networkgame;

import com.networkgame.client.ClientLauncher;
import com.networkgame.server.GameServer;
import java.util.Arrays;

public final class Launcher {
    private Launcher() { }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "server" : args[0].toLowerCase();
        if ("server".equals(mode)) {
            int port = args.length > 1 ? Integer.parseInt(args[1]) : 5050;
            new GameServer(port).start();
            return;
        }
        if ("client".equals(mode)) {
            ClientLauncher.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        throw new IllegalArgumentException("Usage: server [port] or client [host] [port]");
    }
}
