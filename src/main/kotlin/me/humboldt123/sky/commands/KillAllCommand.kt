package me.humboldt123.sky.commands

import me.humboldt123.sky.Sky
import me.humboldt123.sky.reset.ResetSequence
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

class KillAllCommand(private val plugin: Sky) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // Clear necromancer state first so minion deaths don't return eggs
        plugin.necromancerManager.clearAll()
        ResetSequence.killEntities()
        sender.sendMessage("§a[Sky] Killed all non-player/non-villager/non-painting/non-item-frame entities.")
        return true
    }
}
