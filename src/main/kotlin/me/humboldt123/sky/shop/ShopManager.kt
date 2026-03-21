package me.humboldt123.sky.shop

import me.humboldt123.sky.Sky
import me.humboldt123.sky.util.locationFromConfigString
import me.humboldt123.sky.util.pluginName
import me.humboldt123.sky.util.sendActionBarMsg
import me.humboldt123.sky.util.toConfigString
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantRecipe
import java.util.Base64
import java.util.UUID

class ShopManager(private val plugin: Sky) : Listener {

    data class ShopItem(
        val item: String,   // base64 encoded result ItemStack
        val costA: String,  // base64 encoded cost A ItemStack
        val costB: String?  // base64 encoded cost B ItemStack (optional)
    )

    data class ShopData(
        var villagerUUID: UUID?,
        var location: org.bukkit.Location,
        var profession: Villager.Profession,
        var biome: Villager.Type,
        var level: Int,
        val activeSwitches: MutableList<String>,
        val items: MutableList<ShopItem>
    )

    val shops = mutableMapOf<String, ShopData>()

    // Edit session state per player
    private val editSessions = mutableMapOf<UUID, EditSession>()

    data class EditSession(
        val shopName: String,
        var profession: Villager.Profession,
        var biome: Villager.Type,
        var level: Int
    )

    // Inventory holders
    class ShopEditHolder(val shopName: String) : InventoryHolder {
        override fun getInventory(): Inventory = throw UnsupportedOperationException()
    }

    companion object {
        val PROFESSIONS = listOf(
            Villager.Profession.ARMORER, Villager.Profession.BUTCHER,
            Villager.Profession.CARTOGRAPHER, Villager.Profession.CLERIC,
            Villager.Profession.FARMER, Villager.Profession.FISHERMAN,
            Villager.Profession.FLETCHER, Villager.Profession.LEATHERWORKER,
            Villager.Profession.LIBRARIAN, Villager.Profession.MASON,
            Villager.Profession.SHEPHERD, Villager.Profession.TOOLSMITH,
            Villager.Profession.WEAPONSMITH
        )
        val BIOMES = listOf(
            Villager.Type.DESERT, Villager.Type.JUNGLE, Villager.Type.PLAINS,
            Villager.Type.SAVANNA, Villager.Type.SNOW, Villager.Type.SWAMP,
            Villager.Type.TAIGA
        )

        // Edit GUI layout:
        //   Row 0 (0-8):   Items for sale (result)
        //   Row 1 (9-17):  Cost A
        //   Row 2 (18-26): Cost B (optional)
        //   Row 3 (27-35): Glass panes
        //   Row 4 (36-44): Glass panes
        //   Row 5 (45-53): Glass panes with centered buttons at 48,49,50
        const val PROFESSION_SLOT = 48
        const val BIOME_SLOT = 49
        const val LEVEL_SLOT = 50
        val CONFIG_SLOTS = setOf(PROFESSION_SLOT, BIOME_SLOT, LEVEL_SLOT)
        val GLASS_SLOTS = (27..53).toSet() - CONFIG_SLOTS
    }

    // --- Config I/O ---

    fun load() {
        shops.clear()
        val sec = plugin.config.getConfigurationSection("shops") ?: return
        for (name in sec.getKeys(false)) {
            val shopSec = sec.getConfigurationSection(name) ?: continue
            val uuidStr = shopSec.getString("villagerUUID")
            val uuid = if (uuidStr != null) {
                try { UUID.fromString(uuidStr) } catch (_: Exception) { null }
            } else null
            val loc = locationFromConfigString(shopSec.getString("location") ?: continue) ?: continue
            val profStr = (shopSec.getString("profession") ?: "WEAPONSMITH").lowercase()
            val prof = PROFESSIONS.firstOrNull { it.key().value() == profStr }
                ?: Villager.Profession.WEAPONSMITH
            val biomeStr = (shopSec.getString("biome") ?: "PLAINS").lowercase()
            val biome = BIOMES.firstOrNull { it.key().value() == biomeStr }
                ?: Villager.Type.PLAINS
            val level = shopSec.getInt("level", 1).coerceIn(1, 5)
            val switches = shopSec.getStringList("activeSwitches").toMutableList()

            val items = mutableListOf<ShopItem>()
            val itemsSec = shopSec.getConfigurationSection("items")
            val costASec = shopSec.getConfigurationSection("costA")
            val costBSec = shopSec.getConfigurationSection("costB")
            if (itemsSec != null) {
                for (key in itemsSec.getKeys(false).sortedBy { it.toIntOrNull() ?: 0 }) {
                    val b64 = itemsSec.getString(key) ?: continue
                    val costA = costASec?.getString(key) ?: continue
                    val costB = costBSec?.getString(key)
                    items.add(ShopItem(b64, costA, costB))
                }
            }

            shops[name] = ShopData(uuid, loc, prof, biome, level, switches, items)
        }
    }

    fun save() {
        plugin.config.set("shops", null)
        for ((name, data) in shops) {
            val prefix = "shops.$name"
            plugin.config.set("$prefix.villagerUUID", data.villagerUUID?.toString())
            plugin.config.set("$prefix.location", data.location.toConfigString())
            plugin.config.set("$prefix.profession", data.profession.key().value().uppercase())
            plugin.config.set("$prefix.biome", data.biome.key().value().uppercase())
            plugin.config.set("$prefix.level", data.level)
            plugin.config.set("$prefix.activeSwitches", data.activeSwitches)
            for ((i, item) in data.items.withIndex()) {
                plugin.config.set("$prefix.items.$i", item.item)
                plugin.config.set("$prefix.costA.$i", item.costA)
                if (item.costB != null) {
                    plugin.config.set("$prefix.costB.$i", item.costB)
                }
            }
        }
        plugin.saveConfig()
    }

    // --- Villager Management ---

    fun initVillagers() {
        for ((name, data) in shops) {
            val uuid = data.villagerUUID ?: continue
            val villager = findVillager(uuid)
            if (villager == null) {
                plugin.logger.warning("Shop '$name': Villager with UUID $uuid not found!")
                continue
            }
            applyVillagerConfig(villager, data)
        }
    }

    fun findVillager(uuid: UUID): Villager? {
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities) {
                if (entity is Villager && entity.uniqueId == uuid) return entity
            }
        }
        return null
    }

    fun applyVillagerConfig(villager: Villager, data: ShopData) {
        villager.profession = data.profession
        villager.villagerType = data.biome
        villager.villagerLevel = data.level
        villager.isInvulnerable = true
        villager.setGravity(false)
        villager.setAI(false)
        villager.isSilent = true
        villager.isPersistent = true
    }

    fun findNearestVillager(player: Player, radius: Double = 10.0): Villager? {
        return player.location.world?.getNearbyEntities(player.location, radius, radius, radius)
            ?.filterIsInstance<Villager>()
            ?.minByOrNull { it.location.distance(player.location) }
    }

    fun findShopByVillager(uuid: UUID): String? {
        for ((name, data) in shops) {
            if (data.villagerUUID == uuid) return name
        }
        return null
    }

    fun isActive(shopName: String): Boolean {
        val data = shops[shopName] ?: return false
        return plugin.interactableManager.isActive(data.activeSwitches)
    }

    fun findSwitchUsages(switchName: String): List<String> {
        val usages = mutableListOf<String>()
        for ((name, data) in shops) {
            if (switchName in data.activeSwitches) usages.add("shop:$name")
        }
        return usages
    }

    // --- Player Buy GUI (Merchant Trading Interface) ---

    fun openBuyGui(player: Player, shopName: String) {
        val data = shops[shopName] ?: return
        if (data.items.isEmpty()) {
            player.sendMessage("§cThis shop has no items for sale!")
            return
        }

        val merchant = Bukkit.createMerchant(
            Component.text("${shopName.replaceFirstChar { it.uppercase() }}")
        )

        val recipes = mutableListOf<MerchantRecipe>()
        for (shopItem in data.items) {
            try {
                val result = ItemStack.deserializeBytes(Base64.getDecoder().decode(shopItem.item))
                val costA = ItemStack.deserializeBytes(Base64.getDecoder().decode(shopItem.costA))
                val costB = if (shopItem.costB != null) {
                    ItemStack.deserializeBytes(Base64.getDecoder().decode(shopItem.costB))
                } else null

                val recipe = MerchantRecipe(result, Int.MAX_VALUE) // unlimited uses
                recipe.addIngredient(costA)
                if (costB != null) recipe.addIngredient(costB)
                recipes.add(recipe)
            } catch (e: Exception) {
                plugin.logger.warning("Shop '$shopName' trade build failed: ${e.message}")
            }
        }

        merchant.recipes = recipes
        player.openMerchant(merchant, true)
    }

    // --- Admin Edit GUI ---

    private fun createGlassPane(): ItemStack {
        val pane = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        val meta = pane.itemMeta!!
        meta.displayName(pluginName(" "))
        pane.itemMeta = meta
        return pane
    }

    fun openEditGui(player: Player, shopName: String) {
        val data = shops[shopName] ?: run {
            player.sendMessage("§cShop '$shopName' not found!")
            return
        }

        val inv = Bukkit.createInventory(
            ShopEditHolder(shopName), 54,
            Component.text("Edit Shop: $shopName", NamedTextColor.GOLD)
        )

        // Place glass panes
        val pane = createGlassPane()
        for (slot in GLASS_SLOTS) inv.setItem(slot, pane)

        // Place existing items
        for ((i, shopItem) in data.items.withIndex()) {
            if (i > 8) break
            try {
                inv.setItem(i, ItemStack.deserializeBytes(Base64.getDecoder().decode(shopItem.item)))
                inv.setItem(9 + i, ItemStack.deserializeBytes(Base64.getDecoder().decode(shopItem.costA)))
                if (shopItem.costB != null) {
                    inv.setItem(18 + i, ItemStack.deserializeBytes(Base64.getDecoder().decode(shopItem.costB)))
                }
            } catch (_: Exception) {}
        }

        // Config buttons (centered: slots 48, 49, 50)
        inv.setItem(PROFESSION_SLOT, createProfessionButton(data.profession))
        inv.setItem(BIOME_SLOT, createBiomeButton(data.biome))
        inv.setItem(LEVEL_SLOT, createLevelButton(data.level))

        editSessions[player.uniqueId] = EditSession(shopName, data.profession, data.biome, data.level)
        player.openInventory(inv)
    }

    private fun createProfessionButton(profession: Villager.Profession): ItemStack {
        val item = ItemStack(Material.CRAFTING_TABLE)
        val meta = item.itemMeta!!
        meta.displayName(pluginName("§6Profession: ${profession.key().value().uppercase()}"))
        meta.lore(listOf(pluginName("§eClick to cycle")))
        item.itemMeta = meta
        return item
    }

    private fun createBiomeButton(biome: Villager.Type): ItemStack {
        val item = ItemStack(Material.GRASS_BLOCK)
        val meta = item.itemMeta!!
        meta.displayName(pluginName("§6Biome: ${biome.key().value().uppercase()}"))
        meta.lore(listOf(pluginName("§eClick to cycle")))
        item.itemMeta = meta
        return item
    }

    private fun createLevelButton(level: Int): ItemStack {
        val item = ItemStack(Material.EXPERIENCE_BOTTLE, level)
        val meta = item.itemMeta!!
        meta.displayName(pluginName("§6Level: $level"))
        meta.lore(listOf(pluginName("§eClick to cycle (1-5)")))
        item.itemMeta = meta
        return item
    }

    // --- Auto-save on edit GUI close ---

    @EventHandler
    fun onEditGuiClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? ShopEditHolder ?: return
        val player = event.player as? Player ?: return
        val shopName = holder.shopName
        val topInv = event.inventory

        val data = shops[shopName] ?: return
        val session = editSessions.remove(player.uniqueId)

        // Apply villager config from edit session
        if (session != null) {
            data.profession = session.profession
            data.biome = session.biome
            data.level = session.level
        }

        // Collect items from rows 0-2
        data.items.clear()
        for (col in 0..8) {
            val resultItem = topInv.getItem(col)
            if (resultItem == null || resultItem.type == Material.AIR) continue

            val costAItem = topInv.getItem(9 + col)
            if (costAItem == null || costAItem.type == Material.AIR) continue

            val costBItem = topInv.getItem(18 + col)
            val hasCostB = costBItem != null && costBItem.type != Material.AIR

            try {
                val itemB64 = Base64.getEncoder().encodeToString(resultItem.serializeAsBytes())
                val costAB64 = Base64.getEncoder().encodeToString(costAItem.serializeAsBytes())
                val costBB64 = if (hasCostB) {
                    Base64.getEncoder().encodeToString(costBItem!!.serializeAsBytes())
                } else null

                data.items.add(ShopItem(itemB64, costAB64, costBB64))
            } catch (e: Exception) {
                plugin.logger.warning("Shop save serialize failed col $col: ${e.message}")
            }
        }

        // Apply config to actual villager entity
        val villager = data.villagerUUID?.let { findVillager(it) }
        if (villager != null) applyVillagerConfig(villager, data)

        save()
        player.sendMessage("§a[Sky] Shop '$shopName' saved! (${data.items.size} trades)")
    }

    // --- Villager Interaction ---

    @EventHandler
    fun onVillagerInteract(event: PlayerInteractEntityEvent) {
        val entity = event.rightClicked as? Villager ?: return
        val shopName = findShopByVillager(entity.uniqueId) ?: return

        event.isCancelled = true

        val player = event.player
        if (!isActive(shopName)) {
            val data = shops[shopName]
            val switchNames = data?.activeSwitches?.joinToString("§f, §6") ?: "a switch"
            player.sendActionBarMsg("§fCome back when §6$switchNames §fis active")
            // Villager shakes head and says "hmm"
            entity.shakeHead()
            player.playSound(entity.location, Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
            return
        }

        openBuyGui(player, shopName)
    }

    // --- Prevent all damage to shop villagers ---

    @EventHandler
    fun onShopVillagerDamage(event: EntityDamageEvent) {
        val entity = event.entity as? Villager ?: return
        if (findShopByVillager(entity.uniqueId) != null) {
            event.isCancelled = true
        }
    }

    // --- Edit Click Handling ---

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder
        if (holder is ShopEditHolder) handleEditClick(event)
    }

    private fun handleEditClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val slot = event.rawSlot

        // Block glass pane slots
        if (slot in GLASS_SLOTS) {
            event.isCancelled = true
            return
        }

        // Handle config button clicks
        if (slot !in CONFIG_SLOTS) return

        event.isCancelled = true
        val session = editSessions[player.uniqueId] ?: return

        when (slot) {
            PROFESSION_SLOT -> {
                val idx = PROFESSIONS.indexOf(session.profession)
                session.profession = PROFESSIONS[(idx + 1) % PROFESSIONS.size]
                event.inventory.setItem(slot, createProfessionButton(session.profession))
            }
            BIOME_SLOT -> {
                val idx = BIOMES.indexOf(session.biome)
                session.biome = BIOMES[(idx + 1) % BIOMES.size]
                event.inventory.setItem(slot, createBiomeButton(session.biome))
            }
            LEVEL_SLOT -> {
                session.level = (session.level % 5) + 1
                event.inventory.setItem(slot, createLevelButton(session.level))
            }
        }
    }

    // --- Particle Ticking ---

    fun tickParticles() {
        for ((_, data) in shops) {
            val loc = data.location.clone().add(0.5, 1.5, 0.5)
            val world = loc.world ?: continue
            if (plugin.interactableManager.isActive(data.activeSwitches)) {
                world.spawnParticle(Particle.HAPPY_VILLAGER, loc, 3, 0.3, 0.3, 0.3)
            } else {
                world.spawnParticle(Particle.ASH, loc, 5, 0.3, 0.3, 0.3)
            }
        }
    }
}
