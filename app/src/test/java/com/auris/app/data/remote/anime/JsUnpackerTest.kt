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
}
