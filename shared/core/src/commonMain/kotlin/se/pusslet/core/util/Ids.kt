package se.pusslet.core.util

import kotlin.uuid.Uuid

/** Injectable id source so tests get predictable ids. */
fun interface IdGenerator {
    fun newId(): String
}

object RandomIdGenerator : IdGenerator {
    override fun newId(): String = Uuid.random().toHexString().take(20)
}

/** Sequential ids for tests and previews. */
class SequentialIdGenerator(private val prefix: String = "id") : IdGenerator {
    private var next = 1

    override fun newId(): String = "$prefix${next++}"
}

object InviteCodes {
    /** No 0/O, 1/I/L: codes are read aloud and typed by grandparents. */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 10

    /** 10 characters from a 31-symbol alphabet ≈ 49 bits, from a cryptographically secure source (Uuid.random). */
    fun generate(): String {
        val bytes = Uuid.random().toByteArray() + Uuid.random().toByteArray()
        return (0 until LENGTH).map { i -> ALPHABET[(bytes[i].toInt() and 0xFF) % ALPHABET.length] }.joinToString("")
    }

    /** "K7QDM2XP9R" -> "K7QD-M2XP-9R" */
    fun format(code: String): String = normalize(code).chunked(4).joinToString("-")

    /** Accepts lowercase, spaces and dashes (the alphabet has no look-alike characters to confuse). */
    fun normalize(input: String): String = input.uppercase().filter { it in ALPHABET }

    fun isValid(input: String): Boolean = normalize(input).length == LENGTH
}
