package me.humboldt123.sky.listeners

import me.humboldt123.sky.Sky
import me.humboldt123.sky.util.pluginName
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.Consumable
import io.papermc.paper.datacomponent.item.FoodProperties
import io.papermc.paper.datacomponent.item.consumable.ConsumeEffect
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class CombatListener(private val plugin: Sky) : Listener {

    // --- Kill Rewards ---

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerKill(event: PlayerDeathEvent) {
        val killed = event.player
        val killer = killed.killer ?: return

        // Reset ALL killer's cooldowns
        plugin.cooldownManager.resetAll(killer.uniqueId)
        plugin.itemCooldownManager.cancelForPlayer(killer.uniqueId)

        // Apply resistance 255 for 2 seconds
        killer.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, 40, 255, false, false))

        // Drop head at death location
        val head = createHead(killed)
        killed.world.dropItemNaturally(killed.location, head)
    }

    @Suppress("UnstableApiUsage")
    private fun createHead(killed: Player): ItemStack {
        val head = ItemStack(Material.PLAYER_HEAD)
        val meta = head.itemMeta as SkullMeta
        meta.owningPlayer = killed
        meta.displayName(pluginName("§6${killed.name}'s Head"))
        meta.lore(listOf(pluginName("§7Consume to heal")))
        head.itemMeta = meta

        // 1.21 food component — nutrition + saturation
        head.setData(DataComponentTypes.FOOD, FoodProperties.food()
            .nutrition(6)
            .saturation(6f)
            .canAlwaysEat(true)
            .build())

        // 1.21 consumable component — eat time + potion effects
        head.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
            .consumeSeconds(0.5f) // 10 ticks
            .addEffect(ConsumeEffect.applyStatusEffects(listOf(
                PotionEffect(PotionEffectType.ABSORPTION, 2400, 0, false, true),
                PotionEffect(PotionEffectType.REGENERATION, 100, 1, false, true)
            ), 1.0f))
            .build())

        return head
    }

    // --- Shield Blocking ---

    @EventHandler(priority = EventPriority.HIGH)
    fun onShieldBlock(event: EntityDamageByEntityEvent) {
        val player = event.entity as? Player ?: return
        if (!player.isBlocking) return

        val shield = player.activeItem
        if (shield.type != Material.SHIELD) return

        // Determine slot: offhand (40) or main hand
        val slot = if (player.inventory.itemInOffHand.type == Material.SHIELD) 40
                   else player.inventory.heldItemSlot

        // Get the actual ItemStack reference from inventory (not the copy from activeItem)
        val shieldRef = player.inventory.getItem(slot) ?: return
        if (shieldRef.type != Material.SHIELD) return

        if (plugin.shieldManager.onShieldBlock(player, shieldRef, slot)) {
            event.damage = 0.0
        }
    }
}
