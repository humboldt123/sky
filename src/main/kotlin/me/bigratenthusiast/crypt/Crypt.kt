package me.bigratenthusiast.crypt

import me.bigratenthusiast.crypt.abilities.AbilityListener
import me.bigratenthusiast.crypt.abilities.BombermanManager
import me.bigratenthusiast.crypt.abilities.CooldownManager
import me.bigratenthusiast.crypt.abilities.ItemCooldownManager
import me.bigratenthusiast.crypt.abilities.NecromancerManager
import me.bigratenthusiast.crypt.abilities.ShieldManager
import me.bigratenthusiast.crypt.commands.*
import me.bigratenthusiast.crypt.config.ConfigManager
import me.bigratenthusiast.crypt.interactables.InteractableListener
import me.bigratenthusiast.crypt.interactables.InteractableManager
import me.bigratenthusiast.crypt.interactables.PondPortalTask
import me.bigratenthusiast.crypt.interactables.SnakeBlockTask
import me.bigratenthusiast.crypt.interactables.SwitchListener
import me.bigratenthusiast.crypt.kit.KitManager
import me.bigratenthusiast.crypt.listeners.CombatListener
import me.bigratenthusiast.crypt.listeners.PlayerListener
import me.bigratenthusiast.crypt.shop.ShopManager
import me.bigratenthusiast.crypt.util.Keys
import me.bigratenthusiast.crypt.util.sendActionBarMsg
import org.bukkit.command.CommandExecutor
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable

class Crypt : JavaPlugin() {

    lateinit var configManager: ConfigManager
        private set
    lateinit var cooldownManager: CooldownManager
        private set
    lateinit var shieldManager: ShieldManager
        private set
    lateinit var necromancerManager: NecromancerManager
        private set
    lateinit var bombermanManager: BombermanManager
        private set
    lateinit var interactableManager: InteractableManager
        private set
    lateinit var switchListener: SwitchListener
        private set
    lateinit var interactableListener: InteractableListener
        private set
    lateinit var snakeBlockTask: SnakeBlockTask
        private set
    lateinit var pondPortalTask: PondPortalTask
        private set
    lateinit var kitManager: KitManager
        private set
    lateinit var itemCooldownManager: ItemCooldownManager
        private set
    lateinit var shopManager: ShopManager
        private set

    override fun onEnable() {
        // Initialize PDC keys
        Keys.init(this)

        // Initialize managers
        configManager = ConfigManager(this)
        configManager.load()

        cooldownManager = CooldownManager()
        itemCooldownManager = ItemCooldownManager(this)
        shieldManager = ShieldManager(this)
        necromancerManager = NecromancerManager(this)
        bombermanManager = BombermanManager(this)
        interactableManager = InteractableManager(this)
        interactableManager.load()

        kitManager = KitManager(this)
        kitManager.load()

        shopManager = ShopManager(this)
        shopManager.load()

        switchListener = SwitchListener(this)
        interactableListener = InteractableListener(this)
        snakeBlockTask = SnakeBlockTask(this)
        pondPortalTask = PondPortalTask(this)

        // Register commands
        getCommand("rematch")?.setExecutor(RematchCommand(this))
        getCommand("deathmatch")?.setExecutor(DeathmatchCommand(this))

        val quickHealCmd = QuickHealCommand()
        getCommand("quickheal")?.setExecutor(quickHealCmd)
        getCommand("quickheal")?.tabCompleter = quickHealCmd

        getCommand("killall")?.setExecutor(KillAllCommand(this))

        val cryptCmd = CryptAdminCommand(this)
        getCommand("crypt")?.setExecutor(cryptCmd)
        getCommand("crypt")?.tabCompleter = cryptCmd

        val addSwitchCmd = AddSwitchToNearestCommand(this)
        getCommand("addswitchtonearest")?.setExecutor(addSwitchCmd)
        getCommand("addswitchtonearest")?.tabCompleter = addSwitchCmd

        val removeSwitchCmd = RemoveSwitchFromNearestCommand(this)
        getCommand("removeswitchfromnearest")?.setExecutor(removeSwitchCmd)
        getCommand("removeswitchfromnearest")?.tabCompleter = removeSwitchCmd

        getCommand("listswitchesonnearest")?.setExecutor(ListSwitchesOnNearestCommand(this))

        // /kits — kit selection GUI (creative always, survival once per rematch)
        getCommand("kits")?.setExecutor(CommandExecutor { sender, _, _, _ ->
            if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return@CommandExecutor true }
            if (sender.gameMode != org.bukkit.GameMode.CREATIVE && kitManager.hasUsedKitsThisRound(sender)) {
                sender.sendMessage("§cYou already selected a kit this round! Ask an admin for /crypt kit giveuse")
                return@CommandExecutor true
            }
            kitManager.openKitGui(sender)
            true
        })

        // /shop — open nearest shop GUI
        getCommand("shop")?.setExecutor(CommandExecutor { sender, _, _, _ ->
            if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return@CommandExecutor true }
            for ((name, data) in shopManager.shops) {
                val uuid = data.villagerUUID ?: continue
                val loc = data.location
                if (loc.world != sender.world) continue
                if (loc.distance(sender.location) > 10.0) continue
                if (shopManager.isActive(name)) {
                    shopManager.openBuyGui(sender, name)
                } else {
                    val switchNames = data.activeSwitches.joinToString("§f, §6").ifEmpty { "a switch" }
                    sender.sendActionBarMsg("§fCome back when §6$switchNames §fis active")
                }
                return@CommandExecutor true
            }
            sender.sendMessage("§cNo shop found nearby! Right-click a shop villager.")
            true
        })

        // /resetmap — paste schematic + full reset
        getCommand("resetmap")?.setExecutor(ResetMapCommand(this))

        // Register listeners
        server.pluginManager.registerEvents(PlayerListener(this), this)
        server.pluginManager.registerEvents(CombatListener(this), this)
        server.pluginManager.registerEvents(AbilityListener(this), this)
        server.pluginManager.registerEvents(itemCooldownManager, this)
        server.pluginManager.registerEvents(necromancerManager, this)
        server.pluginManager.registerEvents(bombermanManager, this)
        server.pluginManager.registerEvents(switchListener, this)
        server.pluginManager.registerEvents(interactableListener, this)
        server.pluginManager.registerEvents(kitManager, this)
        server.pluginManager.registerEvents(shopManager, this)

        // Start interactable tasks — sync state from saved activeSwitch
        switchListener.updateAllInteractables()
        pondPortalTask.start()
        snakeBlockTask.startAll()

        // Re-associate shop villagers on startup
        shopManager.initVillagers()

        // Particle tick task (every 10 ticks = 0.5s)
        object : BukkitRunnable() {
            override fun run() {
                interactableListener.tickParticles()
                shopManager.tickParticles()
            }
        }.runTaskTimer(this, 20L, 10L)

        // Item cooldown tick task (every 20 ticks = 1s) — updates lore/durability on tracked items
        object : BukkitRunnable() {
            override fun run() {
                itemCooldownManager.tick()
            }
        }.runTaskTimer(this, 20L, 20L)

        // Bomberman mine proximity (every 5 ticks = 0.25s)
        object : BukkitRunnable() {
            override fun run() {
                bombermanManager.tickProximity()
            }
        }.runTaskTimer(this, 20L, 5L)

        // Bomberman crossbow TNT impact detection (every tick for instant rocket jumping)
        object : BukkitRunnable() {
            override fun run() {
                bombermanManager.tickCrossbowTnt()
            }
        }.runTaskTimer(this, 20L, 1L)

        // Clean up stale cooldown items from a previous restart/reload
        itemCooldownManager.cleanupStaleItems()

        logger.info("Crypt enabled!")
    }

    override fun onDisable() {
        necromancerManager.clearAll()
        bombermanManager.clearAll()
        itemCooldownManager.clearAll()
        switchListener.stopBouncePigPolling()
        snakeBlockTask.stopAll()
        pondPortalTask.stop()
        interactableManager.save()
        kitManager.save()
        shopManager.save()
        configManager.save()
        logger.info("Crypt disabled.")
    }
}
