package com.rumor.mesh.core.filter

import com.rumor.mesh.core.model.FilterAction
import com.rumor.mesh.core.model.FilterEntry
import com.rumor.mesh.core.model.FilterSubscription
import com.rumor.mesh.core.model.FilterSubscriptionMode
import com.rumor.mesh.core.model.KeywordFilterList
import com.rumor.mesh.core.model.MatchKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeywordFilterMatcherTest {

    private fun list(
        publisherId: String = "alice",
        name: String = "test",
        entries: List<FilterEntry>,
        userIdAllowlist: Set<String> = emptySet(),
    ) = KeywordFilterList(publisherId, 1, name, entries, userIdAllowlist, signature = "sig-test")

    private fun sub(
        publisherId: String = "alice",
        name: String = "test",
        enabled: Boolean = true,
        localAllow: Set<String> = emptySet(),
        actionOverrides: Map<String, FilterAction> = emptyMap(),
    ) = FilterSubscription(publisherId, name, FilterSubscriptionMode.CONTINUOUS, localAllow, actionOverrides, enabled)

    @Test
    fun `empty text returns null`() {
        val l = list(entries = listOf(FilterEntry("foo", FilterAction.WARN)))
        assertNull(KeywordFilterMatcher.match("", "bob", listOf(l to sub())))
        assertNull(KeywordFilterMatcher.match(null, "bob", listOf(l to sub())))
    }

    @Test
    fun `substring CI matches regardless of case`() {
        val l = list(entries = listOf(FilterEntry("badword", FilterAction.BLOCK)))
        val r = KeywordFilterMatcher.match("This is a BadWord here", "bob", listOf(l to sub()))
        assertNotNull(r)
        assertEquals(FilterAction.BLOCK, r.action)
        assertEquals(1, r.hits.size)
    }

    @Test
    fun `substring CS respects case`() {
        val l = list(entries = listOf(FilterEntry("Bad", FilterAction.WARN, MatchKind.SUBSTRING_CS)))
        assertNull(KeywordFilterMatcher.match("this is bad", "bob", listOf(l to sub())))
        assertNotNull(KeywordFilterMatcher.match("this is Bad", "bob", listOf(l to sub())))
    }

    @Test
    fun `word CI requires word boundaries`() {
        val l = list(entries = listOf(FilterEntry("ass", FilterAction.WARN, MatchKind.WORD_CI)))
        // "pass" should NOT match the substring "ass" under WORD_CI.
        assertNull(KeywordFilterMatcher.match("please pass the salt", "bob", listOf(l to sub())))
        // Standalone word should match.
        assertNotNull(KeywordFilterMatcher.match("don't be an ass", "bob", listOf(l to sub())))
        // Punctuation counts as a boundary.
        assertNotNull(KeywordFilterMatcher.match("ass!", "bob", listOf(l to sub())))
    }

    @Test
    fun `BLOCK beats WARN when multiple lists match`() {
        val l1 = list(publisherId = "p1", name = "warn-list", entries = listOf(FilterEntry("foo", FilterAction.WARN)))
        val l2 = list(publisherId = "p2", name = "block-list", entries = listOf(FilterEntry("foo", FilterAction.BLOCK)))
        val r = KeywordFilterMatcher.match("hello foo bar", "bob", listOf(l1 to sub("p1", "warn-list"), l2 to sub("p2", "block-list")))
        assertNotNull(r)
        assertEquals(FilterAction.BLOCK, r.action)
        assertEquals(2, r.hits.size)
    }

    @Test
    fun `publisher userIdAllowlist exempts that sender from the entire list`() {
        val l = list(
            entries = listOf(FilterEntry("slur", FilterAction.BLOCK)),
            userIdAllowlist = setOf("in-group-member"),
        )
        assertNotNull(KeywordFilterMatcher.match("here's a slur", "outsider", listOf(l to sub())))
        assertNull(KeywordFilterMatcher.match("here's a slur", "in-group-member", listOf(l to sub())))
    }

    @Test
    fun `subscriber localAllowlistAdditions compose with publisher allowlist`() {
        val l = list(
            entries = listOf(FilterEntry("blocked-word", FilterAction.BLOCK)),
            userIdAllowlist = setOf("publisher-exempt"),
        )
        val s = sub(localAllow = setOf("my-friend"))
        assertNull(KeywordFilterMatcher.match("blocked-word", "my-friend", listOf(l to s)))
        assertNull(KeywordFilterMatcher.match("blocked-word", "publisher-exempt", listOf(l to s)))
        assertNotNull(KeywordFilterMatcher.match("blocked-word", "stranger", listOf(l to s)))
    }

    @Test
    fun `subscriber actionOverrides promote WARN to BLOCK`() {
        val l = list(entries = listOf(FilterEntry("mild", FilterAction.WARN)))
        val s = sub(actionOverrides = mapOf("mild" to FilterAction.BLOCK))
        val r = KeywordFilterMatcher.match("mild word", "bob", listOf(l to s))
        assertNotNull(r)
        assertEquals(FilterAction.BLOCK, r.action)
    }

    @Test
    fun `subscriber actionOverrides demote BLOCK to WARN`() {
        val l = list(entries = listOf(FilterEntry("strict", FilterAction.BLOCK)))
        val s = sub(actionOverrides = mapOf("strict" to FilterAction.WARN))
        val r = KeywordFilterMatcher.match("strict word", "bob", listOf(l to s))
        assertNotNull(r)
        assertEquals(FilterAction.WARN, r.action)
    }

    @Test
    fun `disabled subscription is skipped entirely`() {
        val l = list(entries = listOf(FilterEntry("foo", FilterAction.BLOCK)))
        assertNull(KeywordFilterMatcher.match("foo bar", "bob", listOf(l to sub(enabled = false))))
    }

    @Test
    fun `no match returns null`() {
        val l = list(entries = listOf(FilterEntry("xyz", FilterAction.BLOCK)))
        assertNull(KeywordFilterMatcher.match("hello world", "bob", listOf(l to sub())))
    }

    @Test
    fun `warnLabels collected and deduplicated`() {
        val l1 = list(publisherId = "p1", name = "l1", entries = listOf(FilterEntry("foo", FilterAction.WARN, warnLabel = "sexual content")))
        val l2 = list(publisherId = "p2", name = "l2", entries = listOf(FilterEntry("bar", FilterAction.WARN, warnLabel = "sexual content")))
        val l3 = list(publisherId = "p3", name = "l3", entries = listOf(FilterEntry("baz", FilterAction.WARN, warnLabel = "violence")))
        val r = KeywordFilterMatcher.match("foo bar baz", "bob", listOf(
            l1 to sub("p1", "l1"),
            l2 to sub("p2", "l2"),
            l3 to sub("p3", "l3"),
        ))
        assertNotNull(r)
        assertEquals(setOf("sexual content", "violence"), r.warnLabels.toSet())
        assertEquals(2, r.warnLabels.size, "duplicate labels should be deduplicated")
    }

    @Test
    fun `multiple hits on different entries within one list`() {
        val l = list(entries = listOf(
            FilterEntry("foo", FilterAction.WARN),
            FilterEntry("bar", FilterAction.BLOCK),
        ))
        val r = KeywordFilterMatcher.match("foo and bar", "bob", listOf(l to sub()))
        assertNotNull(r)
        assertEquals(FilterAction.BLOCK, r.action)
        assertEquals(2, r.hits.size)
    }
}
