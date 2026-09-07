package com.hungergames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** Command routing; game rules and player recovery remain owned by GameManager. */
public final class HgCommand implements CommandExecutor, TabCompleter {
    private static final String PLAY_PERMISSION = "hungergames.play";
    private static final String ADMIN_PERMISSION = "hungergames.admin";
    private static final String[] SUBCOMMANDS = {
        "help", "join", "leave", "status", "setlobby", "setcenter", "addspawn",
        "clearspawns", "start", "stop", "reload"
    };

    private final HungerGamesPlugin plugin;

    public HgCommand(HungerGamesPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String subcommand = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!isKnown(subcommand)) {
            error(sender, "Unknown subcommand. Use /" + label + " help.");
            return true;
        }
        if (args.length > 1) {
            error(sender, "Usage: /" + label + " " + subcommand + " (no additional arguments)");
            return true;
        }
        if (isAdminCommand(subcommand) && !sender.hasPermission(ADMIN_PERMISSION)) {
            error(sender, "You need " + ADMIN_PERMISSION + " to use this command.");
            return true;
        }
        if (subcommand.equals("join") && !sender.hasPermission(PLAY_PERMISSION)) {
            error(sender, "You need " + PLAY_PERMISSION + " to join.");
            return true;
        }
        if (requiresPlayer(subcommand) && !(sender instanceof Player)) {
            error(sender, "This command must be used by a player in-game.");
            return true;
        }

        try {
            if ((isSetupCommand(subcommand) || subcommand.equals("reload"))
                    && !plugin.getGame().isIdle()) {
                error(sender, "Setup and reload require WAITING with no participants. Use /"
                        + label + " stop first, and let any pending player recovery finish.");
                return true;
            }
            switch (subcommand) {
                case "help":
                    sendHelp(sender, label);
                    break;
                case "join":
                    plugin.getGame().join((Player) sender);
                    break;
                case "leave":
                    // Never gate recovery on permissions, membership, or the current phase.
                    plugin.getGame().leave((Player) sender);
                    break;
                case "status":
                    plugin.getGame().sendStatus(sender);
                    break;
                case "setlobby":
                    plugin.getArena().setLobby(((Player) sender).getLocation());
                    success(sender, "Lobby saved at your location.");
                    break;
                case "setcenter":
                    plugin.getArena().setCenter(((Player) sender).getLocation());
                    success(sender, "Arena center saved at your location.");
                    break;
                case "addspawn":
                    plugin.getArena().addSpawn(((Player) sender).getLocation());
                    success(sender, "Spawn saved. Configured spawns: " + plugin.getArena().getSpawns().size());
                    break;
                case "clearspawns":
                    plugin.getArena().clearSpawns();
                    success(sender, "All arena spawns cleared.");
                    break;
                case "start":
                    // Force-start still validates minimum players and the arena in GameManager.
                    plugin.getGame().forceStart(sender);
                    break;
                case "stop":
                    plugin.getGame().stop();
                    success(sender, "Stop requested. Players with stalled recovery can retry /"
                            + label + " leave after respawning.");
                    break;
                case "reload":
                    plugin.reloadSettings();
                    success(sender, "Hunger Games settings reloaded.");
                    break;
                default:
                    throw new IllegalStateException("Unhandled subcommand: " + subcommand);
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed /" + label + " " + subcommand + " for " + sender.getName(), ex);
            String detail = ex.getMessage();
            error(sender, "Could not complete /" + label + " " + subcommand
                    + (detail == null || detail.trim().isEmpty() ? "." : ": "
                    + ChatColor.stripColor(detail).replace('\n', ' ').replace('\r', ' '))
                    + " See the server log for details.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return Collections.emptyList();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<String>();
        for (String subcommand : SUBCOMMANDS) {
            if (subcommand.startsWith(prefix) && canShow(sender, subcommand)) {
                matches.add(subcommand);
            }
        }
        return matches;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "Hunger Games commands:");
        help(sender, label, "help", "Show this help.");
        help(sender, label, "join", "Join the waiting game.");
        help(sender, label, "leave", "Leave or retry stalled inventory recovery; respawn first if dead.");
        help(sender, label, "status", "Show the game status.");
        help(sender, label, "setlobby", "Save your location as the lobby (idle only).");
        help(sender, label, "setcenter", "Save your location as the safe arena center (idle only).");
        help(sender, label, "addspawn", "Save an individual spawn at your location (idle only).");
        help(sender, label, "clearspawns", "Remove all spawn pads from setup (idle only).");
        help(sender, label, "start", "Start now; still requires at least two players and a valid arena.");
        help(sender, label, "stop", "Stop the game and restore players.");
        help(sender, label, "reload", "Reload settings (idle only).");
    }

    private void help(CommandSender sender, String label, String subcommand, String description) {
        if (canShow(sender, subcommand)) {
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " " + subcommand
                    + ChatColor.GRAY + " - " + description);
        }
    }

    private static boolean canShow(CommandSender sender, String subcommand) {
        return (!requiresPlayer(subcommand) || sender instanceof Player)
                && (!isAdminCommand(subcommand) || sender.hasPermission(ADMIN_PERMISSION))
                && (!subcommand.equals("join") || sender.hasPermission(PLAY_PERMISSION));
    }

    private static boolean requiresPlayer(String subcommand) {
        return subcommand.equals("join") || subcommand.equals("leave")
                || subcommand.equals("setlobby") || subcommand.equals("setcenter")
                || subcommand.equals("addspawn");
    }

    private static boolean isSetupCommand(String subcommand) {
        return subcommand.equals("setlobby") || subcommand.equals("setcenter")
                || subcommand.equals("addspawn") || subcommand.equals("clearspawns");
    }

    private static boolean isAdminCommand(String subcommand) {
        return isSetupCommand(subcommand) || subcommand.equals("start")
                || subcommand.equals("stop") || subcommand.equals("reload");
    }

    private static boolean isKnown(String subcommand) {
        for (String candidate : SUBCOMMANDS) {
            if (candidate.equals(subcommand)) {
                return true;
            }
        }
        return false;
    }

    private static void success(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.GREEN + message);
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.RED + message);
    }
}
