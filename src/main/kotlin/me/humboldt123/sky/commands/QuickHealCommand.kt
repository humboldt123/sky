package me.humboldt123.sky.commands

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class QuickHealCommand : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val target = if (args.isEmpty()) {
            if (sender is Player) sender
            else { sender.sendMessage("§cUsage: /quickheal <player>"); return true }
        } else {
            Bukkit.getPlayer(args[0]) ?: run {
                sender.sendMessage("§cPlayer '${args[0]}' not found.")
                return true
            }
        }

        target.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, 100, 255, false, false)) // 5s
        target.addPotionEffect(PotionEffect(PotionEffectType.SATURATION, 100, 255, false, false))   // 5s

        val msg = if (sender is Player && sender.uniqueId == target.uniqueId) {
            "§a${sender.name} quickhealed themselves"
        } else {
            "§a${sender.name} quickhealed ${target.name}."
        }
        Bukkit.broadcast(net.kyori.adventure.text.Component.text(msg))
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }
}
