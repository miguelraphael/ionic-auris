package com.auris.app.data.remote.anime

/**
 * Unpacks JavaScript code packed with Dean Edwards' Packer algorithm:
 * `eval(function(p,a,c,k,e,d){...}('...',29,35,'...'.split('|'),0,{}))`
 */
object JsUnpacker {
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private val PACKED_REGEX = Regex(
        """(?:eval\s*\(\s*)?function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[^)]*\)\s*\{.*?\}\s*\(\s*(?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)")\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)")\s*\.split\(\s*['"]\|['"]\s*\)[^)]*\)\s*\)?""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun unpack(packedJs: String): String {
        val matches = PACKED_REGEX.findAll(packedJs).toList()
        if (matches.isEmpty()) return packedJs

        val sb = StringBuilder()
        var lastIndex = 0
        for (match in matches) {
            sb.append(packedJs, lastIndex, match.range.first)
            val unpacked = unpackSingle(match)
            sb.append(unpacked)
            lastIndex = match.range.last + 1
        }
        sb.append(packedJs, lastIndex, packedJs.length)
        return sb.toString()
    }

    private fun unpackSingle(match: MatchResult): String {
        val p = match.groups[1]?.value ?: match.groups[2]?.value ?: return match.value
        val aStr = match.groups[3]?.value ?: return match.value
        val kStr = match.groups[5]?.value ?: match.groups[6]?.value ?: return match.value

        val a = aStr.toIntOrNull() ?: return match.value
        val k = kStr.split('|')

        val unescapedP = p
            .replace(Regex("""\\+['"]""")) { it.value.last().toString() }
            .replace(Regex("""\\+/"""), "/")

        val dict = HashMap<String, String>(k.size)
        for (i in k.indices) {
            val word = k[i]
            if (word.isNotEmpty()) {
                val token = encode(i, a)
                dict[token] = word
            }
        }

        return Regex("""\b\w+\b""").replace(unescapedP) { m ->
            dict[m.value] ?: m.value
        }
    }

    private fun encode(v: Int, radix: Int): String {
        if (radix <= 1) return v.toString()
        if (radix <= 36) return v.toString(radix)
        fun rec(n: Int): String {
            val q = n / radix
            val r = n % radix
            val ch = if (r > 35 && radix > 36) {
                (r + 29).toChar()
            } else {
                ALPHABET[r]
            }
            return if (q > 0) rec(q) + ch else ch.toString()
        }
        return rec(v)
    }
}
