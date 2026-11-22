package dev.anchor.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictnessTest {
    private val now = System.currentTimeMillis()
    private val day = 86_400_000L
    private val week = Commitment("x", now + 7 * day, now)
    private val rhythm = Commitment("youtube", now + 30 * day, now, listOf(TimeWindow((1..5).toSet(), 8 * 60, 18 * 60)))
    private val base = Policy(commitments = listOf(week, rhythm))

    private fun broken(n: Policy) = Strictness.brokenCommitments(base, n, now)

    @Test fun removingARunningBlockIsRefused() =
        assertEquals(listOf(week), broken(base.copy(commitments = listOf(rhythm))))

    @Test fun shorteningIsRefused() =
        assertEquals(listOf(week), broken(base.copy(commitments = listOf(week.copy(until = week.until - day), rhythm))))

    @Test fun extendingIsFine() =
        assertTrue(broken(base.copy(commitments = listOf(week.copy(until = week.until + day), rhythm))).isEmpty())

    @Test fun narrowingARhythmIsRefused() {
        val narrower = rhythm.copy(windows = listOf(TimeWindow((1..5).toSet(), 9 * 60, 18 * 60)))
        assertEquals(listOf(rhythm), broken(base.copy(commitments = listOf(week, narrower))))
    }

    @Test fun wideningARhythmIsFine() {
        val wider = rhythm.copy(windows = listOf(TimeWindow((1..7).toSet(), 7 * 60, 19 * 60)))
        assertTrue(broken(base.copy(commitments = listOf(week, wider))).isEmpty())
    }

    @Test fun rhythmToAllTheTimeIsFine() =
        assertTrue(broken(base.copy(commitments = listOf(week, rhythm.copy(windows = emptyList())))).isEmpty())

    @Test fun allTheTimeToRhythmIsRefused() {
        val r = week.copy(windows = listOf(TimeWindow((1..7).toSet(), 0, 12 * 60)))
        assertEquals(listOf(week), broken(base.copy(commitments = listOf(r, rhythm))))
    }

    @Test fun expiredBlocksCanGo() {
        val old = Policy(commitments = listOf(Commitment("x", now - 1000, now - day)))
        assertTrue(Strictness.brokenCommitments(old, Policy(), now).isEmpty())
    }

    @Test fun grayscaleLoosenings() {
        val g = Policy(grayscale = true, grayscaleWindows = listOf(TimeWindow(start = 21 * 60, end = 8 * 60)), grayscaleApps = listOf("com.android.chrome"))
        assertTrue(Strictness.isLooser(g, g.copy(grayscale = false)))
        assertTrue(Strictness.isLooser(g, g.copy(grayscaleWindows = listOf(TimeWindow(start = 22 * 60, end = 8 * 60)))))
        assertTrue(Strictness.isLooser(g, g.copy(grayscaleApps = emptyList())))
        assertFalse(Strictness.isLooser(g, g.copy(grayscaleApps = listOf("com.android.chrome", "a.b"))))
    }

    @Test fun settingsLock() {
        val locked = Policy(lockedUntil = now + 10 * day)
        assertTrue(Strictness.isLooser(locked, locked.copy(lockedUntil = now + day)))
        assertFalse(Strictness.isLooser(locked, locked.copy(lockedUntil = now + 20 * day)))
        assertTrue(Strictness.isLooser(Policy(essentials = listOf("a")), Policy(essentials = listOf("a", "b"))))
    }

    @Test fun overnightWindowCoversAfterMidnight() {
        val w = listOf(TimeWindow((1..7).toSet(), 22 * 60, 7 * 60))
        assertTrue(Schedule.contains(w, java.time.LocalDateTime.of(2026, 11, 23, 3, 0)))
        assertFalse(Schedule.contains(w, java.time.LocalDateTime.of(2026, 11, 23, 12, 0)))
    }

    @Test fun addingPartnerCodeIsLoosening() {
        assertTrue(Strictness.isLooser(Policy(), Policy(partnerCode = "salt:abc")))
        assertTrue(Strictness.isLooser(Policy(partnerCode = "salt:abc"), Policy()))
    }

    @Test fun nextChangeFindsWindowEdges() {
        val w = listOf(TimeWindow((1..5).toSet(), 8 * 60, 18 * 60))
        val mon7 = java.time.LocalDateTime.of(2026, 11, 23, 7, 30) // Monday
        assertTrue(Schedule.nextChange(w, mon7) == java.time.LocalDateTime.of(2026, 11, 23, 8, 0))
        val fri19 = java.time.LocalDateTime.of(2026, 11, 27, 19, 0)
        assertTrue(Schedule.nextChange(w, fri19) == java.time.LocalDateTime.of(2026, 11, 30, 8, 0))
    }

    @Test fun rebaseKeepsBackgroundChangesAndMyEdits() {
        val base = Policy(grayscale = false, notifyFilter = false)
        val current = base.copy(apps = mapOf("new.app" to AppRule(mode = Mode.BLOCK)), commitments = listOf(Commitment("x", now + day, now)))
        val draft = base.copy(grayscale = true)
        val m = Merge.rebase(draft, base, current)
        assertTrue(m.grayscale)                                   // my edit
        assertTrue(m.apps["new.app"]?.mode == Mode.BLOCK)         // auto-block made meanwhile
        assertTrue(m.commitments.single().target == "x")          // block extended meanwhile
        assertFalse(Strictness.isLooser(current, m))
    }

    @Test fun pendingSurvivesUnrelatedChange() {
        val o = Policy(grayscale = true, nudgeText = "a")
        val pending = o.copy(grayscale = false)                   // queued loosening
        val n = o.copy(nudgeText = "b")                           // neutral change applied now
        val carried = Merge.carryPending(pending, o, n)
        assertFalse(carried.grayscale); assertTrue(carried.nudgeText == "b")
    }

    @Test fun nudgeAppChangeIsLoosening() {
        val o = Policy(nudgeEnabled = true, nudgeApp = "com.audible.application")
        assertTrue(Strictness.isLooser(o, o.copy(nudgeApp = "com.instagram.android")))
        assertFalse(Strictness.isLooser(o, o.copy(nudgeApp = null)))
    }

    @Test fun moreFavoritesIsNotLoosening() {
        assertFalse(Strictness.isLooser(Policy(), Policy(launcherApps = listOf("a", "b"))))
        val nudging = Policy(nudgeEnabled = true, lockedUntil = Long.MAX_VALUE)
        assertFalse(Strictness.isLooser(nudging, nudging.copy(launcherApps = listOf("com.whatsapp"))))
    }
}
