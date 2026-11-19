package me.rerere.rikkahub.mate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MateWireTest {
    private val id = "9fc692a4-3490-4f12-955d-cfc12983dabc"

    @Test fun snapshotRequiresSameRequestId() {
        val event = MateWire.parseSnapshot(
            """{"id":"$id","state":"running","type":"snapshot","text":"李鱼","error":""}""", id
        )
        assertEquals("李鱼", event.text)
        assertFalse(event.terminal)
        assertThrows(IllegalArgumentException::class.java) {
            MateWire.parseSnapshot(
                """{"id":"other-message","state":"done","type":"done","text":"bad"}""", id
            )
        }
    }

    @Test fun sseParsesMultipleLinesAndComments() {
        val parser = MateSseParser()
        assertEquals(null, parser.line(": keepalive"))
        assertEquals(null, parser.line("data: " + """{"hello":"""))
        assertEquals(null, parser.line("data: " + """"world"}"""))
        assertEquals("""{"hello":
"world"}""", parser.line(""))
        assertEquals(null, parser.line(""))
    }

    @Test fun terminalAndUnknownStates() {
        val event = MateWire.parseSnapshot("""{"id":"$id","state":"done","type":"done","text":"好"}""", id)
        assertTrue(event.terminal)
        assertThrows(IllegalArgumentException::class.java) {
            MateWire.parseSnapshot("""{"id":"$id","state":"unapproved","type":"done","text":"x"}""", id)
        }
    }
}
