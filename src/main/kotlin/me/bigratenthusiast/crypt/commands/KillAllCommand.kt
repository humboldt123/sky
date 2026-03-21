package me.bigratenthusiast.crypt.commands

import me.bigratenthusiast.crypt.Crypt
import me.bigratenthusiast.crypt.reset.ResetSequence
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

class KillAllCommand(private val plugin: Crypt) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // Clear necromancer state first so minion deaths don't return eggs
        plugin.necromancerManager.clearAll()
        ResetSequence.killEntities()
        sender.sendMessage("§a[Crypt] Killed all non-player/non-villager/non-painting/non-item-frame entities.")
        return true
    }
}
