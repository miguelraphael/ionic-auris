package com.auris.app.data.remote.anime

/**
 * Unpacks JavaScript code packed with Dean Edwards' Packer algorithm:
 * `eval(function(p,a,c,k,e,d){...}('...',29,35,'...'.split('|'),0,{}))`
 */
object JsUnpacker {
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private val PACKED_REGEX = Regex(
        """eval\(function\(p,a,c,k,e,d\).*?\}\s*\(\s*'(.*)',\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun unpack(packedJs: String): String {
        val match = PACKED_REGEX.find(packedJs) ?: return packedJs
        val (p, aStr, _, kStr) = match.destructured
        val a = aStr.toIntOrNull() ?: return packedJs
        val k = kStr.split('|')

        fun encode(v: Int): String {
            if (a <= 1 || a > ALPHABET.length) return v.toString()
            if (v < a) return ALPHABET[v].toString()
            return encode(v / a) + ALPHABET[v % a]
        }

        var result = p
        for (i in k.indices.reversed()) {
            val word = k[i]
            if (word.isNotEmpty()) {
                val token = encode(i)
                result = result.replace(Regex("\\b$token\\b"), word)
            }
        }
        return result
    }
}
