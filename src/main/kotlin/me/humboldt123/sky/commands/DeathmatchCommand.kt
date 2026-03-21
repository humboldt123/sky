package me.humboldt123.sky.commands

import me.humboldt123.sky.Sky
import me.humboldt123.sky.util.alivePlayers
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import java.util.concurrent.ThreadLocalRandom

class DeathmatchCommand(private val plugin: Sky) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val center = plugin.configManager.getDeathmatchCenter()
        if (center == null) {
            sender.sendMessage("§c[Sky] deathmatch-center not set! Use /sky set deathmatch-center")
            return true
        }

        val alive = alivePlayers
        if (alive.isEmpty()) {
            sender.sendMessage("§cNo alive players to teleport.")
            return true
        }

        val random = ThreadLocalRandom.current()
        for (player in alive) {
            val dest = findSafeSpawn(center, random)
            player.teleport(dest)
        }

        Bukkit.broadcast(net.kyori.adventure.text.Component.text("§eDeathmatch! All alive players teleported!"))
        return true
    }
    
    private fun findSafeSpawn(center: Location, random: ThreadLocalRandom): Location {
        val world = center.world!!

        data class Candidate(val loc: Location, val score: Int)
        val candidates = mutableListOf<Candidate>()

        for (attempt in 0 until 30) {
            val xInt = (center.x + random.nextDouble(-5.0, 5.0)).toInt()
            val zInt = (center.z + random.nextDouble(-5.0, 5.0)).toInt()

            // Scan downward to find valid "floor + 2 air blocks"
            for (y in world.maxHeight downTo world.minHeight) {
                val ground = world.getBlockAt(xInt, y, zInt)
                val above1 = world.getBlockAt(xInt, y + 1, zInt)
                val above2 = world.getBlockAt(xInt, y + 2, zInt)

                // Must be solid ground with 2 air blocks above
                if (!ground.type.isSolid) continue
                if (!above1.type.isAir || !above2.type.isAir) continue

                // Avoid spawning on roofs (prefer enclosed spaces)
                val highestY = world.getHighestBlockYAt(xInt, zInt)
                val exposedToSky = highestY <= y + 2
                if (exposedToSky) continue

                val score = scoreBlock(ground.type)

                val loc = Location(
                    world,
                    xInt + 0.5,
                    (y + 1).toDouble(),
                    zInt + 0.5,
                    center.yaw,
                    center.pitch
                )

                candidates.add(Candidate(loc, score))

                // If it's a strong block (like structure material), use immediately
                if (score >= 3) return loc
            }
        }

        // Pick best candidate
        if (candidates.isNotEmpty()) {
            return candidates.maxByOrNull { it.score }!!.loc
        }

        // Fallback: center column, but still try to find interior
        val xInt = center.blockX
        val zInt = center.blockZ

        for (y in world.maxHeight downTo world.minHeight) {
            val ground = world.getBlockAt(xInt, y, zInt)
            val above1 = world.getBlockAt(xInt, y + 1, zInt)
            val above2 = world.getBlockAt(xInt, y + 2, zInt)

            if (ground.type.isSolid && above1.type.isAir && above2.type.isAir) {
                return Location(world, xInt + 0.5, (y + 1).toDouble(), zInt + 0.5, center.yaw, center.pitch)
            }
        }

        // Absolute worst fallback (should almost never happen)
        return center.clone()
    }

    // Higher = better
    private fun scoreBlock(type: Material): Int {
        // Building blocks: stone, bricks, planks, etc. 
        if (type.name.contains("STAIR") || type.name.contains("SLAB")) {
            return 0;
        }
        if (type.name.contains("BRICK") || type.name.contains("PLANK") ||
            type.name.contains("STONE") || type.name.contains("CONCRETE") ||
            type.name.contains("TERRACOTTA") || type.name.contains("QUARTZ") ||
            type.name.contains("DEEPSLATE") || type.name.contains("SANDSTONE") ||
            type == Material.SMOOTH_STONE || type == Material.COBBLESTONE ||
            type == Material.OBSIDIAN || type == Material.IRON_BLOCK) {
            return 3
        }
        // Natural blocks: grass, dirt, sand, etc.
        if (type == Material.GRASS_BLOCK || type == Material.DIRT ||
            type == Material.SAND || type == Material.GRAVEL ||
            type == Material.PODZOL || type == Material.MYCELIUM ||
            type == Material.MUD || type == Material.CLAY ||
            type == Material.SNOW_BLOCK || type == Material.ICE) {
            return 2
        }
        // Anything else solid
        return 1
    }
}
