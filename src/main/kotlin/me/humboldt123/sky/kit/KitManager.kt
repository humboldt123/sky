package me.humboldt123.sky.kit

import me.humboldt123.sky.Sky
import me.humboldt123.sky.util.pluginName
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.block.ShulkerBox
import java.util.Base64
import java.util.UUID

class KitManager(private val plugin: Sky) : Listener {

    data class KitData(
        val items: MutableMap<Int, String>, // slot index -> base64 serialized ItemStack
        var shulkerColor: String = "PURPLE" // shulker box color for /kits display
    )

    val kits = mutableMapOf<String, KitData>()

    // Track which non-op players have used /kits this round
    private val kitsUsedThisRound = mutableSetOf<UUID>()

    fun hasUsedKitsThisRound(player: Player): Boolean = player.uniqueId in kitsUsedThisRound
    fun markKitUsed(player: Player) { kitsUsedThisRound.add(player.uniqueId) }
    fun resetKitUsage() { kitsUsedThisRound.clear() }
    fun giveKitUse(player: Player) { kitsUsedThisRound.remove(player.uniqueId) }

    // Inventory holders for GUI identification
    class KitSelectHolder : InventoryHolder {
        override fun getInventory(): Inventory = throw UnsupportedOperationException()
    }

    class KitEditHolder(val kitName: String) : InventoryHolder {
        override fun getInventory(): Inventory = throw UnsupportedOperationException()
    }

    // --- Config I/O ---

    fun load() {
        kits.clear()
        val sec = plugin.config.getConfigurationSection("kits") ?: return
        for (name in sec.getKeys(false)) {
            val kitSec = sec.getConfigurationSection(name) ?: continue
            val items = mutableMapOf<Int, String>()
            kitSec.getConfigurationSection("items")?.let { itemsSec ->
                for (key in itemsSec.getKeys(false)) {
                    val slot = key.toIntOrNull() ?: continue
                    items[slot] = itemsSec.getString(key) ?: return@let
                }
            }
            val color = kitSec.getString("shulkerColor", "PURPLE") ?: "PURPLE"
            kits[name] = KitData(items, color)
        }
    }

    fun save() {
        plugin.config.set("kits", null)
        for ((name, data) in kits) {
            for ((slot, b64) in data.items) {
                plugin.config.set("kits.$name.items.$slot", b64)
            }
            plugin.config.set("kits.$name.shulkerColor", data.shulkerColor)
        }
        plugin.saveConfig()
    }

    // --- Kit Application ---

    fun applyKit(player: Player, kitName: String): Boolean {
        val kit = kits[kitName] ?: return false
        player.inventory.clear()

        for ((slot, b64) in kit.items) {
            try {
                val bytes = Base64.getDecoder().decode(b64)
                val item = ItemStack.deserializeBytes(bytes)
                player.inventory.setItem(slot, item)
            } catch (e: Exception) {
                plugin.logger.warning("Failed to deserialize kit '$kitName' slot $slot: ${e.message}")
            }
        }

        return true
    }

    // --- Kit Selection GUI ---

    fun openKitGui(player: Player) {
        val kitNames = kits.keys.toList()
        if (kitNames.isEmpty()) {
            player.sendMessage("§cNo kits are configured!")
            return
        }

        val rows = ((kitNames.size + 8) / 9).coerceIn(1, 6)
        val inv = Bukkit.createInventory(
            KitSelectHolder(), rows * 9,
            Component.text("Select Kit")
        )

        for ((index, name) in kitNames.withIndex()) {
            if (index >= rows * 9) break
            val kit = kits[name] ?: continue
            inv.setItem(index, createDisplayItem(kit, name))
        }

        player.openInventory(inv)
    }

    private fun getShulkerMaterial(color: String): Material {
        return try {
            Material.valueOf("${color.uppercase()}_SHULKER_BOX")
        } catch (_: Exception) {
            Material.PURPLE_SHULKER_BOX
        }
    }

    private fun createDisplayItem(kit: KitData, kitName: String): ItemStack {
        val shulkerMat = getShulkerMaterial(kit.shulkerColor)
        val display = ItemStack(shulkerMat)
        val meta = display.itemMeta as? BlockStateMeta

        if (meta != null) {
            meta.displayName(pluginName("§6${kitName.replaceFirstChar { it.uppercase() }}"))
            meta.lore(listOf(pluginName("§eClick to select")))

            // Fill shulker box inventory with kit items for hover preview
            val blockState = meta.blockState as? ShulkerBox
            if (blockState != null) {
                val shulkerInv = blockState.inventory
                var slotIndex = 0
                val maxSlots = 27 // shulker box has 27 slots
                for ((_, b64) in kit.items) {
                    if (slotIndex >= maxSlots) break
                    try {
                        val bytes = Base64.getDecoder().decode(b64)
                        val item = ItemStack.deserializeBytes(bytes)
                        shulkerInv.setItem(slotIndex, item)
                        slotIndex++
                    } catch (_: Exception) {}
                }
                meta.blockState = blockState
            }

            display.itemMeta = meta
        } else {
            // Fallback if BlockStateMeta not available
            val dm = display.itemMeta!!
            dm.displayName(pluginName("§6${kitName.replaceFirstChar { it.uppercase() }}"))
            dm.lore(listOf(pluginName("§eClick to select")))
            display.itemMeta = dm
        }

        return display
    }

    // --- Kit Edit GUI (admin) ---
    // Layout (54-slot double chest):
    //   Row 0-2 (GUI 0-26): player inventory rows (player slots 9-35)
    //   Row 3   (GUI 27-35): hotbar (player slots 0-8)
    //   Row 4   (GUI 36-44): black glass pane separator
    //   Row 5   (GUI 45-53): [glass][offhand][glass][glass][helm][chest][legs][boots][glass]

    companion object {
        // GUI slot -> player inventory slot mapping
        private val GUI_TO_PLAYER = mutableMapOf<Int, Int>().apply {
            // Rows 0-2: GUI 0-26 → player inv 9-35
            for (i in 0..26) this[i] = i + 9
            // Row 3: GUI 27-35 → player hotbar 0-8
            for (i in 0..8) this[27 + i] = i
            // Row 5 equipment
            this[46] = 40 // offhand
            this[49] = 39 // helmet
            this[50] = 38 // chestplate
            this[51] = 37 // leggings
            this[52] = 36 // boots
        }
        // GUI slots that are glass panes (immovable)
        private val GLASS_SLOTS = setOf(36,37,38,39,40,41,42,43,44, 45,47,48,53)
    }

    private fun createGlassPane(): ItemStack {
        val pane = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        val meta = pane.itemMeta!!
        meta.displayName(pluginName(" "))
        pane.itemMeta = meta
        return pane
    }

    fun openEditGui(player: Player, kitName: String) {
        kits.getOrPut(kitName) { KitData(mutableMapOf()) }
        val kit = kits[kitName]!!

        val inv = Bukkit.createInventory(
            KitEditHolder(kitName), 54,
            Component.text("Edit Kit: $kitName", NamedTextColor.GOLD)
        )

        // Place glass panes
        val pane = createGlassPane()
        for (slot in GLASS_SLOTS) inv.setItem(slot, pane)

        // Place existing kit items via mapping
        for ((playerSlot, b64) in kit.items) {
            val guiSlot = GUI_TO_PLAYER.entries.firstOrNull { it.value == playerSlot }?.key ?: continue
            try {
                inv.setItem(guiSlot, ItemStack.deserializeBytes(Base64.getDecoder().decode(b64)))
            } catch (e: Exception) {
                plugin.logger.warning("Kit edit GUI deserialize failed slot $playerSlot: ${e.message}")
            }
        }

        player.openInventory(inv)
    }

    // --- GUI Event Handlers ---

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder
        if (holder is KitSelectHolder) {
            handleKitSelect(event)
        } else if (holder is KitEditHolder) {
            // Prevent moving glass panes
            val slot = event.rawSlot
            if (slot in GLASS_SLOTS) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? KitEditHolder ?: return
        val player = event.player as? Player ?: return
        val kitName = holder.kitName
        val topInv = event.inventory

        val kit = kits.getOrPut(kitName) { KitData(mutableMapOf()) }
        kit.items.clear()

        // Read items from GUI using slot mapping
        for ((guiSlot, playerSlot) in GUI_TO_PLAYER) {
            val item = topInv.getItem(guiSlot) ?: continue
            if (item.type == Material.AIR) continue
            try {
                kit.items[playerSlot] = Base64.getEncoder().encodeToString(item.serializeAsBytes())
            } catch (e: Exception) {
                plugin.logger.warning("Kit save serialize failed slot $playerSlot: ${e.message}")
            }
        }

        save()
        player.sendMessage("§a[Sky] Kit '$kitName' saved! (${kit.items.size} items)")
    }

    private fun handleKitSelect(event: InventoryClickEvent) {
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val slot = event.rawSlot
        if (slot < 0 || slot >= event.inventory.size) return
        if (event.inventory.getItem(slot) == null) return

        val kitNames = kits.keys.toList()
        if (slot >= kitNames.size) return

        val kitName = kitNames[slot]
        player.closeInventory()

        if (applyKit(player, kitName)) {
            markKitUsed(player)
            player.sendMessage("§a[Sky] Kit §6$kitName §aapplied!")
        } else {
            player.sendMessage("§cFailed to apply kit '$kitName'.")
        }
    }
}
