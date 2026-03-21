package me.bigratenthusiast.crypt.commands

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.session.ClipboardHolder
import me.bigratenthusiast.crypt.Crypt
import me.bigratenthusiast.crypt.reset.ResetSequence
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import java.io.File
import java.io.FileInputStream

class ResetMapCommand(private val plugin: Crypt) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val schematicFile = plugin.config.getString("locations.schematic-file") ?: run {
            sender.sendMessage("§cSchematic file not configured! Set locations.schematic-file in config.")
            return true
        }

        val originStr = plugin.config.getString("locations.schematic-origin") ?: run {
            sender.sendMessage("§cSchematic origin not configured! Set locations.schematic-origin in config.")
            return true
        }

        val parts = originStr.split(":")
        if (parts.size != 4) {
            sender.sendMessage("§cInvalid schematic-origin format. Expected world:x:y:z")
            return true
        }

        val world = Bukkit.getWorld(parts[0]) ?: run {
            sender.sendMessage("§cWorld '${parts[0]}' not found!")
            return true
        }
        val x = parts[1].toIntOrNull() ?: run { sender.sendMessage("§cInvalid X coordinate."); return true }
        val y = parts[2].toIntOrNull() ?: run { sender.sendMessage("§cInvalid Y coordinate."); return true }
        val z = parts[3].toIntOrNull() ?: run { sender.sendMessage("§cInvalid Z coordinate."); return true }

        // Look in plugin folder first, then FAWE schematics folder
        val file = File(plugin.dataFolder, schematicFile).takeIf { it.exists() }
            ?: File(Bukkit.getPluginsFolder(), "FastAsyncWorldEdit/schematics/$schematicFile").takeIf { it.exists() }
            ?: run {
                sender.sendMessage("§cSchematic file '$schematicFile' not found!")
                return true
            }

        val format = ClipboardFormats.findByFile(file) ?: run {
            sender.sendMessage("§cUnrecognized schematic format for '$schematicFile'.")
            return true
        }

        sender.sendMessage("§e[Crypt] Pasting schematic '$schematicFile'...")

        // Run schematic paste async, then reset on main thread
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                val clipboard = format.getReader(FileInputStream(file)).use { it.read() }
                val weWorld = BukkitAdapter.adapt(world)

                val editSession = WorldEdit.getInstance().newEditSessionBuilder()
                    .world(weWorld)
                    .build()
                try {
                    val operation = ClipboardHolder(clipboard)
                        .createPaste(editSession)
                        .to(BlockVector3.at(x, y, z))
                        .copyEntities(false)
                        .build()
                    Operations.complete(operation)
                } finally {
                    editSession.close()
                }

                // Run reset sequence on main thread after paste completes
                plugin.server.scheduler.runTask(plugin, Runnable {
                    sender.sendMessage("§a[Crypt] Schematic pasted! Running reset sequence...")
                    ResetSequence.run(plugin)
                })
            } catch (e: Exception) {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    sender.sendMessage("§cSchematic paste failed: ${e.message}")
                })
                plugin.logger.severe("Schematic paste failed: ${e.stackTraceToString()}")
            }
        })

        return true
    }
}
