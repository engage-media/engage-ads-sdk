package com.engage.ads.core

import com.engage.ads.EngageError
import com.engage.ads.ResourceLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VastSupportTest {
    @Test fun oversizedVastIsRejectedBeforeXmlParsing() {
        val markup = "<VAST>" + "é".repeat((ResourceLimits.MAX_VAST_BYTES / 2).toInt()) + "<Ad/></VAST>"
        val error = assertThrows(EngageError::class.java) { validateVastMarkup(markup) }
        assertEquals(EngageError.Code.MALFORMED_RESPONSE, error.code)
    }

    @Test fun deeplyNestedVastIsRejectedBeforeDomConstruction() {
        val markup = "<VAST>" + "<node>".repeat(ResourceLimits.MAX_XML_DEPTH) + "<Ad/>" + "</node>".repeat(ResourceLimits.MAX_XML_DEPTH) + "</VAST>"
        val error = assertThrows(EngageError::class.java) { validateVastMarkup(markup) }
        assertEquals(EngageError.Code.MALFORMED_RESPONSE, error.code)
    }

    @Test fun emptyVastIsNoFill() {
        val error = assertThrows(EngageError::class.java) { validateVastMarkup("<VAST version=\"4.2\"/>") }
        assertEquals(EngageError.Code.NO_FILL, error.code)
    }

    @Test fun malformedVastIsMalformedResponse() {
        val error = assertThrows(EngageError::class.java) { validateVastMarkup("<VAST>") }
        assertEquals(EngageError.Code.MALFORMED_RESPONSE, error.code)
    }

    @Test fun wrapperAdIsAccepted() {
        validateVastMarkup("<VAST version=\"4.2\"><Ad id=\"1\"><Wrapper/></Ad></VAST>")
    }

    @Test fun ordinaryEntitiesNamespacesCommentsAndCdataAreAccepted() {
        validateVastMarkup(
            """<?xml version="1.0"?>
                <v:VAST xmlns:v="urn:iab:vast" version="4.2">
                  <v:Ad id="one&amp;two">
                    <!-- harmless <!DOCTYPE text> -->
                    <v:InLine><v:AdTitle><![CDATA[harmless <!ENTITY text>]]></v:AdTitle></v:InLine>
                  </v:Ad>
                </v:VAST>
            """.trimIndent(),
        )
    }

    @Test fun doctypeAndInternalEntityAreRejected() {
        val error = assertThrows(EngageError::class.java) {
            validateVastMarkup(
                """<!DOCTYPE VAST [<!ENTITY injected "content">]>
                    <VAST version="4.2"><Ad id="&injected;"><InLine/></Ad></VAST>
                """.trimIndent(),
            )
        }
        assertEquals(EngageError.Code.MALFORMED_RESPONSE, error.code)
    }

    @Test fun externalEntityIsRejectedWithoutResolution() {
        val error = assertThrows(EngageError::class.java) {
            validateVastMarkup(
                """<!DOCTYPE VAST [<!ENTITY exfiltrate SYSTEM "file:///etc/passwd">]>
                    <VAST version="4.2"><Ad id="&exfiltrate;"><InLine/></Ad></VAST>
                """.trimIndent(),
            )
        }
        assertEquals(EngageError.Code.MALFORMED_RESPONSE, error.code)
    }
}
