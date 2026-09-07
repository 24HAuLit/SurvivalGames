package com.hungergames;

import java.util.Iterator;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.projectiles.ProjectileSource;

public final class GameListener implements Listener {
    private final HungerGamesPlugin plugin;
    private final GameManager game;

    public GameListener(HungerGamesPlugin plugin, GameManager game) {
        this.plugin = plugin;
        this.game = game;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Recover synchronously so inventory/drop events cannot run before the lock.
        game.recover(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { game.disconnect(event.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!game.isManaged(player)) return;
        boolean dropLoot = game.canPlay(player);
        if (!dropLoot) event.getDrops().clear();
        event.setKeepInventory(false);
        event.setKeepLevel(false);
        event.setDroppedExp(0);
        event.setNewExp(0);
        event.setNewLevel(0);
        event.setNewTotalExp(0);
        event.setDeathMessage(null);
        game.eliminate(player, player.getKiller());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(final PlayerRespawnEvent event) {
        if (!game.isManaged(event.getPlayer())) return;
        final Object token = game.getParticipationToken(event.getPlayer());
        Location location = game.respawnLocation(event.getPlayer());
        if (location != null) event.setRespawnLocation(location);
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() { game.afterRespawn(event.getPlayer(), token); }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Player victim = event.getEntity() instanceof Player ? (Player) event.getEntity() : null;
        Player attacker = event instanceof EntityDamageByEntityEvent
                ? attackingPlayer(((EntityDamageByEntityEvent) event).getDamager()) : null;
        if (victim != null && game.isManaged(victim)) {
            if (!game.isAlive(victim) || !game.getPhase().allowsDamage()) {
                event.setCancelled(true);
                return;
            }
            if (attacker != null && !game.isAlive(attacker)) event.setCancelled(true);
        }
        if (attacker != null && game.isManaged(attacker)) {
            if (!game.canPlay(attacker) || !game.getPhase().allowsDamage()
                    || (victim != null && !game.isAlive(victim))) event.setCancelled(true);
        }
        // Keep decorative entities intact; this plugin does not reset the world.
        if (game.isArenaProtected(event.getEntity().getLocation())
                && (event.getEntity() instanceof org.bukkit.entity.Hanging
                || event.getEntity() instanceof org.bukkit.entity.ArmorStand)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        Player source = attackingPlayer(event.getEntity());
        boolean matchSource = source != null && game.isManaged(source);
        for (org.bukkit.entity.LivingEntity target : event.getAffectedEntities()) {
            boolean matchTarget = target instanceof Player && game.isManaged((Player) target);
            if (matchTarget) {
                Player player = (Player) target;
                if (!game.canPlay(player) || !game.getPhase().allowsDamage()
                        || source == null || !game.canPlay(source)) event.setIntensity(target, 0);
            } else if (matchSource) event.setIntensity(target, 0);
        }
    }

    private Player attackingPlayer(Entity entity) {
        if (entity instanceof Player) return (Player) entity;
        if (entity instanceof Projectile) {
            ProjectileSource source = ((Projectile) entity).getShooter();
            if (source instanceof Player) return (Player) source;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (game.isManaged(player) && !game.canPlay(player)) event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;
        Player player = event.getPlayer();
        if (!game.isManaged(player)) return;
        Location to = event.getTo();
        if (game.isRecovering(player)) {
            Location stay = event.getFrom().clone();
            stay.setYaw(to.getYaw());
            stay.setPitch(to.getPitch());
            event.setTo(stay);
        } else if (game.getPhase().allowsJoining()) {
            Location lobby = plugin.getArena().getLobby();
            if (lobby != null && (!lobby.getWorld().equals(to.getWorld())
                    || Math.hypot(to.getX() - lobby.getX(), to.getZ() - lobby.getZ()) > 16
                    || Math.abs(to.getY() - lobby.getY()) > 8)) event.setTo(lobby);
        } else if (!plugin.getArena().contains(to)) {
            Location from = event.getFrom();
            event.setTo(plugin.getArena().contains(from) ? from : plugin.getArena().getCenter());
        } else if (!game.isAlive(player) && to.getY() < 1) {
            event.setTo(plugin.getArena().getCenter());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!game.isManaged(player) || game.isInternalTeleport(player)) return;
        if (game.canPlay(player) && event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                && plugin.getArena().contains(event.getTo())) return;
        if (game.isParticipant(player) && !game.isAlive(player)
                && event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE
                && plugin.getArena().contains(event.getTo())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (game.isManaged(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!game.isManaged(event.getPlayer())) return;
        String label = event.getMessage().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (label.equals("/hg") || label.equals("/sg") || label.equals("/hungergames")
                || label.equals("/hungergames:hg") || label.equals("/hungergames:sg")
                || label.equals("/hungergames:hungergames")) return;
        event.setCancelled(true);
        game.tell(event.getPlayer(), "Only /hg commands are available during a match. Use /hg leave to exit.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (game.isManaged(event.getPlayer()) || game.isArenaProtected(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (game.isManaged(event.getPlayer()) || game.isArenaProtected(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (game.isManaged(player)) {
            if (!game.canPlay(player)) event.setCancelled(true);
            else if (block != null && block.getType() != Material.CHEST
                    && block.getType() != Material.TRAPPED_CHEST && block.getType() != Material.WORKBENCH) {
                // Deny doors, buttons, farmland trampling and other persistent map changes,
                // but still permit eating/shooting the held item.
                event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            }
        } else if (block != null && game.isArenaProtected(block.getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (game.isManaged(event.getPlayer()) || game.isArenaProtected(event.getRightClicked().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (game.isManaged(event.getPlayer()) || game.isArenaProtected(event.getRightClicked().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (game.isManaged(event.getPlayer()) || game.isArenaProtected(event.getBlockClicked().getLocation())
                || game.isArenaProtected(event.getBlockClicked().getRelative(event.getBlockFace()).getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (game.isManaged(event.getPlayer()) || game.isArenaProtected(event.getBlockClicked().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        if (game.isManaged(player)) {
            if (!game.canPlay(player)) event.setCancelled(true);
            else if (event.getInventory().getType() != InventoryType.WORKBENCH
                    && !game.openChest(player, event.getInventory())) event.setCancelled(true);
        } else if (game.isArenaInventory(event.getInventory())) event.setCancelled(true);
    }

    private boolean blockInventory(Player player, Inventory inventory) {
        return game.isManaged(player) ? !game.canPlay(player) : game.isArenaInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player
                && blockInventory((Player) event.getWhoClicked(), event.getView().getTopInventory())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player
                && blockInventory((Player) event.getWhoClicked(), event.getView().getTopInventory())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        if (game.isArenaInventory(event.getSource()) || game.isArenaInventory(event.getDestination())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (game.isMatchEntity(event.getItem()) || game.isArenaInventory(event.getInventory())
                || game.isArenaProtected(event.getItem().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        if ((game.isManaged(player) && !game.canPlay(player))
                || (!game.canPlay(player) && (game.isMatchEntity(event.getItem())
                || game.isArenaProtected(event.getItem().getLocation())))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if ((game.isManaged(player) && !game.canPlay(player))
                || (!game.isParticipant(player) && game.isArenaProtected(player.getLocation()))) event.setCancelled(true);
        else if (game.isParticipant(player)) game.trackEntity(event.getItemDrop());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        // Do not let a tracked drop lose its ownership by merging with an outside item.
        if (game.isMatchEntity(event.getEntity()) != game.isMatchEntity(event.getTarget())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (game.isArenaProtected(event.getLocation())) game.trackEntity(event.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent event) {
        Player player = attackingPlayer(event.getEntity());
        if (player != null && game.isManaged(player) && !game.canPlay(player)) event.setCancelled(true);
        else if (game.isArenaProtected(event.getEntity().getLocation()) || (player != null && game.isParticipant(player))) {
            game.trackEntity(event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicle(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player && game.isManaged((Player) event.getEntered())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) { protectBlocks(event.blockList().iterator()); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) { protectBlocks(event.blockList().iterator()); }

    private void protectBlocks(Iterator<Block> blocks) {
        while (blocks.hasNext()) if (game.isArenaProtected(blocks.next().getLocation())) blocks.remove();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityBlock(EntityChangeBlockEvent event) {
        if (game.isArenaProtected(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHanging(HangingBreakEvent event) {
        if (game.isArenaProtected(event.getEntity().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (game.isArenaProtected(event.getBlock().getLocation())
                || (event.getPlayer() != null && game.isManaged(event.getPlayer()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (game.isArenaProtected(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (game.isArenaProtected(event.getBlock().getLocation()) || game.isArenaProtected(event.getToBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (game.isArenaProtected(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (game.isArenaProtected(event.getBlock().getLocation())) event.setCancelled(true);
        for (Block block : event.getBlocks()) {
            if (game.isArenaProtected(block.getLocation())
                    || game.isArenaProtected(block.getRelative(event.getDirection()).getLocation())) event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (game.isArenaProtected(event.getBlock().getLocation())
                || game.isArenaProtected(event.getRetractLocation())) event.setCancelled(true);
    }
}
