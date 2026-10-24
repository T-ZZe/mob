package fr.mobs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MobsPlugin extends JavaPlugin implements Listener, CommandExecutor {

    // ====== LES 3 MOBS ======
    enum Type {
        FLAME("flame", "Gardien des Flammes", "§6", EntityType.HUSK, Material.HUSK_SPAWN_EGG,
                120, 8, 0.26, "Nova de feu : une onde de flammes explose autour de lui."),
        STORM("storm", "Chevalier de la Tempête", "§b", EntityType.VINDICATOR, Material.VINDICATOR_SPAWN_EGG,
                100, 9, 0.28, "Charge éclair : il fonce sur toi en tranchant tout."),
        SHADOW("shadow", "Spectre des Ombres", "§5", EntityType.WITHER_SKELETON, Material.WITHER_SKELETON_SPAWN_EGG,
                90, 10, 0.30, "Frappe d'ombre : il se téléporte dans ton dos et frappe.");

        final String id, label, color, power;
        final EntityType entity;
        final Material egg;
        final double health, damage, speed;

        Type(String id, String label, String color, EntityType entity, Material egg,
             double health, double damage, double speed, String power) {
            this.id = id; this.label = label; this.color = color; this.entity = entity; this.egg = egg;
            this.health = health; this.damage = damage; this.speed = speed; this.power = power;
        }

        static Type byId(String id) {
            if (id == null) return null;
            for (Type t : values()) if (t.id.equalsIgnoreCase(id)) return t;
            return null;
        }
    }

    // ====== REGLAGES ======
    static final long COOLDOWN_MS = 9000;      // délai entre deux attaques spéciales
    static final int REWARD_DIAMONDS = 2;      // butin à la mort
    static final int REWARD_XP = 300;

    private NamespacedKey eggKey;
    private NamespacedKey mobKey;
    private final Set<UUID> tracked = new HashSet<>();
    private final Map<UUID, Long> nextUse = new HashMap<>();

    @Override
    public void onEnable() {
        eggKey = new NamespacedKey(this, "egg_type");
        mobKey = new NamespacedKey(this, "mob_type");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("mobegg").setExecutor(this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 10L);
    }

    private static Component c(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s)
                .decoration(TextDecoration.ITALIC, false);
    }

    // ====== OEUFS ======
    private ItemStack createEgg(Type t, int amount) {
        ItemStack it = new ItemStack(t.egg, amount);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(c(t.color + "Oeuf : " + t.label));
        meta.lore(List.of(c("§7" + t.power), c("§8Clic droit sur un bloc pour l'invoquer.")));
        meta.getPersistentDataContainer().set(eggKey, PersistentDataType.STRING, t.id);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta()) return;
        String id = it.getItemMeta().getPersistentDataContainer().get(eggKey, PersistentDataType.STRING);
        if (id == null) return;
        e.setCancelled(true); // pas d'apparition vanilla
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Type t = Type.byId(id);
        if (t == null) return;
        Location loc = e.getClickedBlock().getRelative(e.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        spawn(t, loc);
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE) it.setAmount(it.getAmount() - 1);
    }

    // ====== APPARITION ======
    private void spawn(Type t, Location loc) {
        if (!(loc.getWorld().spawnEntity(loc, t.entity) instanceof LivingEntity le)) return;

        le.customName(c(t.color + t.label));
        le.setCustomNameVisible(true);
        le.setRemoveWhenFarAway(false);
        le.setCanPickupItems(false);
        le.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, t.id);

        setBase(le, Attribute.MAX_HEALTH, t.health);
        le.setHealth(t.health);
        setBase(le, Attribute.ATTACK_DAMAGE, t.damage);
        setBase(le, Attribute.MOVEMENT_SPEED, t.speed);
        setBase(le, Attribute.FOLLOW_RANGE, 40);

        EntityEquipment eq = le.getEquipment();
        if (eq != null) {
            switch (t) {
                case FLAME -> {
                    eq.setHelmet(new ItemStack(Material.GOLDEN_HELMET));
                    eq.setChestplate(new ItemStack(Material.GOLDEN_CHESTPLATE));
                    eq.setItemInMainHand(new ItemStack(Material.BLAZE_ROD));
                    le.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, true, false));
                }
                case STORM -> {
                    eq.setHelmet(new ItemStack(Material.IRON_HELMET));
                    eq.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                    eq.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
                    eq.setItemInMainHand(new ItemStack(Material.IRON_AXE));
                }
                case SHADOW -> {
                    eq.setHelmet(new ItemStack(Material.NETHERITE_HELMET));
                    eq.setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
                    eq.setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
                }
            }
            eq.setHelmetDropChance(0f);
            eq.setChestplateDropChance(0f);
            eq.setLeggingsDropChance(0f);
            eq.setItemInMainHandDropChance(0f);
        }
        tracked.add(le.getUniqueId());
        loc.getWorld().spawnParticle(Particle.EXPLOSION, loc.clone().add(0, 1, 0), 3);
        loc.getWorld().playSound(loc, Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.6f);
    }

    private void setBase(LivingEntity le, Attribute a, double v) {
        AttributeInstance inst = le.getAttribute(a);
        if (inst != null) inst.setBaseValue(v);
    }

    private Type typeOf(Entity en) {
        return Type.byId(en.getPersistentDataContainer().get(mobKey, PersistentDataType.STRING));
    }

    /** Après un redémarrage, on ré-enregistre le mob dès qu'il cible un joueur. */
    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (typeOf(e.getEntity()) != null) tracked.add(e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Type t = typeOf(e.getEntity());
        if (t == null) return;
        tracked.remove(e.getEntity().getUniqueId());
        nextUse.remove(e.getEntity().getUniqueId());
        e.getDrops().clear();
        e.getDrops().add(new ItemStack(Material.DIAMOND, REWARD_DIAMONDS));
        e.setDroppedExp(REWARD_XP);
        Location l = e.getEntity().getLocation();
        l.getWorld().spawnParticle(Particle.EXPLOSION, l.clone().add(0, 1, 0), 2);
    }

    // ====== BOUCLE DES ATTAQUES SPECIALES ======
    private void tick() {
        long now = System.currentTimeMillis();
        Iterator<UUID> it = tracked.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Entity en = Bukkit.getEntity(id);
            if (!(en instanceof Mob mob) || !mob.isValid()) { it.remove(); nextUse.remove(id); continue; }
            Type t = typeOf(mob);
            if (t == null) continue;
            if (!(mob.getTarget() instanceof Player target)) continue;
            if (target.isDead() || !target.getWorld().equals(mob.getWorld())) continue;
            if (target.getGameMode() == GameMode.CREATIVE || target.getGameMode() == GameMode.SPECTATOR) continue;
            Long nu = nextUse.get(id);
            if (nu != null && nu > now) continue;

            double dist = mob.getLocation().distance(target.getLocation());
            boolean fired = switch (t) {
                case FLAME -> dist <= 7 && fire(flameNova(mob));
                case STORM -> dist >= 4 && dist <= 14 && fire(stormCharge(mob, target));
                case SHADOW -> dist >= 3 && dist <= 16 && fire(shadowStrike(mob, target));
            };
            if (fired) nextUse.put(id, now + COOLDOWN_MS);
        }
    }

    private boolean fire(boolean started) { return started; }

    private void ring(Location center, double radius, Particle p, int points) {
        World w = center.getWorld();
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            w.spawnParticle(p, center.clone().add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius), 1, 0, 0, 0, 0);
        }
    }

    // ---- Attaque 1 : NOVA DE FEU ----
    private boolean flameNova(Mob mob) {
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_BLAZE_AMBIENT, 2f, 0.6f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!mob.isValid()) { cancel(); return; }
                Location c = mob.getLocation();
                World w = c.getWorld();
                if (t < 20) {                                   // avertissement : le cercle se charge
                    ring(c, 6, Particle.SMOKE, 30);
                    ring(c, 6 * (t / 20.0), Particle.FLAME, 20);
                } else {                                        // explosion
                    for (double r = 1; r <= 6; r += 1) ring(c, r, Particle.FLAME, (int) (r * 10));
                    w.spawnParticle(Particle.EXPLOSION, c.clone().add(0, 1, 0), 4, 1.5, 0.5, 1.5, 0);
                    w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.8f);
                    for (Player p : w.getPlayers()) {
                        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
                        if (p.getLocation().distance(c) > 6) continue;
                        p.damage(8, mob);
                        p.setFireTicks(100);
                        Vector kb = p.getLocation().toVector().subtract(c.toVector());
                        if (kb.lengthSquared() > 0.01) p.setVelocity(kb.normalize().multiply(1.1).setY(0.5));
                    }
                    cancel();
                    return;
                }
                t += 2;
            }
        }.runTaskTimer(this, 0L, 2L);
        return true;
    }

    // ---- Attaque 2 : CHARGE ECLAIR ----
    private boolean stormCharge(Mob mob, Player target) {
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 1.8f);
        new BukkitRunnable() {
            int t = 0;
            final Set<UUID> hit = new HashSet<>();
            Vector dir;
            @Override public void run() {
                if (!mob.isValid()) { cancel(); return; }
                Location c = mob.getLocation().add(0, 1, 0);
                World w = c.getWorld();
                if (t < 12) {                                   // charge : étincelles autour du mob
                    w.spawnParticle(Particle.ELECTRIC_SPARK, c, 12, 0.5, 0.8, 0.5, 0.05);
                } else if (t == 12) {                           // départ de la charge
                    dir = target.getLocation().toVector().subtract(mob.getLocation().toVector());
                    dir.setY(0);
                    if (dir.lengthSquared() < 0.01) { cancel(); return; }
                    dir.normalize();
                    mob.setVelocity(dir.clone().multiply(1.9).setY(0.2));
                    w.playSound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.7f);
                } else {                                        // en plein élan : traînée + dégâts
                    w.spawnParticle(Particle.SWEEP_ATTACK, c, 2, 0.4, 0.3, 0.4, 0);
                    w.spawnParticle(Particle.CRIT, c, 8, 0.4, 0.5, 0.4, 0.2);
                    for (Player p : w.getPlayers()) {
                        if (hit.contains(p.getUniqueId())) continue;
                        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
                        if (p.getLocation().add(0, 1, 0).distance(c) > 2.3) continue;
                        hit.add(p.getUniqueId());
                        p.damage(9, mob);
                        p.setVelocity(dir.clone().multiply(1.2).setY(0.4));
                        w.spawnParticle(Particle.DAMAGE_INDICATOR, p.getLocation().add(0, 1, 0), 8, 0.3, 0.4, 0.3, 0.1);
                        w.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.5f, 1f);
                    }
                    if (t > 22) { cancel(); return; }
                }
                t++;
            }
        }.runTaskTimer(this, 0L, 1L);
        return true;
    }

    // ---- Attaque 3 : FRAPPE D'OMBRE ----
    private boolean shadowStrike(Mob mob, Player target) {
        Location from = mob.getLocation().add(0, 1, 0);
        from.getWorld().spawnParticle(Particle.SMOKE, from, 40, 0.4, 0.8, 0.4, 0.05);
        from.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, from, 25, 0.4, 0.8, 0.4, 0.05);
        from.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 0.6f);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!mob.isValid() || !target.isOnline() || target.isDead()) return;
            Location tl = target.getLocation();
            Location behind = tl.clone().add(tl.getDirection().setY(0).normalize().multiply(-1.6));
            behind.setDirection(tl.toVector().subtract(behind.toVector()));
            mob.teleport(behind);
            World w = behind.getWorld();
            w.spawnParticle(Particle.SMOKE, behind.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.05);
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, behind.clone().add(0, 1, 0), 25, 0.4, 0.8, 0.4, 0.05);
            w.playSound(behind, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 1.4f);

            Bukkit.getScheduler().runTaskLater(this, () -> {  // le coup part un instant plus tard
                if (!mob.isValid() || !target.isOnline() || target.isDead()) return;
                Location eye = target.getLocation().add(0, 1, 0);
                if (eye.distance(mob.getLocation()) > 3.8) return; // le joueur a eu le temps de s'éloigner
                World w2 = eye.getWorld();
                w2.spawnParticle(Particle.SWEEP_ATTACK, eye, 3, 0.5, 0.3, 0.5, 0);
                w2.spawnParticle(Particle.DAMAGE_INDICATOR, eye, 12, 0.3, 0.5, 0.3, 0.1);
                w2.spawnParticle(Particle.CRIT, eye, 20, 0.4, 0.5, 0.4, 0.3);
                w2.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.6f);
                target.damage(10, mob);
                target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0));
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
            }, 8L);
        }, 12L);
        return true;
    }

    // ====== COMMANDES ======
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.isOp()) { // réservé aux /op
            sender.sendMessage(c("§cCette commande est réservée aux opérateurs."));
            return true;
        }
        // Raccourci : /mobegg <flame|storm|shadow> [nombre] -> t'ajoute l'oeuf à toi
        if (args.length >= 1 && Type.byId(args[0]) != null) {
            if (!(sender instanceof Player self)) { sender.sendMessage(c("§cUtilise /mobegg give <joueur> <type>.")); return true; }
            int n = 1;
            if (args.length >= 2) {
                try { n = Math.max(1, Math.min(64, Integer.parseInt(args[1]))); }
                catch (NumberFormatException ex) { sender.sendMessage(c("§cNombre invalide.")); return true; }
            }
            Type t = Type.byId(args[0]);
            self.getInventory().addItem(createEgg(t, n));
            self.sendMessage(c("§a" + n + " oeuf(s) " + t.label + " ajouté(s) à ton inventaire."));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            for (Type t : Type.values()) sender.sendMessage(c(t.color + t.id + " §7- " + t.label + " : " + t.power));
            return true;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            Type t = Type.byId(args[2]);
            if (target == null) { sender.sendMessage(c("§cJoueur introuvable.")); return true; }
            if (t == null) { sender.sendMessage(c("§cType inconnu. Fais /mobegg list")); return true; }
            int n = 1;
            if (args.length >= 4) {
                try { n = Math.max(1, Math.min(64, Integer.parseInt(args[3]))); }
                catch (NumberFormatException ex) { sender.sendMessage(c("§cNombre invalide.")); return true; }
            }
            target.getInventory().addItem(createEgg(t, n));
            sender.sendMessage(c("§a" + n + " oeuf(s) " + t.label + " donné(s) à " + target.getName() + "."));
            return true;
        }
        sender.sendMessage(c("§e/mobegg <flame|storm|shadow> [nombre] §7| §e/mobegg give <joueur> <type> [nombre] §7| §e/mobegg list"));
        return true;
    }
}
