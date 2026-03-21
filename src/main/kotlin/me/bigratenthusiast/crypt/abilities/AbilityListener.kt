package me.bigratenthusiast.crypt.abilities

import me.bigratenthusiast.crypt.Crypt
import me.bigratenthusiast.crypt.util.hasPluginName
import me.bigratenthusiast.crypt.util.pluginName
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class AbilityListener(private val plugin: Crypt) : Listener {

    companion object {
        // Cooldowns in ticks // TODO: TEST all values
        const val WINGED_BOOTS_COOLDOWN = 600L   // 30 seconds
        const val WINGED_BOOTS_DURATION = 100     // 5 seconds
        const val WINGED_BOOTS_LEVEL = 5

        const val GRAPPLE_COOLDOWN = 60L         // 5 seconds
        const val GRAPPLE_VELOCITY = 3.5         // multiplier

        const val TOTEM_RECHARGE_SECONDS = 45
    }

    // --- Winged Boots ---

    @EventHandler
    fun onWingedBootsUse(event: PlayerInteractEvent) {
        if (!event.action.isRightClick) return
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        if (!player.isSneaking) return
        if (player.gameMode != GameMode.SURVIVAL) return

        val boots = player.inventory.boots ?: return
        if (boots.type != Material.GOLDEN_BOOTS) return
        if (!boots.hasPluginName("Winged Boots")) return

        if (plugin.cooldownManager.isOnCooldown(player.uniqueId, "winged_boots")) {
            player.sendMessage("§cPlease wait to use that ability!")
            return
        }

        player.addPotionEffect(PotionEffect(
            PotionEffectType.JUMP_BOOST, WINGED_BOOTS_DURATION, WINGED_BOOTS_LEVEL - 1, false, false, true
        ))

        // Sound effect on activation
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f)

        plugin.cooldownManager.setCooldown(player.uniqueId, "winged_boots", WINGED_BOOTS_COOLDOWN)

        // Swap golden boots to copper boots for cooldown visual
        val meta = boots.itemMeta ?: return
        val cooldownBoots = ItemStack(Material.COPPER_BOOTS)
        cooldownBoots.itemMeta = meta

        // Track with ItemCooldownManager BEFORE setting in inventory (setter copies the item)
        plugin.itemCooldownManager.startCooldown(
            item = cooldownBoots,
            player = player,
            abilityName = "Winged Boots",
            durationTicks = WINGED_BOOTS_COOLDOWN,
            showDurability = true,
            loreStyle = ItemCooldownManager.LoreStyle.TAG,
            transformer = { item ->
                // Swap back to golden boots with clean durability
                val m = item.itemMeta
                if (m is Damageable) m.damage = 0
                val goldBoots = ItemStack(Material.GOLDEN_BOOTS)
                goldBoots.itemMeta = m
                goldBoots
            }
        )

        player.inventory.boots = cooldownBoots
    }

    // Only checks WORN boots — must be in boots armor slot
    private fun isWearingWingedBoots(player: Player): Boolean {
        val boots = player.inventory.boots ?: return false
        return (boots.type == Material.GOLDEN_BOOTS || boots.type == Material.COPPER_BOOTS)
            && boots.hasPluginName("Winged Boots")
    }

    // --- Grappling Hook ---

    @EventHandler
    fun onGrappleUse(event: PlayerFishEvent) {
        val player = event.player
        if (player.gameMode == GameMode.SPECTATOR) return

        val rod = player.inventory.itemInMainHand
        if (rod.type != Material.FISHING_ROD) return
        if (!rod.hasPluginName("Grappling Hook")) return

        if (event.state != PlayerFishEvent.State.REEL_IN &&
            event.state != PlayerFishEvent.State.IN_GROUND) return

        val hook = event.hook ?: return

        if (plugin.cooldownManager.isOnCooldown(player.uniqueId, "grappling_hook")) {
            player.sendMessage("§cYou cannot use that ability yet!")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 100.0f, 1.0f)
            return
        }

        // Calculate velocity toward hook location // TODO: TEST
        val hookLoc = hook.location
        val playerLoc = player.location
        val direction = hookLoc.toVector().subtract(playerLoc.toVector())

        if (direction.lengthSquared() < 9.0) return // less than 3 blocks, ignore

        val velocity = direction.normalize().multiply(GRAPPLE_VELOCITY)
        player.velocity = velocity

        // Sound effect on use
        player.playSound(player.location, Sound.ENTITY_SPIDER_AMBIENT, 100.0f, 1.0f)

        plugin.cooldownManager.setCooldown(player.uniqueId, "grappling_hook", GRAPPLE_COOLDOWN)

        // Track with ItemCooldownManager — handles lore, durability bar, cursor/container tracking
        plugin.itemCooldownManager.startCooldown(
            item = rod,
            player = player,
            abilityName = "Grappling Hook",
            durationTicks = GRAPPLE_COOLDOWN,
            showDurability = true,
            loreStyle = ItemCooldownManager.LoreStyle.TAG,
            transformer = { it } // same item, just strip tracking meta
        )
    }

    // --- Hollow Totem ---

    @EventHandler
    fun onTotemPop(event: EntityResurrectEvent) {
        val player = event.entity as? Player ?: return

        // Only fires if the totem actually saved them (not cancelled = totem existed)
        if (event.isCancelled) return

        // Only recharge if WEARING Winged Boots (must be in boots armor slot)
        if (!isWearingWingedBoots(player)) return

        // After 1 tick, replace the consumed totem with a recharging firework star
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable

            val rechargingTotem = ItemStack(Material.FIREWORK_STAR)
            val meta = rechargingTotem.itemMeta!!
            meta.displayName(pluginName("§eHollow Totem"))
            rechargingTotem.itemMeta = meta

            // Track with ItemCooldownManager BEFORE setting in inventory (setter copies the item)
            plugin.itemCooldownManager.startCooldown(
                item = rechargingTotem,
                player = player,
                abilityName = "Hollow Totem",
                durationTicks = TOTEM_RECHARGE_SECONDS * 20L,
                showDurability = false,
                loreStyle = ItemCooldownManager.LoreStyle.COUNTDOWN,
                transformer = { _ -> ItemStack(Material.TOTEM_OF_UNDYING) },
                readyMessage = "§eTotem Recharged.",
                readyActionBar = true
            )

            player.inventory.setItemInOffHand(rechargingTotem)
        }, 1L)
    }

    fun resetAll() {
        // ItemCooldownManager handles its own cleanup via clearAll()
    }
}
