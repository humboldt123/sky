package me.humboldt123.sky.commands

import me.humboldt123.sky.Sky
import me.humboldt123.sky.interactables.InteractableManager
import me.humboldt123.sky.util.toConfigString
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.entity.Villager

class SkyAdminCommand(private val plugin: Sky) : CommandExecutor, TabCompleter {

    private val im get() = plugin.interactableManager
    private val km get() = plugin.kitManager
    private val sm get() = plugin.shopManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "set" -> handleSet(sender, args)

            "cancel" -> handleCancel(sender)
            "reload" -> handleReload(sender)
            "switch" -> handleSwitch(sender, args)
            "jumppad" -> handleJumpPad(sender, args)
            "bouncestand" -> handleBounceStand(sender, args)
            "waterarc" -> handleWaterArc(sender, args)
            "lavatrap" -> handleLavaTrap(sender, args)
            "snake" -> handleSnake(sender, args)
            "portal" -> handlePortal(sender, args)
            "pondportal" -> handlePondPortal(sender, args)
            "bell" -> handleBell(sender, args)
            "crystal" -> handleCrystal(sender, args)
            "ghast" -> handleGhast(sender, args)
            "kit" -> handleKit(sender, args)
            "shop" -> handleShop(sender, args)
            else -> sendHelp(sender)
        }
        return true
    }

    // --- Phase 1 Commands ---

    private fun handleSet(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return }
        if (args.size < 2) {
            sender.sendMessage("§cUsage: /sky set <${plugin.configManager.validLocationKeys.joinToString("|")}>")
            return
        }
        val key = args[1].lowercase()
        if (key !in plugin.configManager.validLocationKeys) {
            sender.sendMessage("§cInvalid key '$key'. Valid: ${plugin.configManager.validLocationKeys.joinToString(", ")}")
            return
        }
        plugin.configManager.setLocation(key, sender.location)
        sender.sendMessage("§a[Sky] Set '$key' to ${sender.location.toConfigString()}")
    }


    private fun handleCancel(sender: CommandSender) {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return }
        val pending = im.pendingRegistrations.remove(sender.uniqueId)
        if (pending != null) {
            sender.sendMessage("§a[Sky] Cancelled pending ${pending.type} registration for '${pending.name}'.")
        } else {
            sender.sendMessage("§7No pending registration to cancel.")
        }
    }

    private fun handleReload(sender: CommandSender) {
        plugin.configManager.load()
        im.load()
        km.load()
        sm.load()
        sm.initVillagers()
        sender.sendMessage("§a[Sky] Config reloaded.")
    }

    // --- Switch ---

    private fun handleSwitch(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return }
        if (args.size < 2) { sender.sendMessage("§cUsage: /sky switch <register|remove|list> <name> [material]"); return }
        when (args[1].lowercase()) {
            "list" -> {
                if (im.switches.isEmpty()) { sender.sendMessage("§7No switches configured."); return }
                sender.sendMessage("§6Switches:")
                for ((name, _) in im.switches) {
                    val color = if (im.activeSwitch == name) "§a" else "§c"
                    sender.sendMessage("  $color$name")
                }
            }
            "register" -> {
                if (args.size < 4) { sender.sendMessage("§cUsage: /sky switch register <name> <material>"); return }
                val name = args[2].lowercase()
                val mat = Material.matchMaterial(args[3])
                if (mat == null || !mat.isBlock) { sender.sendMessage("§cInvalid block material: ${args[3]}"); return }
                val loc = sender.location.block.location
                im.switches[name] = InteractableManager.SwitchData(loc, mat)
                loc.block.type = mat
                loc.clone().add(0.0, 1.0, 0.0).block.type = Material.STONE_PRESSURE_PLATE
                im.save()
                sender.sendMessage("§a[Sky] Registered switch '$name' (${mat.name}) at ${loc.toConfigString()}")
            }
            "remove" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky switch remove <name>"); return }
                val name = args[2].lowercase()
                if (im.switches.remove(name) != null) {
                    // If active switch was deleted, clear it
                    if (im.activeSwitch == name) {
                        im.activeSwitch = null
                        refreshInteractables()
                    }
                    // Warn about interactables/shops still referencing this switch
                    val usages = im.findSwitchUsages(name) + sm.findSwitchUsages(name)
                    if (usages.isNotEmpty()) {
                        sender.sendMessage("§e[Sky] Warning: ${usages.size} interactable(s) still reference switch '$name': ${usages.joinToString(", ")}")
                    }
                    im.save()
                    sender.sendMessage("§a[Sky] Removed switch '$name'.")
                } else {
                    sender.sendMessage("§cSwitch '$name' not found.")
                }
            }
            else -> sender.sendMessage("§cUsage: /sky switch <register|remove|list>")
        }
    }

    // --- Jump Pad ---

    private fun handleJumpPad(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return }
        if (args.size < 2) { sender.sendMessage("§cUsage: /sky jumppad <add|remove|setswitch> <name>"); return }
        when (args[1].lowercase()) {
            "add" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky jumppad add <name>"); return }
                val name = args[2].lowercase()
                im.jumpPads[name] = InteractableManager.JumpPadData(sender.location.block.location, mutableListOf())
                im.save()
                refreshInteractables()
                sender.sendMessage("§a[Sky] Added jump pad '$name' at ${sender.location.block.location.toConfigString()}")
            }
            "remove" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky jumppad remove <name>"); return }
                val name = args[2].lowercase()
                if (im.jumpPads.remove(name) != null) { im.save(); sender.sendMessage("§a[Sky] Removed jump pad '$name'.") }
                else sender.sendMessage("§cJump pad '$name' not found.")
            }
            "setswitch" -> handleSetSwitch(sender, args, "jumppad", im.jumpPads)
            else -> sender.sendMessage("§cUsage: /sky jumppad <add|remove|setswitch>")
        }
    }

    // --- Bounce Stand ---

    private fun handleBounceStand(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return }
        if (args.size < 2) { sender.sendMessage("§cUsage: /sky bouncestand <add|remove|setswitch> <name>"); return }
        when (args[1].lowercase()) {
            "add" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky bouncestand add <name>"); return }
                val name = args[2].lowercase()
                im.bounceStands[name] = InteractableManager.BounceStandData(sender.location, mutableListOf())
                im.save()
                refreshInteractables()
                sender.sendMessage("§a[Sky] Added bounce stand '$name' at ${sender.location.toConfigString()}")
            }
            "remove" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky bouncestand remove <name>"); return }
                val name = args[2].lowercase()
                if (im.bounceStands.remove(name) != null) { im.save(); sender.sendMessage("§a[Sky] Removed bounce stand '$name'.") }
                else sender.sendMessage("§cBounce stand '$name' not found.")
            }
            "setswitch" -> handleSetSwitch(sender, args, "bouncestand", im.bounceStands)
            else -> sender.sendMessage("§cUsage: /sky bouncestand <add|remove|setswitch>")
        }
    }

    // --- Water Arc ---

    private fun handleWaterArc(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return }
        if (args.size < 2) { sender.sendMessage("§cUsage: /sky waterarc <add|pos1|pos2|remove|setswitch> <name>"); return }
        when (args[1].lowercase()) {
            "add" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky waterarc add <name>"); return }
                val name = args[2].lowercase()
                val loc = sender.location.block.location
                im.waterArcs[name] = InteractableManager.WaterArcData(loc, loc.clone(), mutableListOf())
                im.save()
                refreshInteractables()
                sender.sendMessage("§a[Sky] Added water arc '$name'. Set pos1 and pos2.")
            }
            "pos1" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky waterarc pos1 <name>"); return }
                val name = args[2].lowercase()
                val data = im.waterArcs[name] ?: run { sender.sendMessage("§cWater arc '$name' not found."); return }
                data.pos1 = sender.location.block.location
                im.save()
                sender.sendMessage("§a[Sky] Set pos1 for water arc '$name' to ${data.pos1.toConfigString()}")
            }
            "pos2" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky waterarc pos2 <name>"); return }
                val name = args[2].lowercase()
                val data = im.waterArcs[name] ?: run { sender.sendMessage("§cWater arc '$name' not found."); return }
                data.pos2 = sender.location.block.location
                im.save()
                sender.sendMessage("§a[Sky] Set pos2 for water arc '$name' to ${data.pos2.toConfigString()}")
            }
            "remove" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky waterarc remove <name>"); return }
                val name = args[2].lowercase()
                if (im.waterArcs.remove(name) != null) { im.save(); sender.sendMessage("§a[Sky] Removed water arc '$name'.") }
                else sender.sendMessage("§cWater arc '$name' not found.")
            }
            "setswitch" -> handleSetSwitch(sender, args, "waterarc", im.waterArcs)
            else -> sender.sendMessage("§cUsage: /sky waterarc <add|pos1|pos2|remove|setswitch>")
        }
    }

    // --- Lava Trap ---

    private fun handleLavaTrap(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return }
        if (args.size < 2) { sender.sendMessage("§cUsage: /sky lavatrap <add|pos1|pos2|settrigger|remove|setswitch> <name>"); return }
        when (args[1].lowercase()) {
            "add" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky lavatrap add <name>"); return }
                val name = args[2].lowercase()
                val loc = sender.location.block.location
                im.lavaTraps[name] = InteractableManager.LavaTrapData(loc, loc.clone(), loc.clone(), mutableListOf())
                im.save()
                sender.sendMessage("§a[Sky] Added lava trap '$name'. Set pos1, pos2, and trigger.")
            }
            "pos1" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky lavatrap pos1 <name>"); return }
                val name = args[2].lowercase()
                val data = im.lavaTraps[name] ?: run { sender.sendMessage("§cLava trap '$name' not found."); return }
                data.pos1 = sender.location.block.location
                im.save()
                sender.sendMessage("§a[Sky] Set pos1 for lava trap '$name' to ${data.pos1.toConfigString()}")
            }
            "pos2" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky lavatrap pos2 <name>"); return }
                val name = args[2].lowercase()
                val data = im.lavaTraps[name] ?: run { sender.sendMessage("§cLava trap '$name' not found."); return }
                data.pos2 = sender.location.block.location
                im.save()
                sender.sendMessage("§a[Sky] Set pos2 for lava trap '$name' to ${data.pos2.toConfigString()}")
            }
            "settrigger" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky lavatrap settrigger <name>"); return }
                val name = args[2].lowercase()
                val data = im.lavaTraps[name] ?: run { sender.sendMessage("§cLava trap '$name' not found."); return }
                data.trigger = sender.location.block.location
                im.save()
                sender.sendMessage("§a[Sky] Set trigger for lava trap '$name' to ${data.trigger.toConfigString()}")
            }
            "remove" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky lavatrap remove <name>"); return }
                val name = args[2].lowercase()
                if (im.lavaTraps.remove(name) != null) { im.save(); sender.sendMessage("§a[Sky] Removed lava trap '$name'.") }
                else sender.sendMessage("§cLava trap '$name' not found.")
            }
            "setswitch" -> handleSetSwitch(sender, args, "lavatrap", im.lavaTraps)
            else -> sender.sendMessage("§cUsage: /sky lavatrap <add|pos1|pos2|settrigger|remove|setswitch>")
        }
    }

    // --- Snake ---

    private fun handleSnake(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return }
        if (args.size < 2) { sender.sendMessage("§cUsage: /sky snake <add|addpoint|clearpoints|setspeed|setlength|setblocks|remove|setswitch> <name>"); return }
        when (args[1].lowercase()) {
            "add" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky snake add <name>"); return }
                val name = args[2].lowercase()
                im.snakes[name] = InteractableManager.SnakeData(mutableListOf(), 4, mutableListOf())
                im.save()
                sender.sendMessage("§a[Sky] Added snake '$name'. Use /sky snake addpoint $name to add route points.")
            }
            "addpoint" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky snake addpoint <name>"); return }
                val name = args[2].lowercase()
                val data = im.snakes[name] ?: run { sender.sendMessage("§cSnake '$name' not found."); return }
                val loc = sender.location.block.location
                data.points.add(loc)
                im.save()
                // Restart snake if active to pick up new route
                plugin.snakeBlockTask.stopSnake(name)
                refreshInteractables()
                sender.sendMessage("§a[Sky] Added point #${data.points.size - 1} to snake '$name' at ${loc.toConfigString()}")
            }
            "clearpoints" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky snake clearpoints <name>"); return }
                val name = args[2].lowercase()
                val data = im.snakes[name] ?: run { sender.sendMessage("§cSnake '$name' not found."); return }
                plugin.snakeBlockTask.stopSnake(name)
                data.points.clear()
                im.save()
                sender.sendMessage("§a[Sky] Cleared all points for snake '$name'.")
            }
            "setspeed" -> {
                if (args.size < 4) { sender.sendMessage("§cUsage: /sky snake setspeed <name> <ticks>"); return }
                val name = args[2].lowercase()
                val data = im.snakes[name] ?: run { sender.sendMessage("§cSnake '$name' not found."); return }
                val ticks = args[3].toIntOrNull()
                if (ticks == null || ticks < 1) { sender.sendMessage("§cSpeed must be a positive number of ticks."); return }
                data.speedTicks = ticks
                im.save()
                // Restart snake to pick up new speed
                plugin.snakeBlockTask.stopSnake(name)
                refreshInteractables()
                sender.sendMessage("§a[Sky] Set snake '$name' speed to $ticks ticks per step.")
            }
            "setlength" -> {
                if (args.size < 4) { sender.sendMessage("§cUsage: /sky snake setlength <name> <blocks>"); return }
                val name = args[2].lowercase()
                val data = im.snakes[name] ?: run { sender.sendMessage("§cSnake '$name' not found."); return }
                val len = args[3].toIntOrNull()
                if (len == null || len < 1) { sender.sendMessage("§cLength must be a positive number."); return }
                data.length = len
                im.save()
                plugin.snakeBlockTask.stopSnake(name)
                refreshInteractables()
                sender.sendMessage("§a[Sky] Set snake '$name' length to $len blocks.")
            }
            "setblocks" -> {
                if (args.size < 4) { sender.sendMessage("§cUsage: /sky snake setblocks <name> <block1> [block2] ..."); return }
                val name = args[2].lowercase()
                val data = im.snakes[name] ?: run { sender.sendMessage("§cSnake '$name' not found."); return }
                val materials = mutableListOf<Material>()
                for (i in 3 until args.size) {
                    val mat = Material.matchMaterial(args[i])
                    if (mat == null || !mat.isBlock) { sender.sendMessage("§cInvalid block material: ${args[i]}"); return }
                    materials.add(mat)
                }
                data.blockTypes.clear()
                data.blockTypes.addAll(materials)
                im.save()
                // Restart snake to use new block types
                plugin.snakeBlockTask.stopSnake(name)
                refreshInteractables()
                sender.sendMessage("§a[Sky] Set snake '$name' blocks to: ${materials.joinToString(", ") { it.name }}")
            }
            "remove" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky snake remove <name>"); return }
                val name = args[2].lowercase()
                if (im.snakes.containsKey(name)) {
                    plugin.snakeBlockTask.stopSnake(name)
                    im.snakes.remove(name)
                    im.save()
                    sender.sendMessage("§a[Sky] Removed snake '$name'.")
                } else {
                    sender.sendMessage("§cSnake '$name' not found.")
                }
            }
            "setswitch" -> handleSetSwitch(sender, args, "snake", im.snakes)
            else -> sender.sendMessage("§cUsage: /sky snake <add|addpoint|clearpoints|setspeed|setlength|setblocks|remove|setswitch>")
        }
    }

    // --- Portal (Random Player Teleporter) ---

    private fun handlePortal(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return }
        if (args.size < 2) { sender.sendMessage("§cUsage: /sky portal <add|remove|setswitch> <name>"); return }
        when (args[1].lowercase()) {
            "add" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky portal add <name>"); return }
                val name = args[2].lowercase()
                im.portals[name] = InteractableManager.PortalData(sender.location.block.location, mutableListOf())
                im.save()
                refreshInteractables()
                sender.sendMessage("§a[Sky] Added portal '$name' at ${sender.location.block.location.toConfigString()}")
            }
            "remove" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky portal remove <name>"); return }
                val name = args[2].lowercase()
                if (im.portals.remove(name) != null) { im.save(); sender.sendMessage("§a[Sky] Removed portal '$name'.") }
                else sender.sendMessage("§cPortal '$name' not found.")
            }
            "setswitch" -> handleSetSwitch(sender, args, "portal", im.portals)
            else -> sender.sendMessage("§cUsage: /sky portal <add|remove|setswitch>")
        }
    }

    // --- Pond Portal ---

    private fun handlePondPortal(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return }
        if (args.size < 2) { sender.sendMessage("§cUsage: /sky pondportal <add|pos1|pos2|addstop|clearstops|remove|setswitch> <name>"); return }
        when (args[1].lowercase()) {
            "add" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky pondportal add <name>"); return }
                val name = args[2].lowercase()
                val loc = sender.location.block.location
                im.pondPortals[name] = InteractableManager.PondPortalData(loc, loc.clone(), mutableListOf(), mutableListOf())
                im.save()
                refreshInteractables()
                sender.sendMessage("§a[Sky] Added pond portal '$name'. Set pos1, pos2, and add stops.")
            }
            "pos1" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky pondportal pos1 <name>"); return }
                val name = args[2].lowercase()
                val data = im.pondPortals[name] ?: run { sender.sendMessage("§cPond portal '$name' not found."); return }
                data.pos1 = sender.location.block.location
                im.save()
                sender.sendMessage("§a[Sky] Set pos1 for pond portal '$name' to ${data.pos1.toConfigString()}")
            }
            "pos2" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky pondportal pos2 <name>"); return }
                val name = args[2].lowercase()
                val data = im.pondPortals[name] ?: run { sender.sendMessage("§cPond portal '$name' not found."); return }
                data.pos2 = sender.location.block.location
                im.save()
                sender.sendMessage("§a[Sky] Set pos2 for pond portal '$name' to ${data.pos2.toConfigString()}")
            }
            "addstop" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky pondportal addstop <name>"); return }
                val name = args[2].lowercase()
                val data = im.pondPortals[name] ?: run { sender.sendMessage("§cPond portal '$name' not found."); return }
                data.stops.add(sender.location)
                im.save()
                sender.sendMessage("§a[Sky] Added stop #${data.stops.size - 1} to pond portal '$name'")
            }
            "clearstops" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky pondportal clearstops <name>"); return }
                val name = args[2].lowercase()
                val data = im.pondPortals[name] ?: run { sender.sendMessage("§cPond portal '$name' not found."); return }
                data.stops.clear()
                im.save()
                sender.sendMessage("§a[Sky] Cleared all stops for pond portal '$name'.")
            }
            "remove" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky pondportal remove <name>"); return }
                val name = args[2].lowercase()
                if (im.pondPortals.remove(name) != null) { im.save(); sender.sendMessage("§a[Sky] Removed pond portal '$name'.") }
                else sender.sendMessage("§cPond portal '$name' not found.")
            }
            "setswitch" -> handleSetSwitch(sender, args, "pondportal", im.pondPortals)
            else -> sender.sendMessage("§cUsage: /sky pondportal <add|pos1|pos2|addstop|clearstops|remove|setswitch>")
        }
    }

    // --- Bell ---

    private fun handleBell(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return }
        if (args.size < 2) { sender.sendMessage("§cUsage: /sky bell <add|remove|setswitch> <name>"); return }
        when (args[1].lowercase()) {
            "add" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky bell add <name>"); return }
                val name = args[2].lowercase()
                im.bells[name] = InteractableManager.BellData(sender.location.block.location, mutableListOf())
                im.save()
                refreshInteractables()
                sender.sendMessage("§a[Sky] Added bell '$name' at ${sender.location.block.location.toConfigString()}")
            }
            "remove" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky bell remove <name>"); return }
                val name = args[2].lowercase()
                if (im.bells.remove(name) != null) { im.save(); sender.sendMessage("§a[Sky] Removed bell '$name'.") }
                else sender.sendMessage("§cBell '$name' not found.")
            }
            "setswitch" -> handleSetSwitch(sender, args, "bell", im.bells)
            else -> sender.sendMessage("§cUsage: /sky bell <add|remove|setswitch>")
        }
    }

    // --- Crystal Launcher ---

    private fun handleCrystal(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return }
        if (args.size < 2) { sender.sendMessage("§cUsage: /sky crystal <add|remove|setswitch> <name>"); return }
        when (args[1].lowercase()) {
            "add" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky crystal add <name>"); return }
                val name = args[2].lowercase()
                im.crystals[name] = InteractableManager.CrystalData(sender.location.block.location, mutableListOf())
                im.save()
                refreshInteractables()
                sender.sendMessage("§a[Sky] Added crystal launcher '$name' at ${sender.location.block.location.toConfigString()}")
            }
            "remove" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky crystal remove <name>"); return }
                val name = args[2].lowercase()
                if (im.crystals.remove(name) != null) { im.save(); sender.sendMessage("§a[Sky] Removed crystal launcher '$name'.") }
                else sender.sendMessage("§cCrystal launcher '$name' not found.")
            }
            "setswitch" -> handleSetSwitch(sender, args, "crystal", im.crystals)
            else -> sender.sendMessage("§cUsage: /sky crystal <add|remove|setswitch>")
        }
    }

    // --- Happy Ghast ---

    private fun handleGhast(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return }
        if (args.size < 2) { sender.sendMessage("§cUsage: /sky ghast <setstable|setswitch> [switches...]"); return }
        when (args[1].lowercase()) {
            "setstable" -> {
                im.ghastStable = sender.location
                im.save()
                refreshInteractables()
                sender.sendMessage("§a[Sky] Set ghast stable to ${sender.location.toConfigString()}")
            }
            "setswitch" -> {
                im.ghastSwitches.clear()
                if (args.size > 2) im.ghastSwitches.addAll(args.drop(2).map { it.lowercase() })
                im.save()
                refreshInteractables()
                if (im.ghastSwitches.isEmpty()) sender.sendMessage("§a[Sky] Ghast will be always active.")
                else sender.sendMessage("§a[Sky] Ghast active on switches: ${im.ghastSwitches.joinToString(", ")}")
            }
            else -> sender.sendMessage("§cUsage: /sky ghast <setstable|setswitch>")
        }
    }

    // --- Kit ---

    companion object {
        val SHULKER_COLORS = listOf(
            "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME",
            "PINK", "GRAY", "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE",
            "BROWN", "GREEN", "RED", "BLACK"
        )
    }

    private fun handleKit(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return }
        if (args.size < 2) { sender.sendMessage("§cUsage: /sky kit <edit|remove|list|status|giveuse|setcolor> [name]"); return }
        when (args[1].lowercase()) {
            "edit" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky kit edit <name>"); return }
                val name = args[2].lowercase()
                km.openEditGui(sender, name)
                sender.sendMessage("§a[Sky] Editing kit '$name'. Close the GUI to save.")
            }
            "remove" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky kit remove <name>"); return }
                val name = args[2].lowercase()
                if (km.kits.remove(name) != null) {
                    km.save()
                    sender.sendMessage("§a[Sky] Kit '$name' removed.")
                } else {
                    sender.sendMessage("§cKit '$name' not found.")
                }
            }
            "list" -> {
                if (km.kits.isEmpty()) { sender.sendMessage("§7No kits configured."); return }
                sender.sendMessage("§6Kits:")
                for ((name, data) in km.kits) {
                    sender.sendMessage("§7  $name §8— ${data.items.size} items")
                }
            }
            "status" -> {
                sender.sendMessage("§6Kit Status:")
                for (player in org.bukkit.Bukkit.getOnlinePlayers()) {
                    val color = if (km.hasUsedKitsThisRound(player)) "§c" else "§a"
                    sender.sendMessage("  $color${player.name}")
                }
            }
            "giveuse" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky kit giveuse <player>"); return }
                val target = org.bukkit.Bukkit.getPlayer(args[2])
                if (target == null) { sender.sendMessage("§cPlayer '${args[2]}' not found."); return }
                km.giveKitUse(target)
                sender.sendMessage("§a[Sky] Gave kit use to ${target.name}.")
                target.sendMessage("§a[Sky] You can now use /kits again!")
            }
            "setcolor" -> {
                if (args.size < 4) { sender.sendMessage("§cUsage: /sky kit setcolor <name> <color>"); return }
                val name = args[2].lowercase()
                val kit = km.kits[name] ?: run { sender.sendMessage("§cKit '$name' not found."); return }
                val color = args[3].uppercase()
                if (color !in SHULKER_COLORS) {
                    sender.sendMessage("§cInvalid color. Valid: ${SHULKER_COLORS.joinToString(", ").lowercase()}")
                    return
                }
                kit.shulkerColor = color
                km.save()
                sender.sendMessage("§a[Sky] Kit '$name' shulker box color set to $color.")
            }
            else -> sender.sendMessage("§cUsage: /sky kit <edit|remove|list|status|giveuse|setcolor>")
        }
    }

    // --- Shop ---

    private fun handleShop(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return }
        if (args.size < 2) { sender.sendMessage("§cUsage: /sky shop <add|edit|remove|setswitch|list> [name] [args...]"); return }
        when (args[1].lowercase()) {
            "add" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky shop add <name>"); return }
                val name = args[2].lowercase()
                if (sm.shops.containsKey(name)) { sender.sendMessage("§cShop '$name' already exists."); return }

                val villager = sm.findNearestVillager(sender)
                if (villager == null) { sender.sendMessage("§cNo villager found within 10 blocks!"); return }

                // Check if this villager is already registered to another shop
                val existingShop = sm.findShopByVillager(villager.uniqueId)
                if (existingShop != null) { sender.sendMessage("§cThis villager is already registered to shop '$existingShop'."); return }

                sm.shops[name] = me.humboldt123.sky.shop.ShopManager.ShopData(
                    villagerUUID = villager.uniqueId,
                    location = villager.location,
                    profession = villager.profession,
                    biome = villager.villagerType,
                    level = villager.villagerLevel.coerceIn(1, 5),
                    activeSwitches = mutableListOf(),
                    items = mutableListOf()
                )

                // Apply managed config to villager
                sm.applyVillagerConfig(villager, sm.shops[name]!!)

                sm.save()
                sender.sendMessage("§a[Sky] Shop '$name' created with villager ${villager.uniqueId}")
                sender.sendMessage("§7Use /sky shop edit $name to configure items and prices. Close GUI to save.")
            }
            "edit" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky shop edit <name>"); return }
                val name = args[2].lowercase()
                sm.openEditGui(sender, name)
            }
            "remove" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky shop remove <name>"); return }
                val name = args[2].lowercase()
                val data = sm.shops[name]
                if (data != null) {
                    // Restore villager to normal
                    val villager = data.villagerUUID?.let { sm.findVillager(it) }
                    if (villager != null) {
                        villager.isInvulnerable = false
                        villager.setGravity(true)
                        villager.setAI(true)
                        villager.isSilent = false
                    }
                    sm.shops.remove(name)
                    sm.save()
                    sender.sendMessage("§a[Sky] Shop '$name' removed. Villager restored to normal.")
                } else {
                    sender.sendMessage("§cShop '$name' not found.")
                }
            }
            "setswitch" -> {
                if (args.size < 3) { sender.sendMessage("§cUsage: /sky shop setswitch <name> [switches...]"); return }
                val name = args[2].lowercase()
                val data = sm.shops[name] ?: run { sender.sendMessage("§cShop '$name' not found."); return }
                data.activeSwitches.clear()
                if (args.size > 3) data.activeSwitches.addAll(args.drop(3).map { it.lowercase() })
                sm.save()
                if (data.activeSwitches.isEmpty()) sender.sendMessage("§a[Sky] Shop '$name' will be always active.")
                else sender.sendMessage("§a[Sky] Shop '$name' active on switches: ${data.activeSwitches.joinToString(", ")}")
            }
            "list" -> {
                if (sm.shops.isEmpty()) { sender.sendMessage("§7No shops configured."); return }
                sender.sendMessage("§6Shops:")
                for ((name, data) in sm.shops) {
                    val switchInfo = if (data.activeSwitches.isEmpty()) "always" else data.activeSwitches.joinToString(",")
                    sender.sendMessage("§7  $name §8— ${data.profession.key().value()}, level ${data.level}, ${data.items.size} items, switches: $switchInfo")
                }
            }
            else -> sender.sendMessage("§cUsage: /sky shop <add|edit|remove|setswitch|list>")
        }
    }

    // --- Refresh all interactable state ---

    private fun refreshInteractables() {
        plugin.switchListener.updateAllInteractables()
    }

    // --- Generic setswitch handler ---

    private fun <T> handleSetSwitch(sender: CommandSender, args: Array<out String>, typeName: String, map: Map<String, T>) {
        if (args.size < 3) { sender.sendMessage("§cUsage: /sky $typeName setswitch <name> [switches...]"); return }
        val name = args[2].lowercase()
        val entry = map[name]
        if (entry == null) { sender.sendMessage("§c${typeName.replaceFirstChar { it.uppercase() }} '$name' not found."); return }

        val switchList = getActiveSwitches(entry) ?: run { sender.sendMessage("§cInternal error."); return }

        switchList.clear()
        if (args.size > 3) switchList.addAll(args.drop(3).map { it.lowercase() })
        im.save()
        refreshInteractables()

        if (switchList.isEmpty()) sender.sendMessage("§a[Sky] ${typeName.replaceFirstChar { it.uppercase() }} '$name' will be always active.")
        else sender.sendMessage("§a[Sky] ${typeName.replaceFirstChar { it.uppercase() }} '$name' active on switches: ${switchList.joinToString(", ")}")
    }

    private fun getActiveSwitches(data: Any?): MutableList<String>? {
        return when (data) {
            is InteractableManager.JumpPadData -> data.activeSwitches
            is InteractableManager.BounceStandData -> data.activeSwitches
            is InteractableManager.WaterArcData -> data.activeSwitches
            is InteractableManager.LavaTrapData -> data.activeSwitches
            is InteractableManager.SnakeData -> data.activeSwitches
            is InteractableManager.PortalData -> data.activeSwitches
            is InteractableManager.PondPortalData -> data.activeSwitches
            is InteractableManager.BellData -> data.activeSwitches
            is InteractableManager.CrystalData -> data.activeSwitches
            else -> null
        }
    }

    // --- Help ---

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("§6[Sky] Admin Commands:")
        sender.sendMessage("§7  /sky set <key> §8— set a named location")

        sender.sendMessage("§7  /sky switch <register|remove|list> §8— manage switches")
        sender.sendMessage("§7  /sky jumppad <add|remove|setswitch> §8— jump pads")
        sender.sendMessage("§7  /sky bouncestand <add|remove|setswitch> §8— bounce stands")
        sender.sendMessage("§7  /sky waterarc <add|pos1|pos2|remove|setswitch> §8— water arcs")
        sender.sendMessage("§7  /sky lavatrap <add|pos1|pos2|settrigger|remove|setswitch> §8— lava traps")
        sender.sendMessage("§7  /sky snake <add|addpoint|clearpoints|setspeed|setlength|setblocks|remove|setswitch> §8— snakes")
        sender.sendMessage("§7  /sky portal <add|remove|setswitch> §8— random teleporters")
        sender.sendMessage("§7  /sky pondportal <add|pos1|pos2|addstop|clearstops|remove|setswitch> §8— pond portals")
        sender.sendMessage("§7  /sky bell <add|remove|setswitch> §8— bells")
        sender.sendMessage("§7  /sky crystal <add|remove|setswitch> §8— crystal launchers")
        sender.sendMessage("§7  /sky ghast <setstable|setswitch> §8— happy ghast")
        sender.sendMessage("§7  /sky kit <edit|remove|list|status|giveuse|setcolor> §8— kits (close GUI to save)")
        sender.sendMessage("§7  /sky shop <add|edit|remove|setswitch|list> §8— shops (close GUI to save)")
        sender.sendMessage("§7  /sky cancel §8— abort pending registration")
        sender.sendMessage("§7  /sky reload §8— reload config")
    }

    // --- Tab Completion ---

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("set", "cancel", "reload", "switch",
                "jumppad", "bouncestand", "waterarc", "lavatrap", "snake",
                "portal", "pondportal", "bell", "crystal", "ghast",
                "kit", "shop")
                .filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> when (args[0].lowercase()) {
                "set" -> plugin.configManager.validLocationKeys.filter { it.startsWith(args[1], ignoreCase = true) }.toList()

                "switch" -> listOf("register", "remove", "list").filter { it.startsWith(args[1], ignoreCase = true) }
                "jumppad", "bouncestand" -> listOf("add", "remove", "setswitch").filter { it.startsWith(args[1], ignoreCase = true) }
                "waterarc" -> listOf("add", "pos1", "pos2", "remove", "setswitch").filter { it.startsWith(args[1], ignoreCase = true) }
                "lavatrap" -> listOf("add", "pos1", "pos2", "settrigger", "remove", "setswitch").filter { it.startsWith(args[1], ignoreCase = true) }
                "snake" -> listOf("add", "addpoint", "clearpoints", "setspeed", "setlength", "setblocks", "remove", "setswitch").filter { it.startsWith(args[1], ignoreCase = true) }
                "portal" -> listOf("add", "remove", "setswitch").filter { it.startsWith(args[1], ignoreCase = true) }
                "pondportal" -> listOf("add", "pos1", "pos2", "addstop", "clearstops", "remove", "setswitch").filter { it.startsWith(args[1], ignoreCase = true) }
                "bell", "crystal" -> listOf("add", "remove", "setswitch").filter { it.startsWith(args[1], ignoreCase = true) }
                "ghast" -> listOf("setstable", "setswitch").filter { it.startsWith(args[1], ignoreCase = true) }
                "kit" -> listOf("edit", "remove", "list", "status", "giveuse", "setcolor").filter { it.startsWith(args[1], ignoreCase = true) }
                "shop" -> listOf("add", "edit", "remove", "setswitch", "list").filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
            3 -> {
                val type = args[0].lowercase()
                val action = args[1].lowercase()
                when {
                    type == "switch" && action == "remove" -> im.switches.keys
                    type == "jumppad" && action in listOf("remove", "setswitch") -> im.jumpPads.keys
                    type == "bouncestand" && action in listOf("remove", "setswitch") -> im.bounceStands.keys
                    type == "waterarc" && action in listOf("pos1", "pos2", "remove", "setswitch") -> im.waterArcs.keys
                    type == "lavatrap" && action in listOf("pos1", "pos2", "settrigger", "remove", "setswitch") -> im.lavaTraps.keys
                    type == "snake" && action in listOf("addpoint", "clearpoints", "setspeed", "setlength", "setblocks", "remove", "setswitch") -> im.snakes.keys
                    type == "portal" && action in listOf("remove", "setswitch") -> im.portals.keys
                    type == "pondportal" && action in listOf("pos1", "pos2", "addstop", "clearstops", "remove", "setswitch") -> im.pondPortals.keys
                    type == "bell" && action in listOf("remove", "setswitch") -> im.bells.keys
                    type == "crystal" && action in listOf("remove", "setswitch") -> im.crystals.keys
                    type == "kit" && action == "giveuse" -> org.bukkit.Bukkit.getOnlinePlayers().map { it.name }
                    type == "kit" && action in listOf("edit", "remove", "setcolor") -> km.kits.keys
                    type == "shop" && action in listOf("edit", "remove", "setswitch") -> sm.shops.keys
                    else -> emptyList()
                }.filter { it.startsWith(args[2], ignoreCase = true) }.toList()
            }
            else -> {
                val type = args[0].lowercase()
                val action = args[1].lowercase()
                // For setswitch args >= 4, suggest switch names
                if (action == "setswitch" || (type == "ghast" && action == "setswitch")) {
                    im.switches.keys.filter { it.startsWith(args.last(), ignoreCase = true) }.toList()
                } else if (type == "kit" && action == "setcolor" && args.size == 4) {
                    SHULKER_COLORS.map { it.lowercase() }.filter { it.startsWith(args[3], ignoreCase = true) }
                } else emptyList()
            }
        }
    }
}
