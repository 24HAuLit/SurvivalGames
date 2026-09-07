package com.hungergames;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.junit.Before;
import org.junit.Test;

public class GameListenerTest {
    private GameManager game;
    private GameListener listener;

    @Before
    public void setUp() {
        game = mock(GameManager.class);
        listener = new GameListener(mock(HungerGamesPlugin.class), game);
        when(game.getPhase()).thenReturn(GamePhase.RUNNING);
    }

    @Test
    public void outsiderDamageAcrossMatchBoundaryIsCancelledInBothDirections() {
        Player participant = player("participant", true, true);
        Player outsider = player("outsider", false, false);

        EntityDamageByEntityEvent incoming = damage(outsider, participant);
        EntityDamageByEntityEvent outgoing = damage(participant, outsider);
        Projectile arrow = mock(Projectile.class);
        when(arrow.getShooter()).thenReturn(outsider);
        EntityDamageByEntityEvent projectile = damage(arrow, participant);

        listener.onDamage(incoming);
        listener.onDamage(outgoing);
        listener.onDamage(projectile);

        verify(incoming).setCancelled(true);
        verify(outgoing).setCancelled(true);
        verify(projectile).setCancelled(true);

        EntityDamageByEntityEvent unrelated = damage(outsider, player("other outsider", false, false));
        listener.onDamage(unrelated);
        verify(unrelated, never()).setCancelled(anyBoolean());
    }

    @Test
    public void spectatorCannotDealOrReceiveDamageIncludingEnvironmentalDamage() {
        Player participant = player("participant", true, true);
        Player spectator = player("spectator", true, false);
        EntityDamageByEntityEvent incoming = damage(participant, spectator);
        EntityDamageByEntityEvent outgoing = damage(spectator, participant);
        EntityDamageEvent environmental = environmentalDamage(spectator);

        listener.onDamage(incoming);
        listener.onDamage(outgoing);
        listener.onDamage(environmental);

        verify(incoming).setCancelled(true);
        verify(outgoing, atLeastOnce()).setCancelled(true);
        verify(outgoing, never()).setCancelled(false);
        verify(environmental).setCancelled(true);
    }

    @Test
    public void liveParticipantsCanTakeMeleeProjectileAndEnvironmentalDamageDuringCombat() {
        Player attacker = player("attacker", true, true);
        Player victim = player("victim", true, true);
        Projectile arrow = mock(Projectile.class);
        when(arrow.getShooter()).thenReturn(attacker);

        for (GamePhase phase : Arrays.asList(GamePhase.RUNNING, GamePhase.DEATHMATCH)) {
            when(game.getPhase()).thenReturn(phase);
            EntityDamageByEntityEvent melee = damage(attacker, victim);
            EntityDamageByEntityEvent projectile = damage(arrow, victim);
            EntityDamageEvent environmental = environmentalDamage(victim);

            listener.onDamage(melee);
            listener.onDamage(projectile);
            listener.onDamage(environmental);

            verify(melee, never()).setCancelled(anyBoolean());
            verify(projectile, never()).setCancelled(anyBoolean());
            verify(environmental, never()).setCancelled(anyBoolean());
        }
    }

    @Test
    public void graceCancelsParticipantCombatAndEnvironmentalDamage() {
        when(game.getPhase()).thenReturn(GamePhase.GRACE);
        Player attacker = player("attacker", true, true);
        Player victim = player("victim", true, true);
        EntityDamageByEntityEvent combat = damage(attacker, victim);
        EntityDamageEvent environmental = environmentalDamage(victim);

        listener.onDamage(combat);
        listener.onDamage(environmental);

        verify(combat).setCancelled(true);
        verify(environmental).setCancelled(true);
    }

    @Test
    public void trackedItemOutsideArenaCannotBePickedUpByOutsiderOrHopper() {
        Item item = trackedItemOutsideArena();
        PlayerPickupItemEvent pickup = pickup(player("outsider", false, false), item);
        InventoryPickupItemEvent hopper = mock(InventoryPickupItemEvent.class);
        Inventory inventory = mock(Inventory.class);
        when(hopper.getItem()).thenReturn(item);
        when(hopper.getInventory()).thenReturn(inventory);
        when(game.isArenaInventory(inventory)).thenReturn(false);

        listener.onPickup(pickup);
        listener.onHopperPickup(hopper);

        verify(pickup).setCancelled(true);
        verify(hopper).setCancelled(true);
    }

    @Test
    public void trackedItemOutsideArenaIsAvailableToLiveParticipantButNotSpectator() {
        Item item = trackedItemOutsideArena();
        PlayerPickupItemEvent livePickup = pickup(player("participant", true, true), item);
        PlayerPickupItemEvent spectatorPickup = pickup(player("spectator", true, false), item);

        listener.onPickup(livePickup);
        listener.onPickup(spectatorPickup);

        verify(livePickup, never()).setCancelled(anyBoolean());
        verify(spectatorPickup).setCancelled(true);
    }

    @Test
    public void potionSplashIsolatesSourcesAndTargetsWithoutSuppressingEligibleTargets() {
        Player participant = player("participant", true, true);
        Player teammate = player("other participant", true, true);
        Player spectator = player("spectator", true, false);
        Player outsider = player("outsider", false, false);
        LivingEntity mob = mock(LivingEntity.class);

        PotionSplashEvent matchSplash = splash(participant, teammate, spectator, outsider, mob);
        listener.onPotionSplash(matchSplash);
        verify(matchSplash, never()).setIntensity(eq(teammate), anyDouble());
        verify(matchSplash).setIntensity(spectator, 0.0);
        verify(matchSplash).setIntensity(outsider, 0.0);
        verify(matchSplash).setIntensity(mob, 0.0);

        PotionSplashEvent outsiderSplash = splash(outsider, participant, outsider, mob);
        listener.onPotionSplash(outsiderSplash);
        verify(outsiderSplash).setIntensity(participant, 0.0);
        verify(outsiderSplash, never()).setIntensity(eq(outsider), anyDouble());
        verify(outsiderSplash, never()).setIntensity(eq(mob), anyDouble());

        PotionSplashEvent spectatorSplash = splash(spectator, participant, outsider);
        listener.onPotionSplash(spectatorSplash);
        verify(spectatorSplash).setIntensity(participant, 0.0);
        verify(spectatorSplash).setIntensity(outsider, 0.0);

        PotionSplashEvent sourcelessSplash = splash(null, participant, outsider);
        listener.onPotionSplash(sourcelessSplash);
        verify(sourcelessSplash).setIntensity(participant, 0.0);
        verify(sourcelessSplash, never()).setIntensity(eq(outsider), anyDouble());

        when(game.getPhase()).thenReturn(GamePhase.GRACE);
        PotionSplashEvent graceSplash = splash(participant, teammate);
        listener.onPotionSplash(graceSplash);
        verify(graceSplash).setIntensity(teammate, 0.0);
    }

    @Test
    public void managedCommandsAreRestrictedButRecoveryLeaveAliasesAndOutsidersAreAllowed() {
        Player participant = player("participant", true, true);
        Player recovering = player("recovering", true, false);
        when(game.isParticipant(recovering)).thenReturn(false);
        when(game.isRecovering(recovering)).thenReturn(true);

        for (Player managed : Arrays.asList(participant, recovering)) {
            for (String command : Arrays.asList("/spawn", "/minecraft:kill", "/hgadmin", "/other:hg leave")) {
                PlayerCommandPreprocessEvent event = command(managed, command);
                listener.onCommand(event);
                verify(event).setCancelled(true);
            }
            verify(game, times(4)).tell(managed,
                    "Only /hg commands are available during a match. Use /hg leave to exit.");

            for (String command : Arrays.asList("/hg leave", "/sg leave", "/hungergames leave",
                    "/hungergames:hg leave", "/hungergames:sg leave", "/hungergames:hungergames leave",
                    "/HG LEAVE")) {
                PlayerCommandPreprocessEvent event = command(managed, command);
                listener.onCommand(event);
                verify(event, never()).setCancelled(anyBoolean());
            }
        }

        Player outsider = player("outsider", false, false);
        PlayerCommandPreprocessEvent unrelated = command(outsider, "/spawn");
        listener.onCommand(unrelated);
        verify(unrelated, never()).setCancelled(anyBoolean());
        verify(game, never()).tell(eq(outsider), anyString());
    }

    private Player player(String name, boolean managed, boolean alive) {
        Player player = mock(Player.class, name);
        when(game.isManaged(player)).thenReturn(managed);
        when(game.isParticipant(player)).thenReturn(managed);
        when(game.isAlive(player)).thenReturn(alive);
        when(game.canPlay(player)).thenReturn(alive);
        return player;
    }

    // Mock damage events to avoid their constructors consulting Bukkit's server.
    private EntityDamageByEntityEvent damage(Entity attacker, Player victim) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getEntity()).thenReturn(victim);
        return event;
    }

    private EntityDamageEvent environmentalDamage(Player victim) {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
        return event;
    }

    private Item trackedItemOutsideArena() {
        Item item = mock(Item.class);
        Location outside = new Location(null, 1000, 64, 1000);
        when(item.getLocation()).thenReturn(outside);
        when(game.isArenaProtected(outside)).thenReturn(false);
        when(game.isMatchEntity(item)).thenReturn(true);
        return item;
    }

    private PlayerPickupItemEvent pickup(Player player, Item item) {
        PlayerPickupItemEvent event = mock(PlayerPickupItemEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getItem()).thenReturn(item);
        return event;
    }

    private PotionSplashEvent splash(Player source, LivingEntity... targets) {
        ThrownPotion potion = mock(ThrownPotion.class);
        when(potion.getShooter()).thenReturn(source);
        PotionSplashEvent event = mock(PotionSplashEvent.class);
        when(event.getEntity()).thenReturn(potion);
        when(event.getAffectedEntities()).thenReturn(Arrays.asList(targets));
        return event;
    }

    private PlayerCommandPreprocessEvent command(Player player, String message) {
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn(message);
        return event;
    }
}
