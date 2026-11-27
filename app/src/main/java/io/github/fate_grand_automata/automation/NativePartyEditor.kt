package io.github.fate_grand_automata.automation

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.fate_grand_automata.accessibility.AccessibilityGestures
import io.github.fate_grand_automata.imaging.DroidCvPattern
import io.github.lib_automata.GameAreaManager
import io.github.lib_automata.Location
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.lib_automata.ScreenshotService
import io.github.lib_automata.Size
import io.github.lib_automata.dagger.ScriptScope
import io.github.fga.automation.RpcFailure
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Formation-only JP editor. Its sole write target is XV. It observes the reference preset
 * through the ordinary UI, reconstructs XV using the inventory and reads the saved roster
 * back. It never calls AutoBattle, the game's copy/auto-formation commands or a transfer.
 */
@ScriptScope
class NativePartyEditor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val screenshots: ScreenshotService,
    private val area: GameAreaManager,
    private val gestures: AccessibilityGestures
) {
    // The wide JP layout is described in 1100x495 design units; both capture and touch
    // are transformed against the same measured game rectangle.
    private val window get() = area.gameArea
    private val scale = 720.0 / 495
    private val centers = listOf(202, 338, 475, 623, 761, 899)
    private val gridX = listOf(220, 316, 412, 508, 604, 700, 796, 892)
    private val assets = mutableMapOf<String, Pattern>()
    private var deadline = 0L
    private lateinit var report: (String) -> Unit
    private lateinit var ownsRunner: () -> Boolean
    private var writeStarted = false
    private var saved = false

    private fun px(v: Int) = (v * scale).roundToInt()
    private fun box(x: Int, y: Int, w: Int, h: Int) = Region(px(x), px(y), px(w), px(h))
    private fun position(x: Int, y: Int): Location =
        Location(window.x + (x / 1100.0 * window.width).roundToInt(), window.y + (y / 495.0 * window.height).roundToInt())

    private fun fail(reason: String): Nothing = throw RpcFailure(
        if (saved) "PartySaveNeedsVerification" else if (writeStarted) "PartyEditInterrupted" else "PartyPreflightFailed",
        reason
    )

    private fun checkTime() {
        if (!ownsRunner()) fail("Party rebuild was stopped")
        if (SystemClock.elapsedRealtime() >= deadline) fail("Party rebuild timed out")
    }
    private fun frame(): Pattern {
        checkTime()
        return screenshots.takeScreenshot().resize(Size(1601, 720))
    }

    private fun template(name: String): Pattern = assets.getOrPut(name) {
        context.assets.open("Jp/party-editor/$name.png").use { DroidCvPattern(it, false, name) }
    }

    private fun Pattern.has(name: String, roi: Region, score: Double = .88) =
        crop(roi).use { it.findBestMatch(template(name), score) != null }
    private fun Pattern.formation() = has("formation", box(805, 0, 241, 57))
    private fun Pattern.servants() = has("servant", box(724, 0, 325, 56))
    private fun Pattern.equipment() = has("equipment", box(776, 0, 270, 56))
    private fun Pattern.arrangement() = has("arrange-hint", box(312, 401, 408, 25))
    private fun Pattern.emptySlot(i: Int) =
        has("empty", box(centers[i] - 52, 175, 104, 34))
    private fun Pattern.emptySavePrompt() = has("empty-save", box(373, 43, 355, 47))
    private fun Pattern.onFormationMenu() = has("formation-menu", box(744, 84, 218, 60))
    private fun Pattern.party(): Int? {
        val on = (0..14).filter { i ->
            has("dot", box(422 + (17.2 * i).roundToInt(), 23, 16, 18), .85)
        }
        return on.singleOrNull()?.plus(1)
    }
    private fun Pattern.support(): Int? = centers.indices.singleOrNull {
        has("support", box(centers[it] - 54, 174, 110, 35))
    }
    private fun Pattern.unequipped(i: Int) =
        has("no-ce", box(centers[i] - 22, 289, 48, 49), .82)
    private fun Pattern.match(target: Pattern, roi: Region, threshold: Double) =
        crop(roi).use { it.findBestMatch(target, threshold)?.let { m ->
            Triple(m.score, m.region.center.x + roi.x, m.region.center.y + roi.y)
        } }
    private fun tap(x: Int, y: Int) {
        checkTime()
        gestures.click(position(x, y), 1)
    }

    private fun drag(x1: Int, y1: Int, x2: Int, y2: Int) {
        checkTime()
        runBlocking { gestures.swipe7(position(x1, y1), position(x2, y2)) }
    }

    private fun awaitOrNull(attempts: Int = 20, test: Pattern.() -> Boolean): Pattern? {
        repeat(attempts) {
            val f = frame()
            if (f.test()) return f
            f.close()
            Thread.sleep(220)
        }
        return null
    }

    private fun await(label: String, test: Pattern.() -> Boolean): Pattern =
        awaitOrNull(test = test) ?: fail("Expected $label")

    private fun goTo(preset: Int) {
        frame().use {
            if (!it.formation() || it.party() == null) fail("Open the JP Party Formation page first")
            if (it.party() == preset) return
        }
        tap(430 + (17.2 * (preset - 1)).roundToInt(), 32)
        Thread.sleep(800)
        await("preset $preset") { formation() && party() == preset }.close()
    }

    private fun selectTab() {
        await("Craft Essence filter") { equipment() &&
            (has("all", box(635, 66, 151, 46)) || has("event", box(629, 66, 159, 46))) }.use {
            if (it.has("all", box(635, 66, 151, 46))) {
                tap(710, 90)
                await("Event Bonus filter") { equipment() && has("event", box(629, 66, 159, 46)) }.close()
            }
        }
    }

    private fun ceFilterEvent(f: Pattern) = f.has("event", box(629, 66, 159, 46))
    private fun toggleFilter(event: Boolean) {
        val name = if (event) "event" else "all"
        tap(710, 90)
        await(if (event) "Event Bonus filter" else "ALL filter") {
            equipment() && has(name, box(629, 66, 159, 46))
        }.close()
    }
    private fun restoreFilter(wasEvent: Boolean) {
        frame().use { if (it.equipment() && ceFilterEvent(it) != wasEvent) toggleFilter(wasEvent) }
    }
    private fun resetScroll(ce: Boolean) {
        // The JP picker has an independently draggable scrollbar; swiping a header
        // or the formation itself would be unsafe.
        val top = if (ce) 252 else 129
        val y = (top + 485) / 2
        drag(1013, y, 1013, top + 2)
        Thread.sleep(450)
    }

    private data class Member(
        val slot: Int,
        val portrait: Pattern,
        val stats: Pattern,
        val essences: List<Pattern>,
        val previewName: Pattern?,
        val previewAtk: Pattern?,
        val previewCost: Pattern?
    ) : AutoCloseable {
        override fun close() {
            portrait.close()
            stats.close()
            essences.forEach { it.close() }
            previewName?.close()
            previewAtk?.close()
            previewCost?.close()
        }
    }

    private fun portraitBox(slot: Int) = box(centers[slot] - 25, 144, 50, 49)
    private fun ceBox(slot: Int) = box(centers[slot] - 31, 285, 62, 41)
    private fun captureReference(preset: Int, members: MutableList<Member>): Int {
        goTo(preset)
        Thread.sleep(1000)
        val support = await("stable reference formation") { formation() && party() == preset && support() != null }.use { it.support()!! }
        for (slot in centers.indices.filter { it != support }) {
            await("portrait at reference position ${slot + 1}") { formation() && party() == preset }.use { view ->
                if (view.emptySlot(slot)) return@use
                val portrait = view.crop(portraitBox(slot))
                val stats = view.crop(box(centers[slot] - 24, 361, 82, 40))
                // Independently compare three horizontal bands; the picker adds level/cost text.
                if (view.unequipped(slot)) {
                    members += Member(slot, portrait, stats, emptyList(), null, null, null)
                    return@use
                }
                val art = listOf(284, 294, 303).map { y -> view.crop(box(centers[slot] - 28, y, 56, 20)) }
                tap(centers[slot], 313)  // source inspection only
                await("reference Craft Essence opens") { equipment() }.close()
                Thread.sleep(550)
                await("stable reference Craft Essence details") { equipment() }.use { picker ->
                    members += Member(slot, portrait, stats, art,
                        picker.crop(box(302, 132, 240, 27)),
                        picker.crop(box(880, 150, 68, 19)),
                        picker.crop(box(769, 197, 57, 21)))
                }
                tap(126, 29)  // close, never confirm on the read-only preset
                await("reference formation after inspection") { formation() && party() == preset }.close()
            }
        }
        return support
    }

    private fun portraitMatches(f: Pattern, m: Member) =
        f.match(m.portrait, box(centers[m.slot] - 31, 138, 62, 61), .90) != null

    private fun rosterMatches(f: Pattern, members: List<Member>, support: Int): Boolean {
        val empty = centers.indices.filter { i -> i != support && members.none { it.slot == i } }
        return f.support() == support && empty.all { f.emptySlot(it) } && members.all { m ->
            portraitMatches(f, m) &&
                f.match(m.stats, box(centers[m.slot] - 30, 355, 94, 52), .979) != null &&
                if (m.essences.isEmpty()) f.unequipped(m.slot)
                else m.essences.count { f.match(it, ceBox(m.slot), .92) != null } >= 2
        }
    }

    private data class Candidate(val x: Int, val y: Int, val score: Double, val votes: Int)

    private fun candidates(f: Pattern, images: List<Pattern>, ce: Boolean): List<Candidate> {
        val y0 = if (ce) 248 else 132
        val roi = box(177, y0, 762, 491 - y0)
        val hits = mutableListOf<Triple<Int, Int, Pair<Int, Double>>>()
        images.forEachIndexed { i, art ->
            for (factor in listOf(.56, .59, .615, .64, .67, .70, .74)) {
                art.resize(Size((art.width * factor).roundToInt(), (art.height * factor).roundToInt())).use { small ->
                    f.crop(roi).use { grid ->
                        grid.findMatches(small, if (ce) .85 else .87).toList().forEach { m ->
                            val x = ((m.region.center.x + roi.x) / scale).roundToInt()
                            val y = ((m.region.center.y + roi.y) / scale).roundToInt()
                            val cx = gridX.minBy { abs(x - it) }
                            if (abs(x - cx) <= 28) hits += Triple(cx, y, i to m.score)
                        }
                    }
                }
            }
        }
        val result = mutableListOf<Candidate>()
        for ((x, y, v) in hits.sortedByDescending { it.third.second }) {
            if (result.any { it.x == x && abs(it.y - y) < 42 }) continue
            val votes = hits.filter { it.first == x && abs(it.second - y) < 36 }.map { it.third.first }.distinct().size
            if ((votes >= 2 && v.second >= .90) || (ce && v.second >= .954)) result += Candidate(x, y, v.second, votes)
        }
        return result
    }

    private fun scroll(ce: Boolean) {
        drag(950, 450, 950, if (ce) 277 else 175)
        Thread.sleep(430)
    }

    private fun chooseServant(m: Member) {
        await("reserved XV before servant ${m.slot + 1}") { formation() && party() == 15 }.use {
            if (portraitMatches(it, m)) return
        }
        tap(centers[m.slot], 175)
        val picker = awaitOrNull(7) { servants() }
        if (picker == null) {
            // The game paints the roster before the post-save transition accepts touch.
            // Retry only when the exact same reserved roster and empty slot are still visible.
            await("stable XV empty slot after ignored tap") {
                formation() && party() == 15 && emptySlot(m.slot)
            }.close()
            tap(centers[m.slot], 175)
            await("Servant Selection") { servants() }.close()
        } else picker.close()
        val patches = listOf(
            m.portrait.crop(box(4, 9, 42, 25)),
            m.portrait.crop(box(3, 19, 43, 24))
        )
        try {
            resetScroll(false)
            repeat(32) { page ->
                val matches = await("Servant Selection inventory") { servants() }.use { candidates(it, patches, false) }
                val best = matches.firstOrNull()
                if (best != null && (matches.size == 1 || best.score - matches[1].score >= .028)) {
                    tap(best.x, best.y)
                    await("XV servant ${m.slot + 1} portrait after selection") {
                        formation() && party() == 15 && portraitMatches(this, m)
                    }.close()
                    return
                }
                report("searching servant ${m.slot + 1}, page ${page + 1}, best=${best?.score}")
                scroll(false)
            }
        } finally { patches.forEach { it.close() } }
        fail("Could not uniquely identify the reference servant for slot ${m.slot + 1}")
    }

    private fun previewMatches(f: Pattern, m: Member): Boolean {
        val name = f.match(requireNotNull(m.previewName), box(298, 128, 248, 35), .0)?.first ?: 0.0
        val atk = f.match(requireNotNull(m.previewAtk), box(875, 146, 77, 26), .0)?.first ?: 0.0
        val cost = f.match(requireNotNull(m.previewCost), box(765, 192, 65, 30), .0)?.first ?: 0.0
        Timber.d("Party preview slot=${m.slot + 1} name=$name atk=$atk cost=$cost")
        return name >= .96 && atk >= .94 && cost >= .94
    }

    private fun chooseEquipment(m: Member) {
        if (m.essences.isEmpty()) return
        await("reserved XV with matching servant before Craft Essence") {
            formation() && party() == 15 && portraitMatches(this, m)
        }.close()
        tap(centers[m.slot], 313)
        val wasEvent = await("Craft Essence Selection") { equipment() }.use { ceFilterEvent(it) }
        try {
            selectTab()
            for (eventOnly in listOf(true, false)) {
                if (!eventOnly) toggleFilter(false)
                resetScroll(true)
                repeat(if (eventOnly) 6 else 38) { page ->
                    val options = await("Craft Essence inventory") { equipment() }.use { candidates(it, m.essences, true) }
                    for (candidate in options) {
                        // Equipped copies are not transferable: neither this flag nor an
                        // unexpected confirmation after the chooser may be bypassed.
                        val occupied = frame().use {
                            !it.equipment() || it.has("equipped",
                                box(candidate.x - 37, (candidate.y - 10).coerceAtLeast(248), 74, 51), .81)
                        }
                        if (occupied) continue
                        tap(candidate.x, candidate.y)
                        Thread.sleep(530)
                        val correct = frame().use { it.equipment() && previewMatches(it, m) }
                        if (!correct) continue
                        restoreFilter(wasEvent)
                        // Changing filter may reset the pending candidate; verify after it.
                        if (!frame().use { it.equipment() && previewMatches(it, m) })
                            fail("Craft Essence selection reset on filter restore at slot ${m.slot + 1}")
                        tap(888, 208)
                        await("XV equipped slot ${m.slot + 1}; transfers forbidden") {
                            formation() && party() == 15 &&
                                m.essences.count { match(it, ceBox(m.slot), .92) != null } >= 2
                        }.close()
                        return
                    }
                    report("searching Craft Essence ${m.slot + 1}, event=$eventOnly, page ${page + 1}, candidates=${options.size}")
                    scroll(true)
                }
            }
        } finally { restoreFilter(wasEvent) }
        fail("Could not find an unassigned Craft Essence matching reference identity and stats for slot ${m.slot + 1}")
    }

    fun rebuild(reference: Int, canContinue: () -> Boolean, progress: (String) -> Unit) {
        require(reference in 1..14)
        deadline = SystemClock.elapsedRealtime() + 300_000
        report = progress
        ownsRunner = canContinue
        val members = mutableListOf<Member>()
        try {
            if (abs(window.width.toDouble() / window.height - 1100.0 / 495) > .012 || window.x != 0 || window.y != 0)
                fail("Unsupported JP formation aspect ratio or game bounds")
            await("reserved Party XV on the JP formation screen") { formation() && party() == 15 }.close()
            report("capturing reference preset $reference")
            val support = captureReference(reference, members)
            goTo(15)
            if (members.count { it.slot < 3 } != 2) fail("Reference needs two owned starters and one support slot")
            if (frame().use { rosterMatches(it, members, support) }) {
                report("XV already matches; no reconstruction performed")
                return
            }
            val alreadyBlank = frame().use { f ->
                f.formation() && f.party() == 15 && !f.arrangement() && f.support() == support &&
                    centers.indices.filter { it != support }.all { f.emptySlot(it) }
            }
            if (!alreadyBlank) {
                report("disbanding reserved XV")
                tap(306, 444)
                await("Party Edit modal for XV") { party() == 15 && has("edit-menu", box(466, 63, 169, 40)) }.close()
                tap(678, 210)
                await("Disband XV confirmation") { has("disband", box(464, 44, 175, 41)) }.close()
                writeStarted = true
                tap(686, 413)
                val currentSupport = await("empty reserved XV after disband") {
                    arrangement() && party() == 15 && support()?.let { s ->
                        centers.indices.filter { it != s }.all { emptySlot(it) }
                    } == true
                }.use { it.support()!! }
                if (currentSupport != support) {
                    report("moving support position ${currentSupport + 1} to ${support + 1}")
                    gestures.dragFormationCard(position(centers[currentSupport], 183), position(centers[support], 183))
                    await("XV support moved to position ${support + 1}") {
                        arrangement() && party() == 15 && support() == support
                    }.close()
                }
                report("leaving XV's arrangement mode")
                tap(985, 441)
                await("XV editing or Formation menu after arranging support") {
                    (formation() && party() == 15 && !arrangement() && support() == support) ||
                        onFormationMenu() || emptySavePrompt()
                }.use {
                    if (it.emptySavePrompt()) {
                        // Only this exact "starter members missing" prompt can persist the blank
                        // reserved draft; it never starts a quest. XV was verified before opening it.
                        tap(685, 414)
                        await("cleared XV returned to editor or menu") {
                            (formation() && party() == 15 && !arrangement() && support() == support) || onFormationMenu()
                        }.use { if (it.onFormationMenu()) tap(841, 116) }
                    } else if (it.onFormationMenu()) tap(841, 116)
                }
                await("XV ready for servant selection") {
                    formation() && party() == 15 && !arrangement() && support() == support
                }.close()
                Thread.sleep(850) // settle the game-save animation before opening the first picker
            }
            writeStarted = true
            for (member in members) {
                report("selecting servant ${member.slot + 1}")
                chooseServant(member)
            }
            for (member in members) {
                report("equipping slot ${member.slot + 1}")
                chooseEquipment(member)
            }
            val arranging = await("complete XV before saving") {
                formation() && party() == 15 && rosterMatches(this, members, support)
            }.use { it.arrangement() }
            report("saving XV and reopening")
            saved = true
            tap(983, 442)
            if (arranging) {
                Thread.sleep(700)
                await("XV leaves arrangement mode") {
                    onFormationMenu() ||
                        (formation() && party() == 15 && !arrangement() && rosterMatches(this, members, support))
                }.use { if (!it.onFormationMenu()) tap(983, 442) }
            }
            await("Formation menu after save") { onFormationMenu() }.close()
            tap(841, 116)
            await("reopened XV matching ordered servants and Craft Essences") {
                formation() && party() == 15 && rosterMatches(this, members, support)
            }.close()
            report("checking original preset $reference was not altered")
            goTo(reference)
            await("read-only reference still matches original captured lineup") {
                formation() && party() == reference && rosterMatches(this, members, support)
            }.close()
            goTo(15)
            report("verified XV after save and reopen; reference unchanged")
        } finally {
            members.forEach { it.close() }
            assets.values.forEach { it.close() }
            assets.clear()
        }
    }
}
