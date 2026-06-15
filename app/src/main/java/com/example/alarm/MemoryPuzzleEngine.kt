package com.example.alarm

data class MemoryCard(
    val id: Int,
    val symbol: String,
    val isFlipped: Boolean = false,
    val isMatched: Boolean = false
)

object MemoryPuzzleEngine {
    private val EMOJIS = listOf(
        "🧠", "☀️", "☕", "⏰", "⚡", "💡", "🌟", "🔥", "🚀", "🎯", "🎨", "🛸"
    )

    fun generateCards(difficulty: String): List<MemoryCard> {
        val numPairs = when (difficulty.uppercase()) {
            "EASY" -> 3    // 6 cards
            "HARD" -> 6    // 12 cards
            else -> 4      // 8 cards - Default to MEDIUM
        }

        val selectedSymbols = EMOJIS.shuffled().take(numPairs)
        val allSymbols = (selectedSymbols + selectedSymbols).shuffled()

        return allSymbols.mapIndexed { index, symbol ->
            MemoryCard(id = index, symbol = symbol)
        }
    }
}
