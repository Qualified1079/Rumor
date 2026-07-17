package com.rumor.mesh.core.routing

import com.rumor.mesh.core.model.UserMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackboneRealizerTest {

    private fun links(vararg pairs: Pair<String, String>): Set<Link> =
        pairs.map { Link.of(it.first, it.second) }.toSet()

    private fun mobile(vararg ids: String) = ids.associateWith { UserMode.MOBILE }

    // ── the load-bearing property: agreement without a coordinator ────────────

    @Test
    fun `every node computes identical roles from the same input`() {
        val ls = links("a" to "b", "b" to "c", "b" to "d")
        val modes = mobile("a", "b", "c", "d") + ("b" to UserMode.STATIC)
        val r1 = BackboneRealizer.realize(ls, modes)
        val r2 = BackboneRealizer.realize(ls.shuffled().toSet(), modes.entries.reversed().associate { it.key to it.value })
        for (id in listOf("a", "b", "c", "d")) {
            assertEquals(r1.roleOf(id), r2.roleOf(id))
        }
    }

    @Test
    fun `three node star hub hosts, leaves join`() {
        // The field topology: hub with two backbone edges.
        val r = BackboneRealizer.realize(links("hub" to "a", "hub" to "z"), mobile("a", "hub", "z"))
        assertEquals(BackboneRealizer.Role.Host(setOf("a", "z")), r.roleOf("hub"))
        assertEquals(BackboneRealizer.Role.Client("hub"), r.roleOf("a"))
        assertEquals(BackboneRealizer.Role.Client("hub"), r.roleOf("z"))
        assertEquals(2, r.realized.size)
        assertTrue(r.dropped.isEmpty())
    }

    @Test
    fun `higher capacity anchor hosts even with equal degree`() {
        // a—b single edge: FREE b outranks MOBILE a despite identical degree.
        val r = BackboneRealizer.realize(
            links("a" to "b"),
            mapOf("a" to UserMode.MOBILE, "b" to UserMode.FREE),
        )
        assertEquals(BackboneRealizer.Role.Host(setOf("a")), r.roleOf("b"))
        assertEquals(BackboneRealizer.Role.Client("b"), r.roleOf("a"))
    }

    @Test
    fun `no node is ever both host and client`() {
        // Random-ish denser graph; the invariant must hold structurally.
        val ids = (1..9).map { "u$it" }
        val ls = links(
            "u1" to "u2", "u2" to "u3", "u3" to "u4", "u4" to "u5",
            "u5" to "u6", "u2" to "u7", "u7" to "u8", "u8" to "u9",
        )
        val r = BackboneRealizer.realize(ls, ids.associateWith { UserMode.MOBILE })
        for (id in ids) {
            val role = r.roleOf(id)
            if (role is BackboneRealizer.Role.Host) {
                for (other in ids) {
                    val o = r.roleOf(other)
                    if (o is BackboneRealizer.Role.Client && o.hostUserId == id) {
                        assertTrue(r.roleOf(other) !is BackboneRealizer.Role.Host)
                    }
                }
                // A host is nobody's client.
                for (other in ids) {
                    val o = r.roleOf(other)
                    if (o is BackboneRealizer.Role.Host) assertTrue(id !in o.clients || id != other)
                }
            }
        }
        // Every client's host really is a host.
        for (id in ids) {
            val role = r.roleOf(id)
            if (role is BackboneRealizer.Role.Client) {
                assertTrue(r.roleOf(role.hostUserId) is BackboneRealizer.Role.Host)
            }
        }
    }

    @Test
    fun `four chain drops the unreachable far edge`() {
        // a—b—c—d: one hub claims its neighbours; the far leaf's edge to a
        // claimed client is unrealizable and must be reported dropped, not
        // silently mis-assigned.
        val r = BackboneRealizer.realize(
            links("a" to "b", "b" to "c", "c" to "d"),
            mobile("a", "b", "c", "d"),
        )
        val roles = listOf("a", "b", "c", "d").map { r.roleOf(it) }
        val hosts = roles.filterIsInstance<BackboneRealizer.Role.Host>()
        assertEquals(1, hosts.size)
        assertEquals(1, r.dropped.size)
        assertEquals(2, r.realized.size)
        // Exactly one node ends up unassigned (the stranded far leaf).
        assertEquals(1, roles.count { it == BackboneRealizer.Role.None })
    }

    @Test
    fun `higher ranked hub claims a would-be hub as plain client`() {
        // h1 (degree 2) outranks h2; h2 is claimed into h1's star before it can
        // host anything itself — the "edge between two hubs → one joins the
        // other" rule realized through claiming.
        val r = BackboneRealizer.realize(
            links("h1" to "a", "h1" to "h2"),
            mobile("a", "h1", "h2"),
        )
        assertEquals(BackboneRealizer.Role.Host(setOf("a", "h2")), r.roleOf("h1"))
        assertEquals(BackboneRealizer.Role.Client("h1"), r.roleOf("h2"))
        assertTrue(r.dropped.isEmpty())
    }

    @Test
    fun `empty links yields none for everyone`() {
        val r = BackboneRealizer.realize(emptySet(), mobile("a", "b"))
        assertEquals(BackboneRealizer.Role.None, r.roleOf("a"))
        assertEquals(BackboneRealizer.Role.None, r.roleOf("b"))
    }

    @Test
    fun `endpoint missing from modes defaults to mobile rank`() {
        // Hysteresis can hold a link whose peer dropped out of the view; the
        // realizer must not crash and must rank the ghost as MOBILE.
        val r = BackboneRealizer.realize(
            links("known" to "ghost"),
            mapOf("known" to UserMode.STATIC),
        )
        assertEquals(BackboneRealizer.Role.Host(setOf("ghost")), r.roleOf("known"))
        assertEquals(BackboneRealizer.Role.Client("known"), r.roleOf("ghost"))
    }
}
