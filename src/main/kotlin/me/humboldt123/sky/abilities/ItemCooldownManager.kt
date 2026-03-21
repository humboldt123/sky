package me.humboldt123.sky.abilities

import me.humboldt123.sky.Sky
import me.humboldt123.sky.util.Keys
import me.humboldt123.sky.util.pluginName
import me.humboldt123.sky.util.sendActionBarMsg
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

class ItemCooldownManager(private val plugin: Sky) : Listener {

    enum class LoreStyle {
        TAG,       // Shows "[ON COOLDOWN]"
        COUNTDOWN  // Shows "Recharging: Xs"
    }

    data class TrackedCooldown(
        val id: String,
        val playerUUID: UUID,
        val abilityName: String,
        val startTimeMs: Long,
        val durationMs: Long,
        val showDurability: Boolean,
        val maxDurability: Int,
        val loreStyle: LoreStyle,
        val transformer: (ItemStack) -> ItemStack,
        val readyMessage: String? = null,
        val readyActionBar: Boolean = false,
        var completed: Boolean = false
    )

    private val tracked = mutableMapOf<String, TrackedCooldown>()

    /**
     * Start tracking an item on cooldown. Stamps PDC tag and initial lore/durability.
     * The transformer is called when the cooldown completes to produce the final item.
     * After the transformer, the manager strips the PDC tag and cooldown lore automatically.
     */
    fun startCooldown(
        item: ItemStack,
        player: Player,
        abilityName: String,
        durationTicks: Long,
        showDurability: Boolean = false,
        loreStyle: LoreStyle = LoreStyle.TAG,
        transformer: (ItemStack) -> ItemStack,
        readyMessage: String? = null,
        readyActionBar: Boolean = false
    ): String {
        val id = UUID.randomUUID().toString()

        val meta = item.itemMeta!!
        meta.persistentDataContainer.set(Keys.COOLDOWN_ID, PersistentDataType.STRING, id)

        // Add initial cooldown lore
        val lore = meta.lore()?.toMutableList() ?: mutableListOf()
        when (loreStyle) {
            LoreStyle.TAG -> lore.add(pluginName("§7[ON COOLDOWN]"))
            LoreStyle.COUNTDOWN -> {
                val seconds = (durationTicks / 20).toInt()
                lore.add(pluginName("§7Recharging: ${seconds}s"))
            }
        }
        meta.lore(lore)

        // Set initial durability (start empty, fills up over time)
        if (showDurability && meta is Damageable) {
            // Use maxDurability - 1 so the item doesn't appear "broken" at the start
            meta.damage = (item.type.maxDurability.toInt() - 1).coerceAtLeast(1)
        }

        item.itemMeta = meta

        val durationMs = durationTicks * 50L
        tracked[id] = TrackedCooldown(
            id = id,
            playerUUID = player.uniqueId,
            abilityName = abilityName,
            startTimeMs = System.currentTimeMillis(),
            durationMs = durationMs,
            showDurability = showDurability,
            maxDurability = item.type.maxDurability.toInt(),
            loreStyle = loreStyle,
            transformer = transformer,
            readyMessage = readyMessage,
            readyActionBar = readyActionBar
        )

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            completeCooldown(id)
        }, durationTicks)

        return id
    }

    /**
     * Called every second (20 ticks) to update visible cooldown items.
     */
    fun tick() {
        val now = System.currentTimeMillis()
        for ((id, data) in tracked) {
            if (data.completed) continue
            val player = Bukkit.getPlayer(data.playerUUID) ?: continue

            val elapsed = now - data.startTimeMs
            val remaining = data.durationMs - elapsed
            if (remaining <= 0) continue

            val remainingSeconds = ((remaining + 999) / 1000).toInt()
            val proportion = elapsed.toFloat() / data.durationMs.toFloat()

            updateItemInPlayerView(player, id, data, remainingSeconds, proportion)
        }
    }

    private fun updateItemInPlayerView(
        player: Player, id: String, data: TrackedCooldown,
        remainingSeconds: Int, proportion: Float
    ) {
        // Check cursor
        val cursor = player.itemOnCursor
        if (hasTrackingId(cursor, id)) {
            updateItemMeta(cursor, data, remainingSeconds, proportion)
            player.setItemOnCursor(cursor)
        }

        // Check all inventory slots (main 0-35, armor 36-39, offhand 40)
        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot) ?: continue
            if (hasTrackingId(item, id)) {
                updateItemMeta(item, data, remainingSeconds, proportion)
            }
        }

        // Check open inventory top (crafting slots, container view, etc.)
        try {
            val topInv = player.openInventory.topInventory
            for (slot in 0 until topInv.size) {
                val item = topInv.getItem(slot) ?: continue
                if (hasTrackingId(item, id)) {
                    updateItemMeta(item, data, remainingSeconds, proportion)
                }
            }
        } catch (_: Exception) {}
    }

    private fun updateItemMeta(
        item: ItemStack, data: TrackedCooldown,
        remainingSeconds: Int, proportion: Float
    ) {
        val meta = item.itemMeta ?: return
        val plain = PlainTextComponentSerializer.plainText()

        // Update lore: remove old cooldown line, add updated one
        val lore = meta.lore()?.toMutableList() ?: mutableListOf()
        lore.removeAll {
            val text = plain.serialize(it)
            text.contains("[ON COOLDOWN]") || text.contains("Recharging:")
        }
        when (data.loreStyle) {
            LoreStyle.TAG -> lore.add(pluginName("§7[ON COOLDOWN]"))
            LoreStyle.COUNTDOWN -> lore.add(pluginName("§7Recharging: ${remainingSeconds}s"))
        }
        meta.lore(lore)

        // Update durability bar (fills up from empty to full)
        if (data.showDurability && meta is Damageable) {
            val damage = ((1f - proportion) * data.maxDurability).toInt()
            meta.damage = damage.coerceIn(0, data.maxDurability - 1)
        }

        item.itemMeta = meta
    }

    private fun completeCooldown(id: String) {
        val data = tracked[id] ?: return
        data.completed = true

        val player = Bukkit.getPlayer(data.playerUUID)
        if (player == null || !player.isOnline) return

        if (transformInPlayerView(player, id, data)) {
            tracked.remove(id)
            sendReadyMessage(player, data)
            return
        }

        // Not in player view — keep pending for lazy transform on pickup/container open
    }

    private fun transformInPlayerView(player: Player, id: String, data: TrackedCooldown): Boolean {
        // Check cursor
        val cursor = player.itemOnCursor
        if (hasTrackingId(cursor, id)) {
            val transformed = data.transformer(cursor)
            finalizeTransformed(transformed, data)
            player.setItemOnCursor(ItemStack(Material.AIR))
            val leftover = player.inventory.addItem(transformed)
            if (leftover.isNotEmpty()) {
                player.world.dropItemNaturally(player.location, leftover.values.first())
            }
            return true
        }

        // Check inventory
        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot) ?: continue
            if (hasTrackingId(item, id)) {
                val transformed = data.transformer(item)
                finalizeTransformed(transformed, data)
                player.inventory.setItem(slot, transformed)
                return true
            }
        }

        // Check open inventory top (crafting, container, etc.)
        try {
            val topInv = player.openInventory.topInventory
            for (slot in 0 until topInv.size) {
                val item = topInv.getItem(slot) ?: continue
                if (hasTrackingId(item, id)) {
                    val transformed = data.transformer(item)
                    finalizeTransformed(transformed, data)
                    topInv.setItem(slot, transformed)
                    return true
                }
            }
        } catch (_: Exception) {}

        return false
    }

    /**
     * Strip tracking PDC/lore and ensure durability is fully reset after transformation.
     */
    private fun finalizeTransformed(item: ItemStack, data: TrackedCooldown) {
        stripTrackingMeta(item)
        // Explicitly reset durability to full so no residual damage remains
        if (data.showDurability) {
            val meta = item.itemMeta
            if (meta is Damageable) {
                meta.damage = 0
                item.itemMeta = meta
            }
        }
    }

    // --- Lazy transform: item was in a container or dropped when cooldown ended ---

    @EventHandler
    fun onInventoryOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        lazyTransformInInventory(player, event.inventory)
    }

    @EventHandler
    fun onItemPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val itemEntity = event.item
        val item = itemEntity.itemStack
        val id = getTrackingId(item) ?: return
        val data = tracked[id]

        if (data == null) {
            // Stale PDC tag (e.g. from before a restart) — just strip it
            stripTrackingMeta(item)
            resetDurability(item)
            itemEntity.itemStack = item
            return
        }

        if (!data.completed) return

        val transformed = data.transformer(item)
        finalizeTransformed(transformed, data)
        itemEntity.itemStack = transformed
        tracked.remove(id)
        sendReadyMessage(player, data)
    }

    private fun lazyTransformInInventory(player: Player, inventory: org.bukkit.inventory.Inventory) {
        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot) ?: continue
            val id = getTrackingId(item) ?: continue
            val data = tracked[id]

            if (data == null) {
                // Stale PDC tag — strip it
                stripTrackingMeta(item)
                resetDurability(item)
                continue
            }

            if (!data.completed) {
                // Not completed yet — update lore to reflect current state
                val now = System.currentTimeMillis()
                val elapsed = now - data.startTimeMs
                val remaining = data.durationMs - elapsed
                val remainingSeconds = ((remaining + 999) / 1000).toInt().coerceAtLeast(1)
                val proportion = elapsed.toFloat() / data.durationMs.toFloat()
                updateItemMeta(item, data, remainingSeconds, proportion)
                continue
            }

            // Completed — transform in place
            val transformed = data.transformer(item)
            finalizeTransformed(transformed, data)
            inventory.setItem(slot, transformed)
            tracked.remove(id)
            sendReadyMessage(player, data)
        }
    }

    // --- Startup / Reset Cleanup ---

    /**
     * Scan all online players' inventories for items with stale COOLDOWN_ID tags
     * (e.g. from a server restart mid-cooldown) and strip the tracking meta.
     * This makes items immediately functional again instead of being stuck.
     */
    fun cleanupStaleItems() {
        for (player in Bukkit.getOnlinePlayers()) {
            cleanupPlayerItems(player)
        }
    }

    private fun cleanupPlayerItems(player: Player) {
        // Check cursor
        val cursor = player.itemOnCursor
        if (cursor.type != Material.AIR && getTrackingId(cursor) != null) {
            stripTrackingMeta(cursor)
            resetDurability(cursor)
            player.setItemOnCursor(cursor)
        }

        // Check all inventory slots
        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot) ?: continue
            if (getTrackingId(item) != null) {
                stripTrackingMeta(item)
                resetDurability(item)
            }
        }
    }

    // --- Ready Message ---

    private fun sendReadyMessage(player: Player, data: TrackedCooldown) {
        val msg = data.readyMessage ?: "§6${data.abilityName} ready!"
        if (data.readyActionBar) {
            player.sendActionBarMsg(msg)
        } else {
            player.sendMessage(msg)
        }
    }

    // --- Utility ---

    private fun hasTrackingId(item: ItemStack, id: String): Boolean {
        if (item.type == Material.AIR) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.get(Keys.COOLDOWN_ID, PersistentDataType.STRING) == id
    }

    private fun getTrackingId(item: ItemStack): String? {
        if (item.type == Material.AIR) return null
        val meta = item.itemMeta ?: return null
        return meta.persistentDataContainer.get(Keys.COOLDOWN_ID, PersistentDataType.STRING)
    }

    private fun stripTrackingMeta(item: ItemStack) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.remove(Keys.COOLDOWN_ID)
        val plain = PlainTextComponentSerializer.plainText()
        val lore = meta.lore()?.toMutableList()
        if (lore != null) {
            lore.removeAll {
                val text = plain.serialize(it)
                text.contains("[ON COOLDOWN]") || text.contains("Recharging:")
            }
            meta.lore(if (lore.isEmpty()) null else lore)
        }
        item.itemMeta = meta
    }

    private fun resetDurability(item: ItemStack) {
        val meta = item.itemMeta
        if (meta is Damageable && meta.damage > 0) {
            meta.damage = 0
            item.itemMeta = meta
        }
    }

    fun cancelForPlayer(playerUUID: UUID) {
        tracked.entries.removeAll { it.value.playerUUID == playerUUID }
    }

    fun clearAll() {
        tracked.clear()
        // Strip stale PDC tags from all online players so items are immediately functional
        cleanupStaleItems()
    }
}
