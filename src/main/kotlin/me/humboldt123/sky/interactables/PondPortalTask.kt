package me.humboldt123.sky.interactables

import me.humboldt123.sky.Sky
import org.bukkit.scheduler.BukkitRunnable

class PondPortalTask(private val plugin: Sky) {

    private var currentStopIndex = 0
    private var task: BukkitRunnable? = null

    fun start() {
        task = object : BukkitRunnable() {
            override fun run() {
                currentStopIndex++
            }
        }
        task!!.runTaskTimer(plugin, 100L, 100L) // every 5 seconds
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    fun getCurrentStopIndex(): Int = currentStopIndex

    fun reset() {
        currentStopIndex = 0
    }
}
