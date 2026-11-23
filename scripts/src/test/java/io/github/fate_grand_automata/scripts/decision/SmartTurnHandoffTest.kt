package io.github.fate_grand_automata.scripts.decision

import io.github.fate_grand_automata.scripts.*
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.locations.Locations
import io.github.fate_grand_automata.scripts.models.*
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.*
import io.github.fate_grand_automata.scripts.prefs.*
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SmartTurnHandoffTest {
    private enum class Screen { Battle, Details, Info, Closing, Cards }

    /** Only the screen, clock and host are simulated. Battle, tracker and phase dispatch are real. */
    private class Display(val transitionMs: Long) : IFgoAutomataApi by mockk<IFgoAutomataApi>(relaxed = true) {
        override val prefs = mockk<IPreferences>(relaxed = true)
        override val locations = mockk<Locations>(relaxed = true)
        override val images = mockk<IImageLoader>(relaxed = true)
        override val messages = mockk<IScriptMessages>(relaxed = true)
        var screen = Screen.Battle
        var time = 0L
        var lastClose = 0L
        var infoAt = 0L
        val openings = mutableListOf<FieldSlot>()
        val captures = mutableListOf<Screen>()
        val wholeScreen = Region(0, 0, 1000, 500)
        private val field = FieldSlot.list.associateWith { Location(it.position, 0) }
        private val support = Region(10, 5, 1, 1)
        private val arts = mockk<Pattern>(relaxed = true)

        init {
            every { prefs.skipServantFaceCardCheck } returns false
            every { prefs.treatSupportLikeOwnServant } returns false
            every { prefs.waitBeforeTurn } returns Duration.ZERO
            every { prefs.waitBeforeCards } returns Duration.ZERO
            every { prefs.servantDetailsWait } returns 250.milliseconds
            every { prefs.npCardTypeSimilarity } returns 0.8
            every { images[Images.NpArts] } returns arts
            every { locations.scriptArea } returns wholeScreen
            for ((slot, location) in field) {
                every { locations.battle.servantOpenDetailsClick(slot) } returns location
                every { locations.battle.servantChangeSupportCheckRegion(slot) } returns
                    if (slot == FieldSlot.C) support else Region(slot.position, 5, 1, 1)
            }
            every { locations.battle.servantDetailsInfoClick } returns Location(0, 1)
            every { locations.battle.extraInfoWindowCloseClick } returns Location(0, 2)
            every { locations.battle.attackClick } returns Location(0, 3)
        }

        fun advance(ms: Long) {
            time += ms
            if (screen == Screen.Closing && time - lastClose >= transitionMs) screen = Screen.Battle
        }

        override fun <T> useSameSnapIn(block: () -> T) = block()
        override fun <T> useColor(block: () -> T) = block()
        override fun Duration.wait(applyMultiplier: Boolean) = advance(inWholeMilliseconds)
        override fun Region.exists(image: Pattern, timeout: Duration, similarity: Double?) = true
        override fun Region.contains(image: Pattern) = this == support
        override fun Pattern.find(pattern: Pattern, similarity: Double?): Match? =
            if (pattern === arts) Match(support, 0.96) else null

        override fun Region.getPattern(tag: String): Pattern {
            if (this == wholeScreen) captures += screen
            if (tag.startsWith("Face ") || tag.startsWith("NP type ")) {
                assertEquals(Screen.Info, screen)
                assertTrue(time - infoAt >= transitionMs)
            }
            return mockk(relaxed = true)
        }

        override fun Location.click(times: Int) {
            when (this) {
                in field.values -> {
                    assertEquals(Screen.Battle, screen)
                    openings += field.entries.single { it.value == this }.key
                    screen = Screen.Details
                }
                locations.battle.servantDetailsInfoClick -> {
                    assertEquals(Screen.Details, screen)
                    screen = Screen.Info
                    infoAt = time
                }
                locations.battle.extraInfoWindowCloseClick -> {
                    assertEquals(Screen.Info, screen)
                    screen = Screen.Closing
                    lastClose = time
                }
                locations.battle.attackClick -> {
                    assertEquals(Screen.Battle, screen)
                    screen = Screen.Cards
                }
                else -> error("Unexpected click $this")
            }
        }
    }

    private enum class Reply { Valid, Missing, Invalid }

    private fun runTurn(reply: Reply, transitionMs: Long) {
        val display = Display(transitionMs)
        val config = mockk<IBattleConfig>(relaxed = true)
        val localOrder = ServantPriorityPerWave.from(listOf(TeamSlot.list.reversed()))
        every { config.servantPriority } returns localOrder
        val tracker = ServantTracker(display, config)
        val state = BattleState()
        val delivered = mutableListOf<Int>()
        val caster = mockk<Caster>()
        every { caster.use(any<CommandCard.Face>()) } answers { delivered += firstArg<CommandCard.Face>().index }
        val provider = object : NativeBattleDecisionProvider by DisabledNativeBattleDecisionProvider {
            override val enabled = true
            override fun await(context: NativeDecisionContext, capture: () -> Pattern, checkCancelled: () -> Unit): NativeDecisionCommand {
                capture().close()
                if (context.phase == NativeDecisionPhase.Skills) {
                    assertEquals(Screen.Battle, display.screen)
                    assertEquals(250L, display.time - display.lastClose)
                }
                val before = display.openings.toList()
                display.advance(1200)
                assertEquals(before, display.openings)
                assertTrue(delivered.isEmpty())
                if (context.phase == NativeDecisionPhase.Skills) {
                    return NativeDecisionCommand("skills", NativeDecisionAction.Skills(emptyList()))
                }
                if (reply == Reply.Missing) throw NoSuchElementException("host plan absent")
                val order = if (reply == Reply.Invalid) listOf(2, 2, 3) else listOf(4, 1, 3)
                return NativeDecisionCommand("cards", NativeDecisionAction.Cards(order))
            }
        }
        val card = mockk<Card>()
        every { card.readCommandCards() } returns emptyList()
        val plans = ExternalPlanBattle(display, state, caster, ExternalCardExecutor(display, caster), provider, ExitManager())
        val battle = Battle(display, tracker, state, config, mockk(), caster, card, mockk(), mockk(), mockk(relaxed = true), mockk(), plans)
        when (reply) {
            Reply.Valid -> battle.performBattle()
            Reply.Missing -> assertFailsWith<NoSuchElementException> { battle.performBattle() }
            Reply.Invalid -> assertFailsWith<IllegalArgumentException> { battle.performBattle() }
        }
        assertEquals(listOf(FieldSlot.A, FieldSlot.B, FieldSlot.C), display.openings)
        assertEquals(listOf(Screen.Battle, Screen.Cards), display.captures)
        assertEquals(if (reply == Reply.Valid) listOf(4, 1, 3) else emptyList(), delivered)
        verify(exactly = 0) { config.servantPriority }
        verify(exactly = 0) { card.clickCommandCards(any(), any()) }
        tracker.close()
    }

    @Test fun delayedHostGetsSettledScreenAndOwnOrder() {
        for (delay in listOf(200L, 250L)) runTurn(Reply.Valid, delay)
    }
    @Test fun absentOrInvalidHostPlanCannotSelectCards() {
        runTurn(Reply.Missing, 200)
        runTurn(Reply.Invalid, 200)
    }
    @Test fun ordinaryTrackerRecognizesSupportArtsNp() {
        val display = Display(200)
        ServantTracker(display, mockk(relaxed = true)).use { tracker ->
            tracker.beginTurn()
            assertEquals(CardTypeEnum.Arts, tracker.getNpCardType(TeamSlot.C))
            assertEquals(Screen.Battle, display.screen)
        }
    }
}
