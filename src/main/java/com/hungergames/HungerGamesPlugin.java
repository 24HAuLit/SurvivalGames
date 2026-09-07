package com.hungergames;

import java.util.logging.Level;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class HungerGamesPlugin extends JavaPlugin {
    private Arena arena;
    private LootManager loot;
    private GameManager game;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            arena = new Arena(this);
            loot = new LootManager(this);
            game = new GameManager(this, arena, loot);
            HgCommand command = new HgCommand(this);
            getCommand("hungergames").setExecutor(command);
            getCommand("hungergames").setTabCompleter(command);
            getServer().getPluginManager().registerEvents(new GameListener(this, game), this);
            for (Player player : getServer().getOnlinePlayers()) game.recover(player);
            getLogger().info("Hunger Games enabled. Set up your arena with /hg help.");
        } catch (RuntimeException ex) {
            getLogger().log(Level.SEVERE, "Could not enable Hunger Games", ex);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (game != null) game.shutdown();
    }

    public Arena getArena() { return arena; }
    public GameManager getGame() { return game; }

    public void reloadSettings() {
        if (!game.isIdle()) throw new IllegalStateException("Stop the match and remove all queued players before reloading.");
        reloadConfig();
        arena.reload();
        loot.reload();
    }
}
