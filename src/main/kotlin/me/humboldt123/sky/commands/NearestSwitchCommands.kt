package me.humboldt123.sky.commands

import me.humboldt123.sky.Sky
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class AddSwitchToNearestCommand(private val plugin: Sky) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return true }
        if (args.isEmpty()) { sender.sendMessage("§cUsage: /addswitchtonearest <switch>"); return true }

        val switchName = args[0].lowercase()
        if (switchName !in plugin.interactableManager.switches) {
            sender.sendMessage("§cSwitch '$switchName' does not exist.")
            return true
        }

        val nearest = plugin.interactableManager.findNearest(sender.location)
        if (nearest == null) { sender.sendMessage("§cNo interactables found nearby."); return true }

        if (switchName in nearest.activeSwitches) {
            sender.sendMessage("§c${nearest.type} '${nearest.name}' already has switch '$switchName'.")
            return true
        }

        nearest.activeSwitches.add(switchName)
        plugin.interactableManager.save()
        sender.sendMessage("§a[Sky] Added switch '$switchName' to ${nearest.type} '${nearest.name}' (${String.format("%.1f", nearest.distance)} blocks away)")
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        if (args.size == 1) return plugin.interactableManager.switches.keys.filter { it.startsWith(args[0], ignoreCase = true) }.toList()
        return emptyList()
    }
}

class RemoveSwitchFromNearestCommand(private val plugin: Sky) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return true }
        if (args.isEmpty()) { sender.sendMessage("§cUsage: /removeswitchfromnearest <switch>"); return true }

        val switchName = args[0].lowercase()
        val nearest = plugin.interactableManager.findNearest(sender.location)
        if (nearest == null) { sender.sendMessage("§cNo interactables found nearby."); return true }

        if (nearest.activeSwitches.remove(switchName)) {
            plugin.interactableManager.save()
            sender.sendMessage("§a[Sky] Removed switch '$switchName' from ${nearest.type} '${nearest.name}'")
        } else {
            sender.sendMessage("§c${nearest.type} '${nearest.name}' doesn't have switch '$switchName'.")
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        if (args.size == 1) return plugin.interactableManager.switches.keys.filter { it.startsWith(args[0], ignoreCase = true) }.toList()
        return emptyList()
    }
}

class ListSwitchesOnNearestCommand(private val plugin: Sky) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this command."); return true }

        val nearest = plugin.interactableManager.findNearest(sender.location)
        if (nearest == null) { sender.sendMessage("§cNo interactables found nearby."); return true }

        sender.sendMessage("§6Nearest: ${nearest.type} '${nearest.name}' (${String.format("%.1f", nearest.distance)} blocks)")
        if (nearest.activeSwitches.isEmpty()) {
            sender.sendMessage("§7  Always active (no switch restriction)")
        } else {
            sender.sendMessage("§7  Active switches: ${nearest.activeSwitches.joinToString(", ")}")
        }
        return true
    }
}
