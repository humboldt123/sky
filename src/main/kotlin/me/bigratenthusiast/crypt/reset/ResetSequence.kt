package me.bigratenthusiast.crypt.reset

import me.bigratenthusiast.crypt.Crypt
import me.bigratenthusiast.crypt.util.clearFully
import org.bukkit.Bukkit
import org.bukkit.entity.*

object ResetSequence {

    fun run(plugin: Crypt) {
        val lobby = plugin.configManager.getLobbySpawn()
        if (lobby == null) {
            plugin.logger.warning("lobby-spawn not set! Use /crypt set lobby-spawn")
            Bukkit.broadcast(net.kyori.adventure.text.Component.text("§c[Crypt] lobby-spawn not configured!"))
            return
        }

        // 1. Teleport all players to lobby, set survival, clear everything
        for (player in Bukkit.getOnlinePlayers()) {
            player.teleport(lobby)
            player.clearFully()
        }

        // 2. Clear all ability state
        plugin.cooldownManager.clearAll()
        plugin.itemCooldownManager.clearAll()
        plugin.shieldManager.clearAll()
        plugin.necromancerManager.clearAll()
        plugin.bombermanManager.clearAll()

        // 3. Reset interactable state
        plugin.interactableManager.resetAll()
        plugin.interactableListener.resetAll()
        plugin.snakeBlockTask.resetAll()
        plugin.pondPortalTask.reset()
        plugin.switchListener.resetSwitches() // restores blocks, updates all interactables

        // 4. Kill all non-player non-villager non-painting non-item-frame entities
        killEntities()

        // 5. Restart snake tasks (after kill so stands are clean)
        plugin.snakeBlockTask.startAll()

        Bukkit.broadcast(net.kyori.adventure.text.Component.text("§eMap reset!"))
    }

    fun killEntities() {
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities) {
                if (entity is Player) continue
                if (entity is Villager) continue
                if (entity is Painting) continue
                if (entity is ItemFrame) continue
                entity.remove()
            }
        }
    }
}
