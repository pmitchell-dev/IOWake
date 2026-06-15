package com.example.alarm

import kotlin.random.Random

data class MathPuzzle(
    val question: String,
    val answer: Int
)

object MathPuzzleEngine {

    fun generatePuzzle(difficulty: String, justAdditionForEasy: Boolean = false): MathPuzzle {
        var puzzle: MathPuzzle
        do {
            puzzle = when (difficulty.uppercase()) {
                "EASY" -> generateEasyPuzzle(justAdditionForEasy)
                "HARD" -> generateHardPuzzle()
                else -> generateMediumPuzzle() // Default to MEDIUM
            }
        } while (puzzle.answer < 0)
        return puzzle
    }

    private fun generateEasyPuzzle(justAdditionForEasy: Boolean): MathPuzzle {
        // Basic double digit addition/subtraction: e.g. 12 + 27 - 8
        val a = Random.nextInt(10, 50)
        val b = Random.nextInt(10, 40)
        val c = Random.nextInt(5, 20)
        
        val question: String
        val answer: Int

        if (justAdditionForEasy) {
            question = "$a + $b"
            answer = a + b
        } else {
            val isPlusFirst = Random.nextBoolean()
            if (isPlusFirst) {
                question = "$a + $b - $c"
                answer = a + b - c
            } else {
                // To avoid negative intermediate expression: make sure first operand is larger
                val first = maxOf(a, c)
                val second = minOf(a, c)
                question = "$first - $second + $b"
                answer = first - second + b
            }
        }

        return MathPuzzle(question, answer)
    }

    private fun generateMediumPuzzle(): MathPuzzle {
        // Double digit multiplication and order of operations: e.g. (14 * 4) + 19
        val a = Random.nextInt(11, 19)
        val b = Random.nextInt(3, 8)

        val isPlus = Random.nextBoolean()
        val question: String
        val answer: Int

        if (isPlus) {
            val c = Random.nextInt(10, 50)
            question = "($a × $b) + $c"
            answer = (a * b) + c
        } else {
            val product = a * b
            val c = Random.nextInt(10, minOf(50, product).coerceAtLeast(11))
            question = "($a × $b) - $c"
            answer = product - c
        }

        return MathPuzzle(question, answer)
    }

    private fun generateHardPuzzle(): MathPuzzle {
        // Triple-digit equations or multi-step operations requiring short-term memory: e.g. 142 + 283 - 67
        val a = Random.nextInt(150, 450)
        val b = Random.nextInt(100, 300)
        val c = Random.nextInt(40, 150)

        val option = Random.nextInt(3)
        val question: String
        val answer: Int

        when (option) {
            0 -> {
                question = "$a + $b - $c"
                answer = a + b - c
            }
            1 -> {
                // Ensure no negative intermediate of a - c
                val first = maxOf(a, c)
                val second = minOf(a, c)
                question = "$first - $second + $b"
                answer = first - second + b
            }
            else -> {
                // Multi-step double multiplication: e.g. (16 * 6) + (14 * 3)
                val m1 = Random.nextInt(12, 18)
                val f1 = Random.nextInt(4, 7)
                val m2 = Random.nextInt(11, 15)
                val f2 = Random.nextInt(3, 6)
                question = "($m1 × $f1) + ($m2 × $f2)"
                answer = (m1 * f1) + (m2 * f2)
            }
        }

        return MathPuzzle(question, answer)
    }
}
