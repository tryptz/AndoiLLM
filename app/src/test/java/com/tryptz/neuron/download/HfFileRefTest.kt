package com.tryptz.neuron.download

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HfFileRefTest {

    @Test
    fun `parses resolve links including download=true`() {
        val ref = HfFileRef.parse("https://huggingface.co/bartowski/Qwen_Qwen3-8B-GGUF/resolve/main/Qwen_Qwen3-8B-Q4_K_M.gguf?download=true")
        assertEquals(HfFileRef("bartowski/Qwen_Qwen3-8B-GGUF", "main", "Qwen_Qwen3-8B-Q4_K_M.gguf"), ref)
        assertEquals("https://huggingface.co/bartowski/Qwen_Qwen3-8B-GGUF/resolve/main/Qwen_Qwen3-8B-Q4_K_M.gguf", ref!!.resolveUrl)
    }

    @Test
    fun `parses blob pages, subfolders, revisions and the hf co host`() {
        val ref = HfFileRef.parse("https://hf.co/unsloth/gemma-3-4b-it-GGUF/blob/3f2a1b/quants/gemma-3-4b-it-Q4_0.gguf")
        assertEquals("unsloth/gemma-3-4b-it-GGUF", ref?.repo)
        assertEquals("3f2a1b", ref?.revision)
        assertEquals("quants/gemma-3-4b-it-Q4_0.gguf", ref?.path)
        assertEquals("gemma-3-4b-it-Q4_0.gguf", ref?.fileName)
        assertEquals("https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/3f2a1b/quants/gemma-3-4b-it-Q4_0.gguf", ref?.resolveUrl)
    }

    @Test
    fun `decodes and re-encodes special characters`() {
        val ref = HfFileRef.parse("https://huggingface.co/a/b/resolve/main/My%20Model%2B1.gguf")
        assertEquals("My Model+1.gguf", ref?.path)
        assertEquals("https://huggingface.co/a/b/resolve/main/My%20Model%2B1.gguf", ref?.resolveUrl)
    }

    @Test
    fun `ignores non gguf, non file and non HF urls`() {
        assertNull(HfFileRef.parse("https://huggingface.co/bartowski/Qwen_Qwen3-8B-GGUF"))
        assertNull(HfFileRef.parse("https://huggingface.co/bartowski/Qwen_Qwen3-8B-GGUF/tree/main"))
        assertNull(HfFileRef.parse("https://huggingface.co/a/b/resolve/main/README.md"))
        assertNull(HfFileRef.parse("https://evil.example/a/b/resolve/main/x.gguf"))
        assertNull(HfFileRef.parse("https://huggingface.co.evil.example/a/b/resolve/main/x.gguf"))
        assertNull(HfFileRef.parse("file:///sdcard/x.gguf"))
        assertNull(HfFileRef.parse("not a url"))
    }

    @Test
    fun `host check is exact`() {
        assertTrue(HfFileRef.isHuggingFaceHost("https://huggingface.co/models"))
        assertTrue(HfFileRef.isHuggingFaceHost("https://hf.co/x"))
        assertFalse(HfFileRef.isHuggingFaceHost("https://huggingface.co.evil.example/"))
        assertFalse(HfFileRef.isHuggingFaceHost("https://github.com/"))
    }

    @Test
    fun `split models expand to every part in order`() {
        val ref = HfFileRef.parse("https://huggingface.co/a/b/resolve/main/Q4_K_M/model-Q4_K_M-00002-of-00003.gguf")!!
        assertTrue(ref.isSplit)
        assertEquals(
            listOf(
                "Q4_K_M/model-Q4_K_M-00001-of-00003.gguf",
                "Q4_K_M/model-Q4_K_M-00002-of-00003.gguf",
                "Q4_K_M/model-Q4_K_M-00003-of-00003.gguf"
            ),
            ref.allParts().map { it.path }
        )
        val single = HfFileRef("a/b", "main", "model.gguf")
        assertFalse(single.isSplit)
        assertEquals(listOf(single), single.allParts())
    }
}
