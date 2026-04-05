package cool.lasthope.mjolnir;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class MjolnirListener implements Listener {

    private final MjolnirPlugin plugin;

    // PDC keys
    private final NamespacedKey mjolnirKey;
    private final NamespacedKey thrownKey;

    // Track thrown Mjolnirs: projectile entity UUID -> owner UUID
    private final Map<UUID, UUID> thrownMjolnirs = new HashMap<>();

    // Cooldown for throw (per player)
    private final Set<UUID> throwCooldown = new HashSet<>();

    private static final double MELEE_DAMAGE = 4.0;  // 2 hearts = 4 hp
    private static final double THROW_DAMAGE = 2.0;  // 1 heart = 2 hp
    private static final double LIGHTNING_CHANCE = 0.20;

    public MjolnirListener(MjolnirPlugin plugin) {
        this.plugin = plugin;
        this.mjolnirKey = new NamespacedKey(plugin, "mjolnir");
        this.thrownKey = new NamespacedKey(plugin, "mjolnir_thrown");
    }

    // --- Item creation ---

    public static ItemStack createMjolnir() {
        ItemStack item = new ItemStack(Material.IRON_HOE);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(
            Component.text("Мьёльнир")
                .color(NamedTextColor.AQUA)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false)
        );

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Молот Тора").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("ПКМ - бросить").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);

        meta.addEnchant(Enchantment.UNBREAKING, 10, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);

        // Mark as Mjolnir
        meta.getPersistentDataContainer().set(
            new NamespacedKey(Bukkit.getPluginManager().getPlugin("Mjolnir"), "mjolnir"),
            PersistentDataType.BYTE,
            (byte) 1
        );

        item.setItemMeta(meta);
        return item;
    }

    private boolean isMjolnir(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
            .has(mjolnirKey, PersistentDataType.BYTE);
    }

    // --- Melee attack ---

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMeleeHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isMjolnir(held)) return;

        // Cancel original damage, apply fixed 4 hp (2 hearts) bypassing armor
        event.setCancelled(true);
        applyFixedDamage(victim, MELEE_DAMAGE, player);

        // 20% lightning chance
        if (Math.random() < LIGHTNING_CHANCE) {
            victim.getWorld().strikeLightning(victim.getLocation());
        }
    }

    private void applyFixedDamage(LivingEntity entity, double amount, Player source) {
        // Temporarily zero armor to bypass it, then restore
        double baseHealth = entity.getHealth();
        double maxHealth = entity.getAttribute(Attribute.MAX_HEALTH).getValue();

        double newHealth = Math.max(0, baseHealth - amount);
        entity.setHealth(newHealth);

        // Play damage sound and show damage effect
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        entity.getWorld().spawnParticle(Particle.CRIT, entity.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.1);

        if (newHealth <= 0) {
            entity.setHealth(0);
        }
    }

    // --- Throw mechanic ---

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
            && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isMjolnir(held)) return;

        if (throwCooldown.contains(player.getUniqueId())) return;

        event.setCancelled(true);

        // Remove from hand
        player.getInventory().setItemInMainHand(null);

        // Launch snowball as projectile (invisible - we'll use armor stand trick)
        // Using Snowball for physics, we track it and replace visuals with particles
        Snowball proj = player.launchProjectile(Snowball.class);
        proj.setVelocity(player.getLocation().getDirection().multiply(2.0));
        proj.setShooter(player);

        // Mark projectile
        proj.getPersistentDataContainer().set(thrownKey, PersistentDataType.BYTE, (byte) 1);
        thrownMjolnirs.put(proj.getUniqueId(), player.getUniqueId());

        // Particle trail task
        new BukkitRunnable() {
            @Override
            public void run() {
                if (proj.isDead() || !proj.isValid()) {
                    cancel();
                    return;
                }
                proj.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, proj.getLocation(), 5, 0.1, 0.1, 0.1, 0.05);
                proj.getWorld().spawnParticle(Particle.CRIT, proj.getLocation(), 3, 0.1, 0.1, 0.1, 0.1);
            }
        }.runTaskTimer(plugin, 0L, 1L);

        // Return to player after 3 seconds if it hasn't hit anything
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!proj.isDead() && proj.isValid()) {
                    proj.remove();
                    returnMjolnirToPlayer(player);
                    thrownMjolnirs.remove(proj.getUniqueId());
                }
            }
        }.runTaskLater(plugin, 60L);

        // Throw cooldown: 1.5 seconds
        throwCooldown.add(player.getUniqueId());
        new BukkitRunnable() {
            @Override
            public void run() {
                throwCooldown.remove(player.getUniqueId());
            }
        }.runTaskLater(plugin, 30L);
    }

    // --- Projectile hit entity ---

    @EventHandler
    public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball)) return;
        if (!snowball.getPersistentDataContainer().has(thrownKey, PersistentDataType.BYTE)) return;

        UUID ownerUUID = thrownMjolnirs.remove(snowball.getUniqueId());
        if (ownerUUID == null) return;

        Player owner = Bukkit.getPlayer(ownerUUID);

        // If hit a living entity - deal damage
        if (event.getHitEntity() instanceof LivingEntity victim && !(event.getHitEntity().getUniqueId().equals(ownerUUID))) {
            applyFixedDamage(victim, THROW_DAMAGE, owner);

            // 20% lightning on throw hit too
            if (Math.random() < LIGHTNING_CHANCE) {
                victim.getWorld().strikeLightning(victim.getLocation());
            }
        }

        // Return Mjolnir to player
        if (owner != null) {
            returnMjolnirToPlayer(owner);
        }

        snowball.remove();
    }

    private void returnMjolnirToPlayer(Player player) {
        if (!player.isOnline()) return;

        Location target = player.getLocation();
        Location start = player.getLocation(); // will be updated each tick

        // If player is close enough - give directly
        // Otherwise animate return with armorstand
        ItemStack mjolnir = createMjolnir();

        // Use a falling block or simply give after short delay with sound
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1f, 1.2f);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                // Give item
                player.getInventory().addItem(mjolnir);
                player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_HIT_GROUND, 1f, 1.5f);
                player.getWorld().spawnParticle(Particle.FLASH, player.getLocation().add(0, 1, 0), 1);
                cancel();
            }
        }.runTaskLater(plugin, 15L);
    }
}
