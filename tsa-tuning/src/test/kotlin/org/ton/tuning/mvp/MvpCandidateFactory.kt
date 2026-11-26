package org.ton.tuning.mvp

import org.usvm.machine.TvmCvc5OptionInventory
import org.usvm.machine.TvmCvc5OptionInventoryEntry
import org.usvm.machine.TvmCvc5ReplayCandidate
import kotlin.random.Random

/**
 * Builds cvc5 option candidates from the option inventory of the loaded native library.
 *
 * Only options that exist in this exact build are proposed, so a candidate can never name an
 * unknown option. Boolean and categorical options are used because their values are accepted by
 * cvc5 string setters without an external domain description.
 */
class MvpCandidateFactory(
    inventory: TvmCvc5OptionInventory,
    desiredOptionNames: List<String>,
    private val random: Random,
) {
    private val byName = inventory.options.associateBy(TvmCvc5OptionInventoryEntry::name)

    val options: List<TvmCvc5OptionInventoryEntry> =
        desiredOptionNames
            .mapNotNull(byName::get)
            .filter { it.kind == BOOLEAN_KIND || it.kind == CATEGORICAL_KIND }
            .filter { it.defaultValue != null }
            .filter { it.name !in TvmCvc5ReplayCandidate.PROTECTED_OPTIONS }

    /** A behavior-neutral patch: one option set to the value it already has. */
    fun baseline(): Map<String, String> {
        val entry = options.firstOrNull() ?: error("No tunable cvc5 option is available")
        return mapOf(entry.name to requireNotNull(entry.defaultValue))
    }

    fun candidates(count: Int): List<Map<String, String>> {
        val distinct = linkedMapOf<Map<String, String>, Unit>()
        var attempts = 0
        while (distinct.size < count && attempts < count * MAX_ATTEMPTS_PER_CANDIDATE) {
            attempts++
            randomCandidate()?.let { distinct[it] = Unit }
        }
        return distinct.keys.toList()
    }

    private fun randomCandidate(): Map<String, String>? {
        if (options.isEmpty()) return null

        val chosen = mutableMapOf<String, String>()
        val pickCount = 1 + random.nextInt(minOf(MAX_OPTIONS_PER_CANDIDATE, options.size))
        options.shuffled(random).take(pickCount).forEach { entry ->
            value(entry)?.let { chosen[entry.name] = it }
        }
        return chosen.takeIf { it.isNotEmpty() }
    }

    private fun value(entry: TvmCvc5OptionInventoryEntry): String? =
        when (entry.kind) {
            BOOLEAN_KIND -> if (random.nextBoolean()) "true" else "false"
            CATEGORICAL_KIND -> entry.modes.randomOrNull(random)
            else -> null
        }

    private companion object {
        const val BOOLEAN_KIND = "boolean"
        const val CATEGORICAL_KIND = "categorical"
        const val MAX_OPTIONS_PER_CANDIDATE = 3
        const val MAX_ATTEMPTS_PER_CANDIDATE = 50
    }
}
