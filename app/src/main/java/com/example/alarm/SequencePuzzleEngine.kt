package com.example.alarm

import kotlin.random.Random

object SequencePuzzleEngine {
    fun generateSequence(difficulty: String): List<Int> {
        val length = when (difficulty.uppercase()) {
            "EASY" -> 3
            "HARD" -> 6
            else -> 4 // MEDIUM
        }
        return List(length) { Random.nextInt(4) }
    }
}
