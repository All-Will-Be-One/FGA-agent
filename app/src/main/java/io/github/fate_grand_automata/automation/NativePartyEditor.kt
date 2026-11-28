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
import io.github.fate_grand_automata.supportcatalog.ServantCatalog
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import io.github.fate_grand_automata.scripts.modules.AgentEquipmentCompatibility
import io.github.fate_grand_automata.scripts.modules.AgentLimitBreak

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
        val path = if (name == "dot") "Jp/selected_party_agent.png" else "Jp/party-editor/$name.png"
        context.assets.open(path).use { DroidCvPattern(it, false, name) }
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
        if (ce) {
            // The track does not jump to the drag's start. Drag the observed
            // thumb, and verify the actual top, otherwise max-level CEs get skipped.
            repeat(4) {
                val thumb = await("Craft Essence scroll track") { equipment() }.use {
                    it.match(template("ce-scroll-thumb"), box(997, 246, 34, 241), .89)
                } ?: return  // some filtered lists fit on one page and have no thumb
                val y = (thumb.third / scale).roundToInt()
                if (y <= 263) return
                drag(1014, y, 1014, 240)
                Thread.sleep(450)
            }
            fail("Could not verify top of the Craft Essence inventory")
        }
        drag(1013, 307, 1013, 131)
        Thread.sleep(450)
    }

    private data class Member(
        val slot: Int,
        val portrait: Pattern?,
        val essences: List<Pattern>,
        val previewName: Pattern?,
        val previewEffect: Pattern?,
        val limitBreak: AgentLimitBreak?,
        val idArt: IdArt? = null
    ) : AutoCloseable {
        override fun close() {
            portrait?.close()
            idArt?.close()
            essences.forEach { it.close() }
            previewName?.close()
            previewEffect?.close()
        }
    }

    /** Multiple independent art patches guard against level, cost, frame and event overlays.
     * Widths are at the existing 720p scale and were checked on the actual JP picker/editor. */
    private data class ArtPatch(val image: Pattern, val xToCenter: Int) : AutoCloseable {
        override fun close() = image.close()
    }
    private data class IdArt(
        val formation: List<List<ArtPatch>>,
        val servants: List<List<ArtPatch>>,
        val equipment: List<List<ArtPatch>>,
        val banner: List<ArtPatch>,
        val preview: List<ArtPatch>
    ) : AutoCloseable {
        override fun close() =
            (formation.flatten() + servants.flatten() + equipment.flatten() + banner + preview)
                .forEach { it.close() }
    }
    private data class Fraction(val x: Double, val y: Double, val w: Double, val h: Double)
    private val faceWindows = listOf(
        Fraction(.31, .28, .34, .19), Fraction(.28, .45, .32, .18),
        Fraction(.40, .35, .32, .22), Fraction(.26, .20, .47, .13)
    )
    private val bannerWindows = listOf(
        Fraction(.13, .29, .34, .35), Fraction(.48, .30, .34, .39),
        Fraction(.32, .34, .34, .35)
    )

    private fun patches(bytes: ByteArray, width: Int, windows: List<Fraction>): List<ArtPatch> =
        bytes.inputStream().use { stream ->
            DroidCvPattern(stream, false, "catalog-party-art").use { raw ->
                require(raw.width in 64..512 && raw.height in 48..512) { "Invalid catalog artwork" }
                val height = (width * raw.height.toDouble() / raw.width).roundToInt()
                raw.resize(Size(width, height)).use { face ->
                    windows.map { (x, y, w, h) ->
                        val left = (width * x).roundToInt()
                        val right = (width * (x + w)).roundToInt()
                        val top = (height * y).roundToInt()
                        val bottom = (height * (y + h)).roundToInt()
                        ArtPatch(face.crop(Region(left, top, right - left, bottom - top)),
                            width / 2 - (left + right) / 2)
                    }
                }
            }
        }

    private fun loadMember(member: CalculatedMember, faces: Map<Int, List<ByteArray>>,
                           essences: Map<Int, Pair<ByteArray, ByteArray>>): Member {
        val resources = mutableListOf<ArtPatch>()
        fun make(bytes: ByteArray, width: Int, windows: List<Fraction>) =
            patches(bytes, width, windows).also { resources.addAll(it) }
        try {
            val ownFaces = faces.getValue(member.servantId)
            val equip = member.ce?.let { essences.getValue(it.id) }
            return Member(member.slot - 1, null, emptyList(), null, null, member.ce?.let {
                if (it.mlb) AgentLimitBreak.Max else AgentLimitBreak.Normal
            }, IdArt(
                formation = ownFaces.map { make(it, 195, faceWindows) },
                servants = ownFaces.map { make(it, 118, faceWindows) },
                equipment = equip?.let { listOf(make(it.first, 118, faceWindows)) }.orEmpty(),
                banner = equip?.let { make(it.second, 188, bannerWindows) }.orEmpty(),
                preview = equip?.let { make(it.second, 158, bannerWindows) }.orEmpty()
            ))
        } catch (e: Exception) {
            resources.forEach { it.close() }
            throw e
        }
    }

    private fun artMatches(f: Pattern, patches: List<ArtPatch>, roi: Region): Boolean =
        patches.count { f.match(it.image, roi, .91) != null } >= 2

    private fun hasEquipment(member: Member) =
        member.essences.isNotEmpty() || member.idArt?.equipment?.isNotEmpty() == true

    private fun gearMatches(f: Pattern, m: Member): Boolean {
        if (!hasEquipment(m)) return f.unequipped(m.slot)
        return m.idArt?.let { art ->
            artMatches(f, art.banner, box(centers[m.slot] - 62, 283, 124, 58))
        } ?: (m.essences.count { f.match(it, ceBox(m.slot), .92) != null } >= 2)
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
                // Independently compare three horizontal bands; the picker adds level/cost text.
                if (view.unequipped(slot)) {
                    members += Member(slot, portrait, emptyList(), null, null, null)
                    return@use
                }
                val art = listOf(284, 294, 303).map { y -> view.crop(box(centers[slot] - 28, y, 56, 20)) }
                tap(centers[slot], 313)  // source inspection only
                await("reference Craft Essence opens") { equipment() }.close()
                Thread.sleep(550)
                await("stable reference Craft Essence details") { equipment() }.use { picker ->
                    members += Member(slot, portrait, art,
                        picker.crop(box(302, 132, 415, 27)),
                        picker.crop(box(304, 163, 418, 64)),
                        limitBreak(picker))
                }
                tap(126, 29)  // close, never confirm on the read-only preset
                await("reference formation after inspection") { formation() && party() == preset }.close()
            }
        }
        return support
    }

    private fun portraitMatches(f: Pattern, m: Member) =
        m.idArt?.formation?.any {
            artMatches(f, it, box(centers[m.slot] - 62, 124, 124, 103))
        } ?: (f.match(requireNotNull(m.portrait), box(centers[m.slot] - 31, 138, 62, 61), .90) != null)

    private fun rosterMatches(f: Pattern, members: List<Member>, support: Int): Boolean {
        val empty = centers.indices.filter { i -> i != support && members.none { it.slot == i } }
        return f.support() == support && empty.all { f.emptySlot(it) } && members.all { m ->
            portraitMatches(f, m) && gearMatches(f, m)
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

    private fun catalogCandidates(f: Pattern, variants: List<List<ArtPatch>>, ce: Boolean): List<Candidate> {
        val top = if (ce) 254 else 131
        val roi = box(176, top, 763, 489 - top)
        val result = mutableListOf<Candidate>()
        f.crop(roi).use { grid ->
            for (variant in variants) {
                val matches = variant.flatMapIndexed { i, patch ->
                    grid.findMatches(patch.image, .91).take(30).mapNotNull { m ->
                        val x = ((m.region.center.x + roi.x + patch.xToCenter) / scale).roundToInt()
                        val y = ((m.region.center.y + roi.y) / scale).roundToInt()
                        val cx = gridX.minBy { abs(it - x) }
                        if (abs(cx - x) > 11 || y !in top + 7..460) null else Candidate(cx, y, m.score, i)
                    }.toList()
                }
                matches.sortedByDescending { it.score }.forEach { match ->
                    if (result.any { it.x == match.x && abs(it.y - match.y) < 44 }) return@forEach
                    val votes = matches.filter { it.x == match.x && abs(it.y - match.y) < 29 }
                        .map { it.votes }.distinct().size
                    if (votes >= 2 && match.score >= .94) result += match.copy(votes = votes)
                }
            }
        }
        return result.sortedByDescending { it.score }
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
        val patches = m.portrait?.let {
            listOf(it.crop(box(4, 9, 42, 25)), it.crop(box(3, 19, 43, 24)))
        }.orEmpty()
        try {
            resetScroll(false)
            repeat(32) { page ->
                val matches = await("Servant Selection inventory") { servants() }.use { picker ->
                    m.idArt?.let { catalogCandidates(picker, it.servants, false) } ?: candidates(picker, patches, false)
                }
                val best = matches.firstOrNull()
                if (best != null && (matches.size == 1 ||
                        (m.idArt == null && best.score - matches[1].score >= .028))) {
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

    private fun limitBreak(f: Pattern): AgentLimitBreak? {
        val text = box(296, 162, 443, 69)
        // Ordinary CEs can render the max-limit-break marker inline in the
        // effect text, at a different size from the event CE template.
        val inline = f.match(template("ce-inline-max-limit"), text, .0)?.first ?: 0.0
        if (inline >= .84) return AgentLimitBreak.Max
        val stamp = template("max-limit")
        var best = inline
        for (factor in listOf(.76, .80, .84, .88, .91, .94, .97, 1.0, 1.04, 1.08, 1.12, 1.16)) {
            val score = stamp.resize(Size((stamp.width * factor).roundToInt(), (stamp.height * factor).roundToInt()))
                .use { f.match(it, text, .0)?.first ?: 0.0 }
            best = maxOf(best, score)
            if (best >= .83) return AgentLimitBreak.Max
        }
        return if (best < .62) AgentLimitBreak.Normal else null
    }

    private fun previewMatches(f: Pattern, m: Member, allowUpgrade: Boolean): Boolean {
        m.idArt?.let {
            return artMatches(f, it.preview, box(162, 205, 104, 32)) &&
                limitBreak(f)?.let { observed ->
                    AgentEquipmentCompatibility.accepts(requireNotNull(m.limitBreak), observed, allowUpgrade)
                } == true
        }
        if (f.match(requireNotNull(m.previewName), box(298, 128, 423, 35), .975) == null) return false
        // Level/HP/ATK are not part of identity. Identical effect text can corroborate
        // an unreadable marker, but never overrule an observed max → normal mismatch.
        val observed = limitBreak(f)
        val required = m.limitBreak
        val sameEffect = f.match(requireNotNull(m.previewEffect), box(301, 161, 424, 69), .986) != null
        if (required != null && observed != null)
            return AgentEquipmentCompatibility.accepts(required, observed, allowUpgrade) &&
                (required != observed || sameEffect)
        return sameEffect || (allowUpgrade && observed == AgentLimitBreak.Max)
    }

    private fun chooseEquipment(m: Member) {
        if (!hasEquipment(m)) return
        await("reserved XV with matching servant before Craft Essence") {
            formation() && party() == 15 && portraitMatches(this, m)
        }.close()
        tap(centers[m.slot], 313)
        val wasEvent = await("Craft Essence Selection") { equipment() }.use { ceFilterEvent(it) }
        try {
            val passes = if (m.limitBreak == AgentLimitBreak.Max) listOf(false) else listOf(false, true)
            for (upgrade in passes) {
                for (eventOnly in listOf(true, false)) {
                    if (eventOnly) selectTab()
                    else frame().use { if (ceFilterEvent(it)) toggleFilter(false) }
                    resetScroll(true)
                    var previousGrid: Pattern? = null
                    try {
                        for (page in 0 until if (eventOnly) 6 else 38) {
                            val options = await("Craft Essence inventory") { equipment() }.use { f ->
                                val grid = box(177, 251, 759, 229)
                                // Stop at the end of a short or exhausted result list. Preview
                                // selection can tint one card, so require a neighboring swipe too.
                                if (previousGrid?.let { f.match(it, grid, .997) != null } == true) return@use null
                                previousGrid?.close()
                                previousGrid = f.crop(grid)
                                m.idArt?.let { catalogCandidates(f, it.equipment, true) }
                                    ?: candidates(f, m.essences, true)
                            } ?: break
                            for (candidate in options) {
                                val occupied = frame().use {
                                    !it.equipment() || it.has("equipped",
                                        box(candidate.x - 37, (candidate.y - 10).coerceAtLeast(248), 74, 51), .81)
                                }
                                if (occupied) continue
                                tap(candidate.x, candidate.y)
                                Thread.sleep(530)
                                if (!frame().use { it.equipment() && previewMatches(it, m, upgrade) }) continue
                                restoreFilter(wasEvent)
                                if (!frame().use { it.equipment() && previewMatches(it, m, upgrade) })
                                    fail("Craft Essence selection reset on filter restore at slot ${m.slot + 1}")
                                tap(888, 208)
                                await("XV equipped slot ${m.slot + 1}; transfers forbidden") {
                                    formation() && party() == 15 &&
                                        gearMatches(this, m)
                                }.close()
                                return
                            }
                            report("searching Craft Essence ${m.slot + 1}, event=$eventOnly, upgrade=$upgrade, page=${page + 1}")
                            scroll(true)
                        }
                    } finally { previousGrid?.close() }
                }
            }
        } finally { restoreFilter(wasEvent) }
        fail("Could not find an unassigned same-name Craft Essence with compatible limit break for slot ${m.slot + 1}")
    }

    private fun savedEquipmentMatches(members: List<Member>): Boolean {
        for (m in members.filter(::hasEquipment)) {
            await("XV before reading saved equipment") { formation() && party() == 15 }.close()
            tap(centers[m.slot], 313)
            await("saved Craft Essence opens") { equipment() }.close()
            Thread.sleep(550)
            val matches = await("stable saved Craft Essence details") { equipment() }.use {
                previewMatches(it, m, true)
            }
            tap(126, 29)
            await("XV after reading saved equipment") { formation() && party() == 15 }.close()
            if (!matches) return false
        }
        return true
    }

    private fun saveAndReopen(members: List<Member>, support: Int) {
        report("saving XV and reopening")
        fun observe() = frame().use { f ->
            when {
                f.onFormationMenu() -> PartyScreen.Menu
                f.formation() && f.party() == 15 && rosterMatches(f, members, support) -> PartyScreen.VerifiedXV
                else -> PartyScreen.Other
            }
        }
        val pause = { Thread.sleep(220) }
        if (!awaitPartyTransition(PartyScreen.VerifiedXV, PartyScreen.Menu, ::observe, pause) {
                saved = true
                tap(983, 442)
            }) fail("Expected Formation menu after saving verified XV")

        if (!awaitPartyTransition(PartyScreen.Menu, PartyScreen.VerifiedXV, ::observe, pause) {
                tap(841, 116)
            }) fail("Expected reopened XV matching ordered servants and Craft Essences")
        report("rechecking saved Craft Essence names and limit breaks")
        if (!savedEquipmentMatches(members))
            fail("Reopened XV has an incorrect Craft Essence name or limit break")
    }

    fun rebuild(reference: Int, canContinue: () -> Boolean, progress: (String) -> Unit) =
        applyFormation(reference, null, canContinue, progress)

    fun apply(plan: CalculatedFormation, canContinue: () -> Boolean, progress: (String) -> Unit) =
        applyFormation(null, plan, canContinue, progress)

    private fun applyFormation(reference: Int?, plan: CalculatedFormation?,
                               canContinue: () -> Boolean, progress: (String) -> Unit) {
        deadline = SystemClock.elapsedRealtime() + 300_000
        report = progress
        ownsRunner = canContinue
        val members = mutableListOf<Member>()
        try {
            if (abs(window.width.toDouble() / window.height - 1100.0 / 495) > .012 || window.x != 0 || window.y != 0)
                fail("Unsupported JP formation aspect ratio or game bounds")
            await("reserved Party XV on the JP formation screen") { formation() && party() == 15 }.close()
            val support = if (reference != null) {
                report("capturing reference preset $reference")
                captureReference(reference, members).also { goTo(15) }
            } else {
                val calculated = requireNotNull(plan)
                report("preflight: resolve calculated servant and Craft Essence IDs")
                val faces = calculated.members.map { it.servantId }.distinct().associateWith { id ->
                    checkTime()
                    ServantCatalog.faceUrls(id).mapNotNull { url ->
                        checkTime()
                        ServantCatalog.face(url)
                    }.also { require(it.isNotEmpty()) { "No usable portrait for servant $id" } }
                }
                val essences = calculated.members.mapNotNull { it.ce?.id }.distinct().associateWith { id ->
                    checkTime()
                    ServantCatalog.equipFaces(id)
                }
                calculated.members.sortedBy { it.slot }.forEach { members += loadMember(it, faces, essences) }
                calculated.supportSlot - 1
            }
            if (members.count { it.slot < 3 } != 2) fail("Reference needs two owned starters and one support slot")
            if (frame().use { rosterMatches(it, members, support) } && savedEquipmentMatches(members)) {
                if (plan != null) saveAndReopen(members, support)
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
                    // The arrangement page can render before it starts accepting drags.
                    // Retry only after confirming the support is still at its original
                    // position and that the destination remains empty in reserved XV.
                    Thread.sleep(1050)
                    var moved = false
                    for (attempt in 1..3) {
                        frame().use {
                            if (it.arrangement() && it.party() == 15 && it.support() == support) {
                                moved = true
                            }
                        }
                        if (moved) break
                        await("stable empty XV support source and destination") {
                            arrangement() && party() == 15 && support() == currentSupport && emptySlot(support)
                        }.close()
                        report("moving support position ${currentSupport + 1} to ${support + 1}, attempt $attempt")
                        checkTime()
                        gestures.dragFormationCard(position(centers[currentSupport], 183), position(centers[support], 183))
                        val accepted = awaitOrNull(8) { arrangement() && party() == 15 && support() == support }
                        if (accepted != null) {
                            accepted.close()
                            moved = true
                            break
                        }
                    }
                    if (!moved) fail("Could not move support to position ${support + 1}")
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
            saveAndReopen(members, support)
            if (reference != null) {
                report("checking original preset $reference was not altered")
                goTo(reference)
                await("read-only reference still matches original captured lineup") {
                    formation() && party() == reference && rosterMatches(this, members, support)
                }.close()
                goTo(15)
            }
            report("verified XV after save and reopen")
        } finally {
            members.forEach { it.close() }
            assets.values.forEach { it.close() }
            assets.clear()
        }
    }
}
