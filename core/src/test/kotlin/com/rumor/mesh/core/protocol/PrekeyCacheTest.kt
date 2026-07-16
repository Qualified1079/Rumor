package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.model.PrekeyPublish
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrekeyCacheTest {

    private fun pk(id: String, from: Long, to: Long, key: String = "k-$from-$to"): PrekeyPublish =
        PrekeyPublish(
            publisherId = id,
            publisherPublicKey = "pub-$id",
            prekeyPublic = key,
            validFromMs = from,
            validToMs = to,
            signature = "sig",
        )

    @Test fun `empty cache returns null`() {
        val c = PrekeyCache()
        assertNull(c.freshestFor("alice", 100L))
    }

    @Test fun `put then freshestFor inside window`() {
        val c = PrekeyCache()
        val a = pk("alice", 100, 200)
        c.put(a)
        assertEquals(a, c.freshestFor("alice", 150L))
    }

    @Test fun `expired entry not returned`() {
        val c = PrekeyCache()
        c.put(pk("alice", 100, 200))
        assertNull(c.freshestFor("alice", 200L))
        assertNull(c.freshestFor("alice", 300L))
    }

    @Test fun `not-yet-valid entry not returned`() {
        val c = PrekeyCache()
        c.put(pk("alice", 100, 200))
        assertNull(c.freshestFor("alice", 99L))
    }

    @Test fun `strictly fresher entry displaces existing`() {
        val c = PrekeyCache()
        val older = pk("alice", 100, 200)
        val newer = pk("alice", 150, 300)
        c.put(older)
        c.put(newer)
        assertEquals(newer, c.freshestFor("alice", 175L))
    }

    @Test fun `stale entry does not displace fresher`() {
        val c = PrekeyCache()
        val newer = pk("alice", 150, 300)
        val older = pk("alice", 100, 200)
        c.put(newer)
        c.put(older)
        assertEquals(newer, c.freshestFor("alice", 175L))
    }

    @Test fun `same validToMs ties broken by validFromMs`() {
        val c = PrekeyCache()
        val a = pk("alice", 100, 300)
        val b = pk("alice", 150, 300)
        c.put(a)
        c.put(b)
        assertEquals(b, c.freshestFor("alice", 200L))
    }

    @Test fun `recipients are independent`() {
        val c = PrekeyCache()
        val a = pk("alice", 100, 200)
        val b = pk("bob", 100, 200)
        c.put(a)
        c.put(b)
        assertEquals(a, c.freshestFor("alice", 150L))
        assertEquals(b, c.freshestFor("bob", 150L))
    }
}
