package dev.anchor.policy

import java.time.LocalDateTime

object Schedule {
    const val WEEK = 7 * 1440

    fun minuteOfWeek(t: LocalDateTime) = (t.dayOfWeek.value - 1) * 1440 + t.hour * 60 + t.minute

    private fun length(w: TimeWindow) = if (w.end > w.start) w.end - w.start else w.end + 1440 - w.start

    fun contains(windows: List<TimeWindow>, t: LocalDateTime): Boolean {
        val m = minuteOfWeek(t)
        return windows.any { w ->
            w.days.any { d ->
                val start = (d - 1) * 1440 + w.start
                Math.floorMod(m - start, WEEK) < length(w)
            }
        }
    }

    /** Minutes of the week covered by [windows]; empty list covers everything if [emptyMeansAll]. */
    fun coverage(windows: List<TimeWindow>, emptyMeansAll: Boolean): BooleanArray {
        val a = BooleanArray(WEEK)
        if (windows.isEmpty()) {
            if (emptyMeansAll) a.fill(true)
            return a
        }
        for (w in windows) for (d in w.days) {
            val base = (d - 1) * 1440 + w.start
            for (i in 0 until length(w)) a[(base + i) % WEEK] = true
        }
        return a
    }

    /** When [windows] next switch on or off after [now]; null if they never change. */
    fun nextChange(windows: List<TimeWindow>, now: LocalDateTime): LocalDateTime? {
        if (windows.isEmpty()) return null
        val cov = coverage(windows, false)
        val m = minuteOfWeek(now)
        for (i in 1..WEEK) if (cov[(m + i) % WEEK] != cov[m]) return now.withSecond(0).withNano(0).plusMinutes(i.toLong())
        return null
    }

    fun fmt(min: Int) = if (min == 1440) "24:00" else "%02d:%02d".format(min / 60 % 24, min % 60)

    fun parse(s: String): Int? {
        val m = Regex("""^\s*(\d{1,2})(?::(\d{2}))?\s*$""").find(s) ?: return null
        val h = m.groupValues[1].toInt()
        val mm = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (h > 24 || mm > 59 || (h == 24 && mm > 0)) return null
        return h * 60 + mm
    }

    private val DAY = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    fun describe(w: TimeWindow): String {
        val days = when (w.days) {
            (1..7).toSet() -> "daily"
            (1..5).toSet() -> "Mo–Fr"
            setOf(6, 7) -> "Sa–Su"
            else -> w.days.sorted().joinToString(" ") { DAY[it - 1] }
        }
        return "$days ${fmt(w.start)}–${fmt(w.end)}"
    }
}
