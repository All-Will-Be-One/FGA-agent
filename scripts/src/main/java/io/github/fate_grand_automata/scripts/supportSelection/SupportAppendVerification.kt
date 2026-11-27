package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.lib_automata.Location
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/** Bound the game's Normal -> Append -> Both cycle and prove the view before using it. */
internal class AppendDisplayCycle(
    private val read: () -> View,
    private val tapAndWait: () -> Unit,
    private val waitForFrame: () -> Unit
) {
    enum class View { Normal, Append, Both, Unknown }

    private fun stable(expected: View? = null): View {
        var previous = View.Unknown
        repeat(6) {
            val now = read()
            if (now != View.Unknown && now == previous && (expected == null || now == expected))
                return now
            previous = now
            waitForFrame()
        }
        return View.Unknown
    }

    fun normal() {
        when (stable()) {
            View.Normal -> return
            View.Append -> repeat(2) { tapAndWait() }
            View.Both -> tapAndWait()
            View.Unknown -> unverified()
        }
        if (stable(View.Normal) != View.Normal) unverified()
    }

    fun checkAppend(inspect: () -> Boolean): Boolean {
        normal()
        tapAndWait()
        if (stable(View.Append) != View.Append) unverified()
        val result = inspect()
        repeat(2) { tapAndWait() }
        if (stable(View.Normal) != View.Normal) unverified()
        return result
    }

    private fun unverified(): Nothing =
        throw AutoBattle.BattleExitException(AutoBattle.ExitReason.SupportSkillDisplayUnverified)
}

@ScriptScope
class SupportAppendVerification @Inject constructor(
    api: IFgoAutomataApi,
    private val boundsFinder: SupportBoundsFinder
) : IFgoAutomataApi by api {
    private val cycle = AppendDisplayCycle(
        read = ::mode,
        tapAndWait = {
            locations.support.skillDisplaySwitchClick.click()
            0.35.seconds.wait()
        },
        waitForFrame = { 0.18.seconds.wait() }
    )

    private fun mode(): AppendDisplayCycle.View = useColor {
        useSameSnapIn {
            val region = locations.support.skillDisplayLabelsRegion
            val normal = region.exists(images[Images.SupportNormalSkills], similarity = 0.88)
            val append = region.exists(images[Images.SupportAppendSkills], similarity = 0.88)
            when {
                normal && append -> AppendDisplayCycle.View.Both
                normal -> AppendDisplayCycle.View.Normal
                append -> AppendDisplayCycle.View.Append
                else -> AppendDisplayCycle.View.Unknown
            }
        }
    }

    fun ensureNormal() = cycle.normal()

    fun check(
        original: SupportBounds,
        slots: Set<String>,
        unlockedSlots: Set<String>,
        normalMatch: (SupportBounds) -> Region?
    ): Region? {
        val required = slots.map { it.toIntOrNull()?.takeIf { n -> n in 1..5 } ?: return null }
        val unlocked = unlockedSlots.map { it.toIntOrNull()?.takeIf { n -> n in 1..5 } ?: return null }
        if ((required.isEmpty() && unlocked.isEmpty()) || !locations.support.fullyVisibleAppendRow(original.region)) return null
        val identity = useSameSnapIn {
            normalMatch(original)?.let {
                locations.support.appendRowIdentity(original.region).getPattern("Support candidate")
            }
        } ?: return null

        return identity.use {
            val colorIdentity = if (unlocked.isNotEmpty()) useColor {
                useSameSnapIn { locations.support.appendRowIdentity(original.region).getPattern("Support candidate color") }
            } else null
            val passed = colorIdentity.use {
                cycle.checkAppend {
                    val maxMatches = useSameSnapIn {
                        identifiedRow(original, identity)?.let { row ->
                            required.all { slot ->
                                locations.support.appendLevelRegion(row.region, slot)
                                    .exists(images[Images.SkillTen], similarity = 0.68)
                            }
                        } == true
                    }
                    maxMatches && (colorIdentity == null || useColor {
                        useSameSnapIn {
                            identifiedRow(original, colorIdentity)?.let { row ->
                                unlocked.all { slot ->
                                    locations.support.appendIconBody(row.region, slot).getPattern("Append icon").use {
                                        it.width >= 20 && it.height >= 14 && it.brightSaturatedFraction() >= 0.20
                                    }
                                }
                            } == true
                        }
                    })
                }
            }
            if (!passed) null
            else useSameSnapIn { identifiedRow(original, identity)?.let(normalMatch) }
        }
    }

    private fun identifiedRow(original: SupportBounds, identity: Pattern): SupportBounds? =
        boundsFinder.all()
            .singleOrNull { abs(it.region.y - original.region.y) <= 8 }
            ?.takeIf { row ->
                locations.support.fullyVisibleAppendRow(row.region) &&
                    locations.support.appendRowIdentity(row.region).let { region ->
                        (region.copy(width = region.width + 8, height = region.height + 8) - Location(4, 4))
                            .exists(identity, similarity = 0.96)
                    }
            }
}
