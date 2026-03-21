package me.humboldt123.sky.commands

import me.humboldt123.sky.Sky
import me.humboldt123.sky.reset.ResetSequence
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

class RematchCommand(private val plugin: Sky) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        ResetSequence.run(plugin)

        // Reset kit usage tracking, then open kit GUI for all players after 1-tick delay
        plugin.kitManager.resetKitUsage()
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                plugin.kitManager.openKitGui(player)
            }
        }, 1L)

        return true
    }
}
