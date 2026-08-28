package com.chirp.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechFormatterTest {

    private val formatter = SpeechFormatter()

    // --- Markdown stripping -------------------------------------------------

    @Test
    fun `leaves plain prose untouched`() {
        assertEquals("Hello world.", formatter.toSpokenText("Hello world."))
        assertEquals(
            "The answer is 42 and pi is 3.14.",
            formatter.toSpokenText("The answer is 42 and pi is 3.14."),
        )
    }

    @Test
    fun `strips bold and italic markers but keeps the words`() {
        assertEquals("This is important and emphasised.",
            formatter.toSpokenText("This is **important** and *emphasised*."))
        assertEquals("Big underlined word",
            formatter.toSpokenText("__Big__ _underlined_ word"))
    }

    @Test
    fun `strips code spans and code fences`() {
        assertEquals("run installDebug to build it", formatter.toSpokenText("run `installDebug` to build it"))
        assertEquals("Some code here", formatter.toSpokenText("```kotlin\nSome code here\n```"))
    }

    @Test
    fun `strips headers blockquotes and bullet markers`() {
        assertEquals("Benefits", formatter.toSpokenText("### Benefits"))
        assertEquals("As I always say, keep it simple",
            formatter.toSpokenText("> As I always say, keep it simple"))
        assertEquals("First item", formatter.toSpokenText("- First item"))
        assertEquals("Second item", formatter.toSpokenText("* Second item"))
        assertEquals("Third item", formatter.toSpokenText("+ Third item"))
    }

    @Test
    fun `strips ordered list markers inside a sentence`() {
        assertEquals("Click here to continue", formatter.toSpokenText("1) Click here to continue"))
        assertEquals("a parenthesised item", formatter.toSpokenText("(2) a parenthesised item"))
    }

    @Test
    fun `extracts the label from links and images`() {
        assertEquals("Check the docs", formatter.toSpokenText("Check the [docs](https://example.com)"))
        assertEquals("A chart", formatter.toSpokenText("![A chart](chart.png)"))
        // Reference-style links end up as their label too.
        assertEquals("see ref 1", formatter.toSpokenText("see [ref 1]"))
    }

    @Test
    fun `strips html tags and horizontal rules`() {
        assertEquals("Just text", formatter.toSpokenText("<b>Just</b> text"))
        assertEquals("Separator", formatter.toSpokenText("---\nSeparator"))
    }

    @Test
    fun `flattens tables to prose`() {
        assertEquals("Name Age Alice 30", formatter.toSpokenText("Name | Age | Alice | 30"))
    }

    // Cross-utterance numbered lists -----------------------------------------

    @Test
    fun `fuses a lone list number onto the following sentence`() {
        assertEquals("", formatter.toSpokenText("1."))
        assertEquals("1 First item", formatter.toSpokenText("First item"))
    }

    @Test
    fun `fuses consecutive list numbers across utterances`() {
        assertEquals("", formatter.toSpokenText("1."))
        assertEquals("1 Set up the project", formatter.toSpokenText("Set up the project"))
        assertEquals("", formatter.toSpokenText("2."))
        assertEquals("2 Run the tests.", formatter.toSpokenText("Run the tests."))
    }

    @Test
    fun `keeps a pending number across a blank decorative utterance`() {
        assertEquals("", formatter.toSpokenText("3."))
        assertEquals("", formatter.toSpokenText("```"))
        assertEquals("", formatter.toSpokenText("---"))
        // The pending number survives and lands on the real content.
        assertEquals("3 target", formatter.toSpokenText("target"))
    }

    @Test
    fun `returns blank for pure decoration`() {
        assertTrue(formatter.toSpokenText("---").isEmpty())
        assertTrue(formatter.toSpokenText("```").isEmpty())
        assertTrue(formatter.toSpokenText("").isEmpty())
        assertTrue(formatter.toSpokenText("   ").isEmpty())
    }
}