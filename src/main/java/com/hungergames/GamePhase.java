package com.hungergames;

public enum GamePhase {
    WAITING, COUNTDOWN, GRACE, RUNNING, DEATHMATCH, ENDING;

    public boolean allowsJoining() {
        return this == WAITING || this == COUNTDOWN;
    }

    public boolean isPlaying() {
        return this == GRACE || this == RUNNING || this == DEATHMATCH;
    }

    public boolean allowsDamage() {
        return this == RUNNING || this == DEATHMATCH;
    }
}
