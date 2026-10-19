package com.networkgame.client;

import java.awt.GridLayout;
import java.util.prefs.Preferences;
import javax.swing.*;

/** Entry point packaged exclusively with the client UI and TCP connection code. */
public final class ClientLauncher {
    private ClientLauncher() { }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) { }
            if (args.length > 0) {
                try {
                    String host = args[0].trim();
                    int port = args.length > 1 ? Integer.parseInt(args[1]) : 5050;
                    validate(host, port);
                    new GameClientFrame(host, port).setVisible(true);
                } catch (IllegalArgumentException ex) {
                    JOptionPane.showMessageDialog(null, ex.getMessage(), "Server address", JOptionPane.ERROR_MESSAGE);
                }
                return;
            }
            Preferences preferences = Preferences.userNodeForPackage(ClientLauncher.class);
            JTextField hostField = new JTextField(preferences.get("serverHost", ""), 24);
            JSpinner portField = new JSpinner(new SpinnerNumberModel(preferences.getInt("serverPort", 5050), 1, 65535, 1));
            JPanel form = new JPanel(new GridLayout(2, 2, 12, 12));
            form.add(new JLabel("Server IP / hostname")); form.add(hostField);
            form.add(new JLabel("Port")); form.add(portField);
            while (JOptionPane.showConfirmDialog(null, form, "Connect to game server", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                String host = hostField.getText().trim();
                int port = ((Number) portField.getValue()).intValue();
                try {
                    validate(host, port);
                    preferences.put("serverHost", host);
                    preferences.putInt("serverPort", port);
                    new GameClientFrame(host, port).setVisible(true);
                    return;
                } catch (IllegalArgumentException ex) {
                    JOptionPane.showMessageDialog(null, ex.getMessage(), "Server address", JOptionPane.WARNING_MESSAGE);
                }
            }
        });
    }

    private static void validate(String host, int port) {
        if (host.isBlank() || host.chars().anyMatch(Character::isWhitespace) || host.contains("/") || host.contains(";")) {
            throw new IllegalArgumentException("Enter the server IP or hostname, without http:// or a port suffix.");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535.");
    }
}
