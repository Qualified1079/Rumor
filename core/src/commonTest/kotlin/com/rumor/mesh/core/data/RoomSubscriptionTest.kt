package com.rumor.mesh.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomSubscriptionTest {

    @Test fun `OPEN subscription with empty routing key is valid`() {
        val sub = RoomSubscription("room-1", RoomSubscriptionMode.OPEN, ByteArray(0), 1000L)
        assertEquals("room-1", sub.roomId)
        assertEquals(0, sub.routingKey.size)
    }

    @Test fun `OPEN subscription with non-empty routing key fails`() {
        assertFails {
            RoomSubscription("room-1", RoomSubscriptionMode.OPEN, ByteArray(32), 1000L)
        }
    }

    @Test fun `ENCRYPTED subscription with 32-byte routing key is valid`() {
        val key = ByteArray(32) { it.toByte() }
        val sub = RoomSubscription("room-1", RoomSubscriptionMode.ENCRYPTED, key, 1000L)
        assertEquals(32, sub.routingKey.size)
        assertTrue(sub.routingKey.contentEquals(key))
    }

    @Test fun `ENCRYPTED subscription with empty routing key fails`() {
        assertFails {
            RoomSubscription("room-1", RoomSubscriptionMode.ENCRYPTED, ByteArray(0), 1000L)
        }
    }

    @Test fun `ENCRYPTED subscription with wrong-size routing key fails`() {
        assertFails {
            RoomSubscription("room-1", RoomSubscriptionMode.ENCRYPTED, ByteArray(16), 1000L)
        }
        assertFails {
            RoomSubscription("room-1", RoomSubscriptionMode.ENCRYPTED, ByteArray(64), 1000L)
        }
    }

    @Test fun `equals + hashCode use content equality on routing key`() {
        // Standard data-class equality compares ByteArray by reference,
        // not by content; we override to fix that — verify it works.
        val k1 = ByteArray(32) { 0x42 }
        val k2 = ByteArray(32) { 0x42 }  // same content, different array instance
        val a = RoomSubscription("room", RoomSubscriptionMode.ENCRYPTED, k1, 1000L)
        val b = RoomSubscription("room", RoomSubscriptionMode.ENCRYPTED, k2, 1000L)
        assertEquals(a, b, "subscriptions with same content must compare equal")
        assertEquals(a.hashCode(), b.hashCode(), "hashCode must match when equal")
    }

    @Test fun `equals returns false when only routingKey differs`() {
        val k1 = ByteArray(32) { 0x42 }
        val k2 = ByteArray(32) { 0x55 }
        val a = RoomSubscription("room", RoomSubscriptionMode.ENCRYPTED, k1, 1000L)
        val b = RoomSubscription("room", RoomSubscriptionMode.ENCRYPTED, k2, 1000L)
        assertFalse(a == b)
    }
}
