package me.bigratenthusiast.crypt.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

fun Player.clearFully() {
    inventory.clear()
    activePotionEffects.forEach { removePotionEffect(it.type) }
    gameMode = GameMode.ADVENTURE
    health = 20.0
    foodLevel = 20
    saturation = 20f
    fireTicks = 0
    fallDistance = 0f
    exp = 0f
    level = 0
}

fun Player.sendActionBarMsg(message: String) {
    sendActionBar(net.kyori.adventure.text.Component.text(message))
}

fun Location.toConfigString(): String {
    return "${world?.name}:$blockX:$blockY:$blockZ"
}

fun locationFromConfigString(str: String): Location? {
    val parts = str.split(":")
    if (parts.size != 4) return null
    val world = Bukkit.getWorld(parts[0]) ?: return null
    val x = parts[1].toIntOrNull() ?: return null
    val y = parts[2].toIntOrNull() ?: return null
    val z = parts[3].toIntOrNull() ?: return null
    return Location(world, x + 0.5, y.toDouble(), z + 0.5)
}

fun ConfigurationSection.getLocationString(key: String): Location? {
    val str = getString(key) ?: return null
    return locationFromConfigString(str)
}

val alivePlayers: List<Player>
    get() = Bukkit.getOnlinePlayers()
        .filter { it.gameMode == GameMode.SURVIVAL }
        .toList()

/**
 * Create a non-italic display name component. Plugin-set names use italic=false
 * so anvil-renamed items (which are always italic) can't spoof ability items.
 */
fun pluginName(text: String): Component {
    return Component.text(text).decoration(TextDecoration.ITALIC, false)
}

/**
 * Check if an item has a display name containing the given text.
 * Rejects items where italic is explicitly TRUE (anvil renames render italic).
 * Accepts plugin-created (italic=FALSE) and ie-renamed (italic=NOT_SET) items.
 * TODO: use PDC tags for definitive item identification when kit system is built
 */
fun ItemStack.hasPluginName(contains: String): Boolean {
    val meta = itemMeta ?: return false
    if (!meta.hasDisplayName()) return false
    val displayName = meta.displayName() ?: return false
    if (displayName.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE) return false
    val plain = PlainTextComponentSerializer.plainText().serialize(displayName)
    return plain.contains(contains, ignoreCase = true)
}
