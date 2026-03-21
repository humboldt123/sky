package me.bigratenthusiast.crypt.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

class StubCommand(private val message: String) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        sender.sendMessage("§7[Crypt] $message")
        return true
    }
}
