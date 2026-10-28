package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ListConverterTest {
    private val converter = Converters()

    @Test
    fun multilineCommandsQuotesEmptyEntriesAndUnicodeRoundTripWithoutLoss() {
        val commands = listOf("if ready:\n    print(\"hello\")", "", "echo 'مرحبا'", "[literal]")
        assertEquals(commands, converter.toList(converter.fromList(commands)))
        assertEquals(emptyList<String>(), converter.toList(converter.fromList(emptyList())))
    }

    @Test
    fun existingNewlineDelimitedRowsRemainReadable() {
        assertEquals(listOf("first", "second"), converter.toList("first\nsecond\n"))
        assertEquals(listOf("[legacy command]"), converter.toList("[legacy command]"))
        assertEquals(emptyList<String>(), converter.toList(""))
        assertNull(converter.fromList(null))
        assertNull(converter.toList(null))
    }
}
