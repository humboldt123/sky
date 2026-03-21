package me.bigratenthusiast.crypt.interactables

import me.bigratenthusiast.crypt.Crypt
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask

class SnakeBlockTask(private val plugin: Crypt) {

    private val im get() = plugin.interactableManager

    // Active snake tasks (snake name -> task)
    private val activeTasks = mutableMapOf<String, BukkitTask>()

    // Current head index per snake
    private val currentIndex = mutableMapOf<String, Int>()

    // Interpolated route cache per snake
    private val routeCache = mutableMapOf<String, List<Location>>()

    // Blocks currently placed by each snake as a deque (oldest first)
    private val placedBlocks = mutableMapOf<String, ArrayDeque<Location>>()

    fun startAll() {
        for ((name, data) in im.snakes) {
            if (im.isActive(data.activeSwitches) && data.points.size >= 2) {
                startSnake(name)
            }
        }
    }

    fun stopAll() {
        for ((name, _) in activeTasks) {
            activeTasks[name]?.cancel()
        }
        activeTasks.clear()
        currentIndex.clear()
        routeCache.clear()
    }

    fun stopSnake(name: String) {
        activeTasks.remove(name)?.cancel()
        currentIndex.remove(name)

        // Clean up placed blocks immediately
        val blocks = placedBlocks.remove(name) ?: return
        val data = im.snakes[name]
        val blockTypes = data?.blockTypes ?: mutableListOf(Material.BONE_BLOCK)
        for (loc in blocks) {
            if (loc.block.type in blockTypes) {
                loc.block.type = Material.AIR
            }
        }
        routeCache.remove(name)
    }

    fun updateSnake(name: String) {
        val data = im.snakes[name] ?: return
        val shouldBeActive = im.isActive(data.activeSwitches) && data.points.size >= 2

        if (shouldBeActive && name !in activeTasks) {
            startSnake(name)
        } else if (!shouldBeActive && name in activeTasks) {
            deactivateSnake(name)
        }
    }

    fun updateAll() {
        for ((name, _) in im.snakes) {
            updateSnake(name)
        }
    }

    private fun startSnake(name: String) {
        val data = im.snakes[name] ?: return
        if (data.points.size < 2) return

        // Build interpolated route from waypoints
        val route = im.interpolateRoute(data.points)
        if (route.isEmpty()) return

        routeCache[name] = route
        currentIndex[name] = 0
        placedBlocks[name] = ArrayDeque()

        val blockTypes = if (data.blockTypes.isEmpty()) mutableListOf(Material.BONE_BLOCK) else data.blockTypes
        val length = data.length.coerceAtLeast(1)

        val task = object : BukkitRunnable() {
            override fun run() {
                val idx = currentIndex[name] ?: 0
                val cachedRoute = routeCache[name] ?: return
                if (cachedRoute.isEmpty()) { cancel(); return }

                val blocks = placedBlocks[name] ?: return

                val currentPos = cachedRoute[idx % cachedRoute.size]

                // Pick block type cycling through the list
                val blockType = blockTypes[idx % blockTypes.size]

                // Place block at current position
                currentPos.block.type = blockType

                // Remove block above current (so players can walk)
                currentPos.clone().add(0.0, 1.0, 0.0).block.type = Material.AIR

                // Track placed block
                blocks.addLast(currentPos.clone())

                // Remove oldest block if we exceed the length
                while (blocks.size > length) {
                    val oldest = blocks.removeFirst()
                    if (oldest.block.type in blockTypes) {
                        oldest.block.type = Material.AIR
                    }
                }

                currentIndex[name] = idx + 1
            }
        }.runTaskTimer(plugin, 0L, data.speedTicks.toLong())

        activeTasks[name] = task
    }

    private fun deactivateSnake(name: String) {
        activeTasks.remove(name)?.cancel()
        currentIndex.remove(name)
        routeCache.remove(name)

        // Freeze for 3s, then dissolve
        val blocks = placedBlocks.remove(name) ?: return
        val data = im.snakes[name]
        val blockTypes = data?.blockTypes ?: mutableListOf(Material.BONE_BLOCK)

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            dissolveBlocks(blocks.toList(), blockTypes)
        }, 60L) // 3 seconds
    }

    private fun dissolveBlocks(blocks: List<Location>, blockTypes: List<Material>) {
        for (loc in blocks) {
            // Random delay 0-2 seconds (0-40 ticks)
            val delay = (Math.random() * 40).toLong()
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (loc.block.type !in blockTypes) return@Runnable

                // Apply brief levitation to any player standing on this block
                val above = loc.clone().add(0.5, 1.0, 0.5)
                val nearbyPlayers = above.world?.getNearbyEntities(above, 0.8, 0.5, 0.8)
                    ?.filterIsInstance<Player>() ?: emptyList()
                for (player in nearbyPlayers) {
                    player.addPotionEffect(PotionEffect(
                        PotionEffectType.LEVITATION, 15, 0, false, false
                    ))
                }

                loc.block.type = Material.AIR
            }, delay)
        }
    }

    fun resetAll() {
        stopAll()
        placedBlocks.values.forEach { blocks ->
            blocks.forEach { loc ->
                loc.block.type = Material.AIR
            }
        }
        placedBlocks.clear()
        currentIndex.clear()
        routeCache.clear()
    }
}
