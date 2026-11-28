package io.github.fate_grand_automata.automation

internal enum class PartyScreen { VerifiedXV, Menu, Other }

// Poll both ends of the transition, including during retries. An animation or
// an unrelated preset must never authorize a touch.
internal fun awaitPartyTransition(
    source: PartyScreen,
    destination: PartyScreen,
    observe: () -> PartyScreen,
    pause: () -> Unit,
    tap: () -> Unit
): Boolean {
    val pollsBetweenTaps = 35
    val maxPolls = 4 * pollsBetweenTaps
    var nextTap = 0
    var attempts = 0
    var sourceFrames = 0
    repeat(maxPolls) { poll ->
        if (poll > 0) pause()
        val screen = observe()
        if (screen == destination && attempts > 0) return true
        sourceFrames = if (screen == source) sourceFrames + 1 else 0
        if (sourceFrames >= 2 && attempts < 3 && poll >= nextTap && poll < maxPolls - pollsBetweenTaps) {
            tap()
            attempts++
            sourceFrames = 0
            nextTap = poll + pollsBetweenTaps
        }
    }
    return false
}
