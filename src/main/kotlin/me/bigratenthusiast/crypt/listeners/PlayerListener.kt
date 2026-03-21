package me.bigratenthusiast.crypt.listeners

import me.bigratenthusiast.crypt.Crypt
import me.bigratenthusiast.crypt.util.clearFully
import me.bigratenthusiast.crypt.util.sendActionBarMsg
import org.bukkit.GameMode
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerRespawnEvent

class PlayerListener(private val plugin: Crypt) : Listener {

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.player
        plugin.bombermanManager.clearPlayer(player.uniqueId)
        // Delay spectator set by 1 tick so death screen shows
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            player.gameMode = GameMode.SPECTATOR
        }, 1L)
    }

    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val lobby = plugin.configManager.getLobbySpawn() ?: return
        event.respawnLocation = lobby
    }

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        if (player.gameMode == GameMode.SPECTATOR) return

        val to = event.to ?: return

        // Height ceiling: Y > 240 — push down // TODO: TEST
        if (to.y > 240) {
            val loc = player.location
            loc.y = loc.y - 2.0
            player.teleport(loc)
            player.sendActionBarMsg("§cYou've reached the height limit!")
            return
        }

        // Floor: Y < 60 — teleport to lobby // TODO: TEST
        if (to.y < 60) {
            val lobby = plugin.configManager.getLobbySpawn()
            if (lobby != null) {
                player.fallDistance = 0f
                // Despawn necromancer minions + bomberman TNT (void counts as death)
                plugin.necromancerManager.despawnMinionsFor(player.uniqueId)
                plugin.bombermanManager.clearPlayer(player.uniqueId)
                player.clearFully()
                player.teleport(lobby)
                player.sendActionBarMsg("§eYou fell into the void! Teleported to lobby.")
            }
        }
    }
}
