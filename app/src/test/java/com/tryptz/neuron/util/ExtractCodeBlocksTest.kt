package com.tryptz.neuron.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtractCodeBlocksTest {

    @Test
    fun `no code blocks returns empty`() {
        assertTrue("Hello world".extractCodeBlocks().isEmpty())
    }

    @Test
    fun `single code block with language`() {
        val input = "Here is code:\n```kotlin\nfun main() {}\n```"
        val result = input.extractCodeBlocks()
        assertEquals(1, result.size)
        assertEquals("kotlin", result[0].first)
        assertEquals("fun main() {}", result[0].second)
    }

    @Test
    fun `code block without language defaults to text`() {
        val input = "```\nhello\n```"
        val result = input.extractCodeBlocks()
        assertEquals(1, result.size)
        assertEquals("text", result[0].first)
        assertEquals("hello", result[0].second)
    }

    @Test
    fun `multiple code blocks`() {
        val input = """
Some text
```python
print("hi")
```
More text
```javascript
console.log("hi")
```
        """.trimIndent()

        val result = input.extractCodeBlocks()
        assertEquals(2, result.size)
        assertEquals("python", result[0].first)
        assertEquals("print(\"hi\")", result[0].second)
        assertEquals("javascript", result[1].first)
        assertEquals("console.log(\"hi\")", result[1].second)
    }

    @Test
    fun `multiline code block preserves content`() {
        val input = "```python\nline1\nline2\nline3\n```"
        val result = input.extractCodeBlocks()
        assertEquals(1, result.size)
        assertEquals("line1\nline2\nline3", result[0].second)
    }
}
