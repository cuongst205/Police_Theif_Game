package com.networkgame.server;

final class UserRecord {
    final String username;
    final int totalPoints, wins, losses, matches;

    UserRecord(String username, int totalPoints, int wins, int losses, int matches) {
        this.username = username;
        this.totalPoints = totalPoints;
        this.wins = wins;
        this.losses = losses;
        this.matches = matches;
    }

    String winRate() { return matches == 0 ? "0%" : Math.round(wins * 100.0 / matches) + "%"; }
}
