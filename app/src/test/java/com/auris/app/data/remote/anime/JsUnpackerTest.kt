package com.auris.app.data.remote.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsUnpackerTest {
    @Test
    fun unpakcsStandardPackedScript() {
        val packed = """
            <script type="text/javascript">
            eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0 2=\'1\';',3,3,'const|https://example.com/stream.m3u8|source'.split('|'),0,{}))
            </script>
        """.trimIndent()

        val unpacked = JsUnpacker.unpack(packed)
        assertTrue(unpacked.contains("https://example.com/stream.m3u8"))
        assertTrue(unpacked.contains("source"))
    }

    @Test
    fun returnsOriginalWhenNotPacked() {
        val plain = "const x = 42;"
        assertEquals(plain, JsUnpacker.unpack(plain))
    }

    @Test
    fun unpacksDoubleQuotedPackedScript() {
        val packed = """
            eval(function(p,a,c,k,e,d){}( "0 2=\"1\";" , 3 , 3 , "const|https://example.com/stream.m3u8|source".split("|") , 0 , {} ))
        """.trimIndent()

        val unpacked = JsUnpacker.unpack(packed)
        assertTrue(unpacked.contains("https://example.com/stream.m3u8"))
        assertTrue(unpacked.contains("const source"))
    }

    @Test
    fun unpacksEscapedQuotesAndSlashes() {
        val packed = """
            eval(function(p,a,c,k,e,d){return p}('const 0=\'https:\\/\\/example.com\\/master.m3u8\';',1,1,'source'.split('|'),0,{}))
        """.trimIndent()

        val unpacked = JsUnpacker.unpack(packed)
        assertTrue(unpacked.contains("https://example.com/master.m3u8"))
        assertTrue(unpacked.contains("const source"))
    }

    @Test
    fun unpacksBase62RadixWithUppercaseTokens() {
        // Construct symbols where index 36 translates to token 'A' in base 62
        val symbols = (0..36).map { i ->
            when (i) {
                0 -> "var"
                36 -> "finalTokenValue"
                else -> "unused$i"
            }
        }.joinToString("|")

        val packed = """
            eval(function(p,a,c,k,e,d){return p}('0 output = "A";',62,37,'$symbols'.split('|'),0,{}))
        """.trimIndent()

        val unpacked = JsUnpacker.unpack(packed)
        assertTrue(unpacked.contains("finalTokenValue"))
        assertTrue(unpacked.contains("var output"))
    }

    @Test
    fun unpacksWithVariedFunctionSignatureAndSpacing() {
        val packed = """
            eval(function(p, a, c, k, e, r) {
                while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p
            }('const 0=\'1\';',2,2,'stream|https://kwik.cx/test.m3u8'.split('|')))
        """.trimIndent()

        val unpacked = JsUnpacker.unpack(packed)
        assertTrue(unpacked.contains("https://kwik.cx/test.m3u8"))
        assertTrue(unpacked.contains("const stream"))
    }

    @Test
    fun unpacksMultipleScriptsInSingleDocument() {
        val html = """
            <html>
            <script>
            eval(function(p,a,c,k,e,d){return p}('const first=\'0\';',1,1,'stream1.m3u8'.split('|'),0,{}))
            </script>
            <div>Middle content</div>
            <script>
            eval(function(p,a,c,k,e,d){return p}('const second=\'0\';',1,1,'stream2.mp4'.split('|'),0,{}))
            </script>
            </html>
        """.trimIndent()

        val unpacked = JsUnpacker.unpack(html)
        assertTrue(unpacked.contains("stream1.m3u8"))
        assertTrue(unpacked.contains("stream2.mp4"))
        assertTrue(unpacked.contains("<div>Middle content</div>"))
    }
}
