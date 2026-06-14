/**
 * Tests for the collapsed-sidebar-sections JSON round-trip
 * ([encodeCollapsedSectionsJson] / [decodeCollapsedSectionsJson]).
 *
 * The empty-set case is a regression guard: the encoder used to call
 * `js("ids.toArray()")`, which threw on Kotlin's `EmptySet` runtime
 * object (no `toArray` method) — exactly when the user expanded their
 * last collapsed section — so the toggle aborted before rerendering and
 * the section could never be re-opened.
 */
package se.soderbjorn.darkness.web.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class CollapsedSectionsJsonTest {

    @Test
    fun round_trips_a_non_empty_set() {
        val ids = setOf("tab:tab-1", "extra:Outline")
        assertEquals(ids, decodeCollapsedSectionsJson(encodeCollapsedSectionsJson(ids)))
    }

    @Test
    fun round_trips_the_empty_set() {
        assertEquals(emptySet(), decodeCollapsedSectionsJson(encodeCollapsedSectionsJson(emptySet())))
    }

    @Test
    fun decode_returns_empty_set_on_malformed_input() {
        assertEquals(emptySet(), decodeCollapsedSectionsJson("not json"))
    }
}
