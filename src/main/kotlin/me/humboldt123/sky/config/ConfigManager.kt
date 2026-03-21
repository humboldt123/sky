package me.humboldt123.sky.config

import me.humboldt123.sky.Sky
import me.humboldt123.sky.util.locationFromConfigString
import me.humboldt123.sky.util.toConfigString
import org.bukkit.Location

class ConfigManager(private val plugin: Sky) {

    // Named locations: lobby-spawn, ghast-stable, deathmatch-center
    private val locations = mutableMapOf<String, Location>()

    // Spawn points list
    private val spawnPoints = mutableListOf<Location>()

    val validLocationKeys = setOf("lobby-spawn", "ghast-stable", "deathmatch-center")

    fun load() {
        plugin.saveDefaultConfig()
        plugin.reloadConfig()
        val config = plugin.config

        // Load named locations
        locations.clear()
        val locSection = config.getConfigurationSection("locations")
        if (locSection != null) {
            for (key in locSection.getKeys(false)) {
                val str = locSection.getString(key) ?: continue
                val loc = locationFromConfigString(str)
                if (loc != null) {
                    locations[key] = loc
                } else {
                    plugin.logger.warning("Invalid location for key '$key': $str")
                }
            }
        }

        // Load spawn points
        spawnPoints.clear()
        val spawnList = config.getStringList("spawnPoints")
        for (str in spawnList) {
            val loc = locationFromConfigString(str)
            if (loc != null) {
                spawnPoints.add(loc)
            } else {
                plugin.logger.warning("Invalid spawn point: $str")
            }
        }
    }

    fun save() {
        val config = plugin.config

        // Save named locations
        for ((key, loc) in locations) {
            config.set("locations.$key", loc.toConfigString())
        }

        // Save spawn points
        config.set("spawnPoints", spawnPoints.map { it.toConfigString() })

        plugin.saveConfig()
    }

    fun getLocation(key: String): Location? = locations[key]

    fun setLocation(key: String, location: Location) {
        locations[key] = location
        save()
    }

    fun getLobbySpawn(): Location? = locations["lobby-spawn"]
    fun getDeathmatchCenter(): Location? = locations["deathmatch-center"]
    fun getGhastStable(): Location? = locations["ghast-stable"]

    fun getSpawnPoints(): List<Location> = spawnPoints.toList()

    fun addSpawnPoint(location: Location) {
        spawnPoints.add(location)
        save()
    }

    fun removeSpawnPoint(index: Int): Boolean {
        if (index < 0 || index >= spawnPoints.size) return false
        spawnPoints.removeAt(index)
        save()
        return true
    }
}
