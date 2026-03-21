package me.humboldt123.sky.abilities

import me.humboldt123.sky.Sky
import me.humboldt123.sky.util.Keys
import me.humboldt123.sky.util.pluginName
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.persistence.PersistentDataType

class ShieldManager(private val plugin: Sky) {

    data class ShieldTier(val uses: Int, val recharges: Boolean)

    companion object {
        const val SHIELD_MAX_DURABILITY = 336
        const val DEFAULT_RECHARGE_SECONDS = 20

        val SHIELD_TIERS = mapOf(
            "Default Shield" to ShieldTier(1, false),       // breaks, no recharge
            "Rusty Shield" to ShieldTier(1, true),          // 1 hit, recharges
            "Wooden Shield" to ShieldTier(2, true),         // 2 hits, recharges
            "Traveler's Shield" to ShieldTier(3, true),     // 3 hits, recharges
            "Sturdy Shield" to ShieldTier(4, true),         // 4 hits, recharges
            "Reinforced Shield" to ShieldTier(10, true),    // 10 hits, recharges
        )
    }

    /**
     * Called when a player blocks with a shield. Reads/initializes PDC uses,
     * decrements, and breaks when depleted.
     *
     * @param shieldItem the actual ItemStack reference from the player's inventory
     * @param shieldSlot the inventory slot the shield is in
     */
    fun onShieldBlock(player: Player, shieldItem: ItemStack, shieldSlot: Int): Boolean {
        val tier = getTier(shieldItem)
        val maxUses = tier?.uses ?: getDefaultUses(shieldItem)
        val recharges = tier?.recharges ?: (getDisplayName(shieldItem) != null)

        val meta = shieldItem.itemMeta as? Damageable ?: return false
        val pdc = meta.persistentDataContainer

        // Read remaining from PDC, or initialize on first block
        var remaining = if (pdc.has(Keys.SHIELD_REMAINING))
            pdc.get(Keys.SHIELD_REMAINING, PersistentDataType.INTEGER)!!
        else
            maxUses

        remaining--

        if (remaining <= 0) {
            // Clone the shield BEFORE breaking so we can restore it later
            val originalClone = shieldItem.clone()
            val cloneMeta = originalClone.itemMeta as? Damageable
            if (cloneMeta != null) {
                cloneMeta.damage = 0
                cloneMeta.persistentDataContainer.remove(Keys.SHIELD_REMAINING)
                cloneMeta.persistentDataContainer.remove(Keys.SHIELD_MAX_USES)
                originalClone.itemMeta = cloneMeta
            }

            breakShield(player, shieldSlot, maxUses, recharges, originalClone)
            return true
        }

        // Update PDC and durability bar
        pdc.set(Keys.SHIELD_REMAINING, PersistentDataType.INTEGER, remaining)
        pdc.set(Keys.SHIELD_MAX_USES, PersistentDataType.INTEGER, maxUses)
        val proportion = remaining.toFloat() / maxUses.toFloat()
        meta.damage = ((1f - proportion) * SHIELD_MAX_DURABILITY).toInt()
        shieldItem.itemMeta = meta

        return true
    }

    private fun breakShield(player: Player, shieldSlot: Int, maxUses: Int, recharges: Boolean, originalClone: ItemStack) {
        val brokenShield = ItemStack(Material.ARMADILLO_SCUTE)
        val meta = brokenShield.itemMeta!!
        meta.displayName(pluginName("§7Broken Shield"))
        brokenShield.itemMeta = meta

        if (!recharges) {
            player.inventory.setItem(shieldSlot, brokenShield)
            return
        }

        // Track with ItemCooldownManager BEFORE setting in inventory (setter copies the item)
        plugin.itemCooldownManager.startCooldown(
            item = brokenShield,
            player = player,
            abilityName = "Shield",
            durationTicks = DEFAULT_RECHARGE_SECONDS * 20L,
            showDurability = false,
            loreStyle = ItemCooldownManager.LoreStyle.COUNTDOWN,
            transformer = { _ -> originalClone }
        )

        player.inventory.setItem(shieldSlot, brokenShield)
    }

    private fun getTier(item: ItemStack): ShieldTier? {
        val name = getDisplayName(item) ?: return null
        return SHIELD_TIERS.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private fun getDisplayName(item: ItemStack): String? {
        val meta = item.itemMeta ?: return null
        if (!meta.hasDisplayName()) return null
        return PlainTextComponentSerializer.plainText().serialize(meta.displayName()!!)
    }

    private fun getDefaultUses(item: ItemStack): Int {
        // Named shield not in tier list → 3 uses; unnamed/crafted → 1 use
        return if (getDisplayName(item) != null) 3 else 1
    }

    fun clearAll() {
        // No per-player state — PDC lives on items
    }

    fun clearPlayer(playerId: java.util.UUID) {
        // No per-player state — PDC lives on items
    }
}
