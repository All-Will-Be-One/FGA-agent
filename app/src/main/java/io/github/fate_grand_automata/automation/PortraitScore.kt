package io.github.fate_grand_automata.automation

/** Two patches must agree on the same artwork. Its second-best patch sets the score. */
internal inline fun <T> bestPortraitScore(variants: List<List<T>>, score: (T) -> Double): Double =
    variants.maxOfOrNull { patches ->
        var first = Double.NEGATIVE_INFINITY
        var second = Double.NEGATIVE_INFINITY
        for (patch in patches) {
            val value = score(patch)
            if (value >= first) {
                second = first
                first = value
            } else if (value > second) {
                second = value
            }
        }
        second
    } ?: Double.NEGATIVE_INFINITY
