// RescuePlugin.java
package com.example.downedPlayer;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import java.util.*;


public class RescuePlugin extends JavaPlugin implements Listener {

    private final Map<UUID, DownedPlayer> downedPlayers = new HashMap<>();
    private final Map<UUID, RescueSession> rescueSessions = new HashMap<>();
    private int maxWaitTime;
    private double reviveHealth;
    private boolean preventJump;
    private static final Material TOTEM = Material.TOTEM_OF_UNDYING;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        loadConfig();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new CombatListener(), this);
        getServer().getPluginManager().registerEvents(new MovementListener(), this);
        getServer().getPluginManager().registerEvents(new InteractionListener(), this);
        getServer().getPluginManager().registerEvents(new ActionRestrictionListener(), this);

        new BukkitRunnable() {
            @Override
            public void run() {
                new ArrayList<>(downedPlayers.entrySet()).forEach(entry -> {
                    DownedPlayer dp = entry.getValue();
                    Player p = dp.getPlayer();

                    dp.update(p.isSneaking() ? -10 : -1);
                    checkAutoRevive(p);

                    if (dp.isExpired()) {
                        p.setHealth(0);
                        dp.remove();
                        downedPlayers.remove(entry.getKey());
                    }
                });
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void loadConfig() {
        maxWaitTime = getConfig().getInt("max-wait-time", 45);
        reviveHealth = getConfig().getDouble("revive-health", 0.2);
        preventJump = getConfig().getBoolean("prevent-jump", true);
    }

    private void checkAutoRevive(Player p) {
        AttributeInstance maxHealthAttr = p.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null && p.getHealth() >= maxHealthAttr.getValue()) {
            revivePlayer(p, true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityTarget(EntityTargetEvent e) {
        if (!(e.getTarget() instanceof Player)) return;
        Player target = (Player) e.getTarget();
        if (downedPlayers.containsKey(target.getUniqueId())) {
            e.setCancelled(true);
            clearExistingTarget(target);
        }
    }

    private void clearExistingTarget(Player target) {
        target.getWorld().getNearbyEntities(target.getLocation(), 24, 24, 24).forEach(entity -> {
            if (entity instanceof Mob) {
                Mob mob = (Mob) entity;
                if (mob.getTarget() != null && mob.getTarget().getUniqueId().equals(target.getUniqueId())) {
                    mob.setTarget(null);
                }
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        DownedPlayer dp = downedPlayers.remove(p.getUniqueId());
        if (dp != null) dp.remove();
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        DownedPlayer dp = downedPlayers.get(p.getUniqueId());
        if (dp != null) dp.updateHologramPosition();
    }

    private class CombatListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onDamage(EntityDamageEvent e) {
            if (!(e.getEntity() instanceof Player)) return;
            Player p = (Player) e.getEntity();

            if (downedPlayers.containsKey(p.getUniqueId())) {
                e.setCancelled(true);
                return;
            }

            if (p.getHealth() - e.getFinalDamage() <= 0) {
                boolean used = checkHand(p, p.getInventory().getItemInOffHand(), true) ||
                        checkHand(p, p.getInventory().getItemInMainHand(), false);

                if (used) {
                    e.setCancelled(true);
                    p.setHealth(1.0);
                    enterDownedState(p);
                    return;
                }

                e.setCancelled(true);
                enterDownedState(p);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onAttack(EntityDamageByEntityEvent e) {
            handleDownedPlayerAttack(e);
            handleAttackOnDownedPlayer(e);
        }

        private void handleDownedPlayerAttack(EntityDamageByEntityEvent e) {
            if (e.getDamager() instanceof Player) {
                Player attacker = (Player) e.getDamager();
                if (downedPlayers.containsKey(attacker.getUniqueId())) {
                    e.setCancelled(true);
                    attacker.sendActionBar("§c倒地时无法攻击！");
                }
            }
        }

        private void handleAttackOnDownedPlayer(EntityDamageByEntityEvent e) {
            if (!(e.getEntity() instanceof Player)) return;
            Player target = (Player) e.getEntity();
            if (!downedPlayers.containsKey(target.getUniqueId())) return;

            e.setCancelled(true);

            if (e.getDamager() instanceof Player) {
                Player attacker = (Player) e.getDamager();
                DownedPlayer dp = downedPlayers.get(target.getUniqueId());
                dp.deductTime(5);

                String message = String.format("§c-5秒 §7剩余时间: §e%d秒", dp.getRemainingTime());
                showFloatingText(target, message);
                attacker.sendActionBar(message);
            }
        }

        private void showFloatingText(Player target, String text) {
            ArmorStand as = target.getWorld().spawn(target.getLocation().add(0, 2.5, 0), ArmorStand.class, stand -> {
                stand.setVisible(false);
                stand.setCustomNameVisible(true);
                stand.setCustomName(text);
                stand.setGravity(false);
                stand.setInvulnerable(true);
                stand.addScoreboardTag("rescue_time_display");
            });
            new BukkitRunnable() {
                @Override
                public void run() {
                    as.remove();
                }
            }.runTaskLater(RescuePlugin.this, 20L);
        }

        private boolean checkHand(Player p, ItemStack item, boolean isOffhand) {
            if (item != null && item.getType() == TOTEM) {
                triggerTotemEffect(p);
                consumeItem(p, item, isOffhand);
                return true;
            }
            return false;
        }

        private void triggerTotemEffect(Player p) {
            p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getEyeLocation(), 250, 0.5, 0.5, 0.5, 0.5);
            p.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);
            p.setHealth(1.0);
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
            p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
            p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
        }

        private void consumeItem(Player p, ItemStack item, boolean isOffhand) {
            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
            } else {
                if (isOffhand) {
                    p.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
                } else {
                    p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                }
            }
        }
    }

    private class MovementListener implements Listener {
        private final Set<UUID> jumpCooldown = new HashSet<>();

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onMove(PlayerMoveEvent e) {
            Player p = e.getPlayer();
            handleDownedPlayerMovement(p, e);
            handleRescuerMovement(p);
        }

        private void handleDownedPlayerMovement(Player p, PlayerMoveEvent e) {
            DownedPlayer dp = downedPlayers.get(p.getUniqueId());
            if (dp == null) return;

            if (preventJump && (p.getVelocity().getY() > 0.4 || (!p.isOnGround() && p.getLocation().getY() % 1 > 0.001))) {
                e.setCancelled(true);
                p.setVelocity(new Vector(0, -0.5, 0));
                p.sendActionBar("§c倒地状态禁止跳跃");
                p.teleport(e.getFrom().clone().add(0, 0.1, 0));

                if (!jumpCooldown.contains(p.getUniqueId())) {
                    jumpCooldown.add(p.getUniqueId());
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            jumpCooldown.remove(p.getUniqueId());
                        }
                    }.runTaskLater(RescuePlugin.this, 20L);
                }
            }
        }

        private void handleRescuerMovement(Player p) {
            RescueSession session = rescueSessions.get(p.getUniqueId());
            if (session != null) session.checkConditions();
        }
    }

    private class InteractionListener implements Listener {
        @EventHandler
        public void onInteract(PlayerInteractEntityEvent e) {
            if (!(e.getRightClicked() instanceof Player)) return;
            Player rescuer = e.getPlayer();
            Player target = (Player) e.getRightClicked();

            if (downedPlayers.containsKey(target.getUniqueId())) {
                startRescueSession(rescuer, target);
            }
        }

        private void startRescueSession(Player rescuer, Player target) {
            if (rescuer.getUniqueId().equals(target.getUniqueId())) return;
            if (rescueSessions.containsKey(rescuer.getUniqueId())) return;

            RescueSession session = new RescueSession(rescuer, target);
            if (session.isValid()) {
                rescueSessions.put(rescuer.getUniqueId(), session);
                session.start();
            }
        }
    }

    private class ActionRestrictionListener implements Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        public void onInteract(PlayerInteractEvent e) {
            if (downedPlayers.containsKey(e.getPlayer().getUniqueId())) {
                e.setCancelled(true);
                e.getPlayer().sendActionBar("§c倒地时无法交互物品");
            }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onBlockPlace(BlockPlaceEvent e) {
            if (downedPlayers.containsKey(e.getPlayer().getUniqueId())) {
                e.setCancelled(true);
                e.getPlayer().sendActionBar("§c倒地时无法放置方块");
            }
        }
    }

    private void enterDownedState(Player p) {
        p.setHealth(1.0);
        p.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOWNESS,
                Integer.MAX_VALUE,
                5,
                false,
                false,
                true
        ));
        downedPlayers.put(p.getUniqueId(), new DownedPlayer(p, maxWaitTime, this));
        p.sendTitle("§c§l你倒下了！", "§e生命回满自动复活 或等待救援", 10, 60, 10);
        clearExistingTarget(p);
    }

    private void revivePlayer(Player p, boolean naturalHeal) {
        DownedPlayer dp = downedPlayers.remove(p.getUniqueId());
        if (dp != null) {
            dp.remove();
            p.removePotionEffect(PotionEffectType.SLOWNESS);

            if (!naturalHeal) {
                AttributeInstance maxHealthAttr = p.getAttribute(Attribute.MAX_HEALTH);
                if (maxHealthAttr != null) {
                    p.setHealth(Math.min(maxHealthAttr.getValue() * reviveHealth, maxHealthAttr.getValue()));
                }
            }

            p.sendTitle("§a§l复活成功！",
                    naturalHeal ? "§e自然恢复满血" : "§e生命恢复至" + (reviveHealth*100) + "%",
                    10, 40, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        }
    }

    @Override
    public void onDisable() {
        new ArrayList<>(downedPlayers.values()).forEach(DownedPlayer::remove);
        downedPlayers.clear();
    }

    private class RescueSession {
        private final Player rescuer;
        private final Player target;
        private final BossBar bossBar;
        private BukkitTask task;
        private int progress;
        private static final int REQUIRED_PROGRESS = 5;

        RescueSession(Player rescuer, Player target) {
            this.rescuer = rescuer;
            this.target = target;
            this.bossBar = Bukkit.createBossBar("§a救援进度...", BarColor.GREEN, BarStyle.SOLID);
        }

        boolean isValid() {
            return rescuer != null && target != null &&
                    rescuer.isOnline() && target.isOnline() &&
                    rescuer.getLocation().distanceSquared(target.getLocation()) <= 9 &&
                    isFacingRescuer();
        }

        void start() {
            bossBar.addPlayer(rescuer);
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    if (!checkConditions()) {
                        cancel();
                        return;
                    }

                    progress++;
                    bossBar.setProgress((double) progress / REQUIRED_PROGRESS);

                    if (progress >= REQUIRED_PROGRESS) {
                        completeRescue();
                        cancel();
                    }
                }
            }.runTaskTimer(RescuePlugin.this, 0L, 20L);
        }

        boolean checkConditions() {
            if (!isValid()) {
                rescuer.sendActionBar("§c救援中断！");
                bossBar.removeAll();
                rescueSessions.remove(rescuer.getUniqueId());
                return false;
            }
            return true;
        }

        void completeRescue() {
            revivePlayer(target, false);
            rescuer.sendMessage("§a成功救援 " + target.getName());
            bossBar.removeAll();
            rescueSessions.remove(rescuer.getUniqueId());
        }

        boolean isFacingRescuer() {
            Vector rescuerDirection = rescuer.getLocation().getDirection();
            Vector toTarget = target.getLocation().toVector().subtract(rescuer.getLocation().toVector()).normalize();
            return rescuerDirection.dot(toTarget) > 0.8;
        }

        void cancel() {
            if (task != null) task.cancel();
            bossBar.removeAll();
        }
    }
}