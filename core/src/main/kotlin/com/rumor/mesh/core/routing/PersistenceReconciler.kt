package com.rumor.mesh.core.routing

/**
 * O98 stability layer. [PersistencePlanner.plan] is stateless and recomputes
 * the whole backbone every time the mesh view changes — but the view churns
 * constantly (a peer blips out of BLE range for one scan, a mode advertisement
 * arrives a round late), and tearing down / re-establishing a Wi-Fi Direct
 * group costs ~60s of discovery + negotiation + DHCP. Acting on every raw plan
 * would thrash the radio.
 *
 * This reconciler applies hysteresis: new links are added immediately (gaining
 * connectivity is cheap and urgent), but a link that drops out of the target
 * plan is only torn down after it has been absent for [holdRounds] consecutive
 * reconciliations. A brief blip that resolves before the streak expires leaves
 * the live link untouched. Asymmetric on purpose — fast to connect, slow to
 * disconnect — because a spurious teardown is far more expensive than briefly
 * holding a link the plan no longer wants.
 *
 * Not thread-safe; drive it from a single scheduler/transport coroutine.
 */
class PersistenceReconciler(private val holdRounds: Int = 3) {

    private val active = LinkedHashSet<Link>()
    private val absentStreak = HashMap<Link, Int>()

    /** Links currently held open (a copy; safe to iterate). */
    fun activeLinks(): Set<Link> = LinkedHashSet(active)

    /**
     * Fold [target] into the running state and return what actually changed.
     * Idempotent: reconciling the same target with no view change yields empty
     * deltas once any pending teardowns have expired.
     */
    fun reconcile(target: BackbonePlan): PlanDelta {
        val wanted = target.links
        val toAdd = LinkedHashSet<Link>()
        val toRemove = LinkedHashSet<Link>()

        // Additions are immediate; a wanted link also clears any teardown streak.
        for (link in wanted) {
            absentStreak.remove(link)
            if (active.add(link)) toAdd += link
        }

        // Removals age through the hold window before firing.
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val link = iterator.next()
            if (link in wanted) continue
            val streak = (absentStreak[link] ?: 0) + 1
            if (streak >= holdRounds) {
                iterator.remove()
                absentStreak.remove(link)
                toRemove += link
            } else {
                absentStreak[link] = streak
            }
        }

        return PlanDelta(toAdd = toAdd, toRemove = toRemove)
    }

    /** Force-drop a link immediately (e.g. peer confirmed gone from BLE). */
    fun forget(link: Link): Boolean {
        absentStreak.remove(link)
        return active.remove(link)
    }
}

/** Links to open ([toAdd]) and close ([toRemove]) since the last reconcile. */
data class PlanDelta(
    val toAdd: Set<Link>,
    val toRemove: Set<Link>,
) {
    val isEmpty: Boolean get() = toAdd.isEmpty() && toRemove.isEmpty()
}
