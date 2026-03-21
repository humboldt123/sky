package me.humboldt123.sky.interactables

import me.humboldt123.sky.Sky
import me.humboldt123.sky.util.locationFromConfigString
import me.humboldt123.sky.util.toConfigString
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

class InteractableManager(private val plugin: Sky) {

    // --- Data Classes ---

    data class SwitchData(val location: Location, val material: Material)
    data class JumpPadData(val location: Location, val activeSwitches: MutableList<String>)
    data class BounceStandData(val location: Location, val activeSwitches: MutableList<String>)
    data class WaterArcData(var pos1: Location, var pos2: Location, val activeSwitches: MutableList<String>)
    data class LavaTrapData(var pos1: Location, var pos2: Location, var trigger: Location, val activeSwitches: MutableList<String>)
    data class SnakeData(
        val points: MutableList<Location>,
        var speedTicks: Int,
        val activeSwitches: MutableList<String>,
        val blockTypes: MutableList<Material> = mutableListOf(Material.BONE_BLOCK),
        var length: Int = 5
    )
    data class PortalData(val location: Location, val activeSwitches: MutableList<String>)
    data class PondPortalData(var pos1: Location, var pos2: Location, val stops: MutableList<Location>, val activeSwitches: MutableList<String>)
    data class BellData(val location: Location, val activeSwitches: MutableList<String>)
    data class CrystalData(val location: Location, val activeSwitches: MutableList<String>)

    // --- Pending Registration ---

    data class PendingRegistration(val type: String, val name: String, val data: MutableMap<String, Any> = mutableMapOf())

    // --- Storage ---

    val switches = mutableMapOf<String, SwitchData>()
    val jumpPads = mutableMapOf<String, JumpPadData>()
    val bounceStands = mutableMapOf<String, BounceStandData>()
    val waterArcs = mutableMapOf<String, WaterArcData>()
    val lavaTraps = mutableMapOf<String, LavaTrapData>()
    val snakes = mutableMapOf<String, SnakeData>()
    val portals = mutableMapOf<String, PortalData>()
    val pondPortals = mutableMapOf<String, PondPortalData>()
    val bells = mutableMapOf<String, BellData>()
    val crystals = mutableMapOf<String, CrystalData>()

    // Ghast (singleton)
    var ghastStable: Location? = null
    val ghastSwitches = mutableListOf<String>()

    // Active switch state (persisted to config)
    var activeSwitch: String? = null

    // Pending multi-step registrations per player
    val pendingRegistrations = mutableMapOf<UUID, PendingRegistration>()

    // --- Activation Check ---

    fun isActive(activeSwitches: List<String>): Boolean {
        if (activeSwitches.isEmpty()) return true
        val current = activeSwitch ?: return false
        return current in activeSwitches
    }

    // --- Find Nearest Interactable ---

    data class NearestResult(val type: String, val name: String, val activeSwitches: MutableList<String>, val distance: Double)

    fun findNearest(from: Location): NearestResult? {
        var best: NearestResult? = null

        fun check(type: String, name: String, loc: Location, switches: MutableList<String>) {
            if (loc.world != from.world) return
            val dist = loc.distance(from)
            if (best == null || dist < best!!.distance) {
                best = NearestResult(type, name, switches, dist)
            }
        }

        for ((name, data) in jumpPads) check("jumppad", name, data.location, data.activeSwitches)
        for ((name, data) in bounceStands) check("bouncestand", name, data.location, data.activeSwitches)
        for ((name, data) in waterArcs) check("waterarc", name, data.pos1, data.activeSwitches)
        for ((name, data) in lavaTraps) check("lavatrap", name, data.pos1, data.activeSwitches)
        for ((name, data) in snakes) {
            if (data.points.isNotEmpty()) check("snake", name, data.points[0], data.activeSwitches)
        }
        for ((name, data) in portals) check("portal", name, data.location, data.activeSwitches)
        for ((name, data) in pondPortals) check("pondportal", name, data.pos1, data.activeSwitches)
        for ((name, data) in bells) check("bell", name, data.location, data.activeSwitches)
        for ((name, data) in crystals) check("crystal", name, data.location, data.activeSwitches)

        if (ghastStable != null) {
            check("ghast", "ghast", ghastStable!!, ghastSwitches)
        }

        return best
    }

    // --- Switch Usage Lookup ---

    fun findSwitchUsages(switchName: String): List<String> {
        val usages = mutableListOf<String>()
        for ((name, data) in jumpPads) if (switchName in data.activeSwitches) usages.add("jumppad:$name")
        for ((name, data) in bounceStands) if (switchName in data.activeSwitches) usages.add("bouncestand:$name")
        for ((name, data) in waterArcs) if (switchName in data.activeSwitches) usages.add("waterarc:$name")
        for ((name, data) in lavaTraps) if (switchName in data.activeSwitches) usages.add("lavatrap:$name")
        for ((name, data) in snakes) if (switchName in data.activeSwitches) usages.add("snake:$name")
        for ((name, data) in portals) if (switchName in data.activeSwitches) usages.add("portal:$name")
        for ((name, data) in pondPortals) if (switchName in data.activeSwitches) usages.add("pondportal:$name")
        for ((name, data) in bells) if (switchName in data.activeSwitches) usages.add("bell:$name")
        for ((name, data) in crystals) if (switchName in data.activeSwitches) usages.add("crystal:$name")
        if (switchName in ghastSwitches) usages.add("ghast")
        return usages
    }

    // --- Snake Route Interpolation ---

    fun interpolateRoute(waypoints: List<Location>): List<Location> {
        if (waypoints.size < 2) return waypoints.toList()
        val result = mutableListOf<Location>()
        for (i in 0 until waypoints.size - 1) {
            interpolateLine(waypoints[i], waypoints[i + 1], result, i == 0)
        }
        val last = waypoints.last()
        if (result.isEmpty() || !sameBlock(result.last(), last)) {
            result.add(Location(last.world, last.blockX.toDouble(), last.blockY.toDouble(), last.blockZ.toDouble()))
        }
        return result
    }

    private fun interpolateLine(from: Location, to: Location, result: MutableList<Location>, includeStart: Boolean) {
        val dx = to.blockX - from.blockX
        val dy = to.blockY - from.blockY
        val dz = to.blockZ - from.blockZ
        val steps = max(max(abs(dx), abs(dy)), abs(dz))
        if (steps == 0) {
            if (includeStart && (result.isEmpty() || !sameBlock(result.last(), from))) {
                result.add(Location(from.world, from.blockX.toDouble(), from.blockY.toDouble(), from.blockZ.toDouble()))
            }
            return
        }
        val startStep = if (includeStart) 0 else 1
        for (s in startStep..steps) {
            val t = s.toDouble() / steps
            val x = from.blockX + (dx * t).toInt()
            val y = from.blockY + (dy * t).toInt()
            val z = from.blockZ + (dz * t).toInt()
            val loc = Location(from.world, x.toDouble(), y.toDouble(), z.toDouble())
            if (result.isEmpty() || !sameBlock(result.last(), loc)) {
                result.add(loc)
            }
        }
    }

    private fun sameBlock(a: Location, b: Location): Boolean =
        a.blockX == b.blockX && a.blockY == b.blockY && a.blockZ == b.blockZ && a.world == b.world

    // --- Config I/O ---

    fun load() {
        val config = plugin.config

        switches.clear()
        jumpPads.clear()
        bounceStands.clear()
        waterArcs.clear()
        lavaTraps.clear()
        snakes.clear()
        portals.clear()
        pondPortals.clear()
        bells.clear()
        crystals.clear()
        ghastSwitches.clear()
        ghastStable = null

        val inter = config.getConfigurationSection("interactables") ?: return

        // Active switch (persisted)
        activeSwitch = inter.getString("activeSwitch")

        // Switches
        inter.getConfigurationSection("switches")?.let { sec ->
            for (name in sec.getKeys(false)) {
                val s = sec.getConfigurationSection(name) ?: continue
                val loc = locationFromConfigString(s.getString("location") ?: continue) ?: continue
                val mat = Material.matchMaterial(s.getString("material") ?: continue) ?: continue
                switches[name] = SwitchData(loc, mat)
            }
        }

        // Jump Pads
        loadSimple(inter, "jumppads") { name, sec ->
            val loc = locationFromConfigString(sec.getString("location") ?: return@loadSimple) ?: return@loadSimple
            jumpPads[name] = JumpPadData(loc, sec.getStringList("activeSwitches").toMutableList())
        }

        // Bounce Stands
        loadSimple(inter, "bouncestands") { name, sec ->
            val loc = locationFromConfigString(sec.getString("location") ?: return@loadSimple) ?: return@loadSimple
            bounceStands[name] = BounceStandData(loc, sec.getStringList("activeSwitches").toMutableList())
        }

        // Water Arcs
        loadSimple(inter, "waterarcs") { name, sec ->
            val p1 = locationFromConfigString(sec.getString("pos1") ?: return@loadSimple) ?: return@loadSimple
            val p2 = locationFromConfigString(sec.getString("pos2") ?: return@loadSimple) ?: return@loadSimple
            waterArcs[name] = WaterArcData(p1, p2, sec.getStringList("activeSwitches").toMutableList())
        }

        // Lava Traps
        loadSimple(inter, "lavatraps") { name, sec ->
            val p1 = locationFromConfigString(sec.getString("pos1") ?: return@loadSimple) ?: return@loadSimple
            val p2 = locationFromConfigString(sec.getString("pos2") ?: return@loadSimple) ?: return@loadSimple
            val trigger = locationFromConfigString(sec.getString("trigger") ?: return@loadSimple) ?: return@loadSimple
            lavaTraps[name] = LavaTrapData(p1, p2, trigger, sec.getStringList("activeSwitches").toMutableList())
        }

        // Snakes
        loadSimple(inter, "snakes") { name, sec ->
            val points = sec.getStringList("points").mapNotNull { locationFromConfigString(it) }.toMutableList()
            val speed = sec.getInt("speedTicks", 4)
            val blockTypes = sec.getStringList("blockTypes")
                .mapNotNull { Material.matchMaterial(it) }
                .toMutableList()
            if (blockTypes.isEmpty()) blockTypes.add(Material.BONE_BLOCK)
            val length = sec.getInt("length", 5)
            snakes[name] = SnakeData(points, speed, sec.getStringList("activeSwitches").toMutableList(), blockTypes, length)
        }

        // Portals
        loadSimple(inter, "portals") { name, sec ->
            val loc = locationFromConfigString(sec.getString("location") ?: return@loadSimple) ?: return@loadSimple
            portals[name] = PortalData(loc, sec.getStringList("activeSwitches").toMutableList())
        }

        // Pond Portals
        loadSimple(inter, "pondportals") { name, sec ->
            val p1 = locationFromConfigString(sec.getString("pos1") ?: return@loadSimple) ?: return@loadSimple
            val p2 = locationFromConfigString(sec.getString("pos2") ?: return@loadSimple) ?: return@loadSimple
            val stops = sec.getStringList("stops").mapNotNull { locationFromConfigString(it) }.toMutableList()
            pondPortals[name] = PondPortalData(p1, p2, stops, sec.getStringList("activeSwitches").toMutableList())
        }

        // Bells
        loadSimple(inter, "bells") { name, sec ->
            val loc = locationFromConfigString(sec.getString("location") ?: return@loadSimple) ?: return@loadSimple
            bells[name] = BellData(loc, sec.getStringList("activeSwitches").toMutableList())
        }

        // Crystals
        loadSimple(inter, "crystals") { name, sec ->
            val loc = locationFromConfigString(sec.getString("location") ?: return@loadSimple) ?: return@loadSimple
            crystals[name] = CrystalData(loc, sec.getStringList("activeSwitches").toMutableList())
        }

        // Ghast
        config.getConfigurationSection("ghast")?.let { sec ->
            ghastStable = sec.getString("stable")?.let { locationFromConfigString(it) }
            ghastSwitches.addAll(sec.getStringList("activeSwitches"))
        }
    }

    fun save() {
        val config = plugin.config

        // Clear old interactables section
        config.set("interactables", null)

        // Active switch
        config.set("interactables.activeSwitch", activeSwitch)

        // Switches
        for ((name, data) in switches) {
            config.set("interactables.switches.$name.location", data.location.toConfigString())
            config.set("interactables.switches.$name.material", data.material.name)
        }

        // Jump Pads
        for ((name, data) in jumpPads) {
            config.set("interactables.jumppads.$name.location", data.location.toConfigString())
            config.set("interactables.jumppads.$name.activeSwitches", data.activeSwitches)
        }

        // Bounce Stands
        for ((name, data) in bounceStands) {
            config.set("interactables.bouncestands.$name.location", data.location.toConfigString())
            config.set("interactables.bouncestands.$name.activeSwitches", data.activeSwitches)
        }

        // Water Arcs
        for ((name, data) in waterArcs) {
            config.set("interactables.waterarcs.$name.pos1", data.pos1.toConfigString())
            config.set("interactables.waterarcs.$name.pos2", data.pos2.toConfigString())
            config.set("interactables.waterarcs.$name.activeSwitches", data.activeSwitches)
        }

        // Lava Traps
        for ((name, data) in lavaTraps) {
            config.set("interactables.lavatraps.$name.pos1", data.pos1.toConfigString())
            config.set("interactables.lavatraps.$name.pos2", data.pos2.toConfigString())
            config.set("interactables.lavatraps.$name.trigger", data.trigger.toConfigString())
            config.set("interactables.lavatraps.$name.activeSwitches", data.activeSwitches)
        }

        // Snakes
        for ((name, data) in snakes) {
            config.set("interactables.snakes.$name.points", data.points.map { it.toConfigString() })
            config.set("interactables.snakes.$name.speedTicks", data.speedTicks)
            config.set("interactables.snakes.$name.activeSwitches", data.activeSwitches)
            config.set("interactables.snakes.$name.blockTypes", data.blockTypes.map { it.name })
            config.set("interactables.snakes.$name.length", data.length)
        }

        // Portals
        for ((name, data) in portals) {
            config.set("interactables.portals.$name.location", data.location.toConfigString())
            config.set("interactables.portals.$name.activeSwitches", data.activeSwitches)
        }

        // Pond Portals
        for ((name, data) in pondPortals) {
            config.set("interactables.pondportals.$name.pos1", data.pos1.toConfigString())
            config.set("interactables.pondportals.$name.pos2", data.pos2.toConfigString())
            config.set("interactables.pondportals.$name.stops", data.stops.map { it.toConfigString() })
            config.set("interactables.pondportals.$name.activeSwitches", data.activeSwitches)
        }

        // Bells
        for ((name, data) in bells) {
            config.set("interactables.bells.$name.location", data.location.toConfigString())
            config.set("interactables.bells.$name.activeSwitches", data.activeSwitches)
        }

        // Crystals
        for ((name, data) in crystals) {
            config.set("interactables.crystals.$name.location", data.location.toConfigString())
            config.set("interactables.crystals.$name.activeSwitches", data.activeSwitches)
        }

        // Ghast
        config.set("ghast", null)
        if (ghastStable != null) {
            config.set("ghast.stable", ghastStable!!.toConfigString())
        }
        config.set("ghast.activeSwitches", ghastSwitches)

        plugin.saveConfig()
    }

    private fun loadSimple(parent: ConfigurationSection, key: String, loader: (String, ConfigurationSection) -> Unit) {
        val sec = parent.getConfigurationSection(key) ?: return
        for (name in sec.getKeys(false)) {
            val child = sec.getConfigurationSection(name) ?: continue
            loader(name, child)
        }
    }

    // --- Cuboid Utility ---

    fun forEachBlockInRegion(pos1: Location, pos2: Location, action: (Location) -> Unit) {
        val world = pos1.world ?: return
        val minX = minOf(pos1.blockX, pos2.blockX)
        val maxX = maxOf(pos1.blockX, pos2.blockX)
        val minY = minOf(pos1.blockY, pos2.blockY)
        val maxY = maxOf(pos1.blockY, pos2.blockY)
        val minZ = minOf(pos1.blockZ, pos2.blockZ)
        val maxZ = maxOf(pos1.blockZ, pos2.blockZ)
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    action(Location(world, x.toDouble(), y.toDouble(), z.toDouble()))
                }
            }
        }
    }

    fun isInRegion(loc: Location, pos1: Location, pos2: Location): Boolean {
        if (loc.world != pos1.world) return false
        val minX = minOf(pos1.blockX, pos2.blockX)
        val maxX = maxOf(pos1.blockX, pos2.blockX)
        val minY = minOf(pos1.blockY, pos2.blockY)
        val maxY = maxOf(pos1.blockY, pos2.blockY)
        val minZ = minOf(pos1.blockZ, pos2.blockZ)
        val maxZ = maxOf(pos1.blockZ, pos2.blockZ)
        return loc.blockX in minX..maxX && loc.blockY in minY..maxY && loc.blockZ in minZ..maxZ
    }

    // --- Reset ---

    fun resetAll() {
        activeSwitch = null
        pendingRegistrations.clear()
    }
}
