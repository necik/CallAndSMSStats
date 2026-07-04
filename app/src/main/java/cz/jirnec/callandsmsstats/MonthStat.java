package cz.jirnec.callandsmsstats;

import java.time.YearMonth;
import java.util.Locale;

/**
 * Souhrn statistik hovorů a SMS za jeden kalendářní měsíc.
 */
public class MonthStat {

    public final YearMonth month;
    public long incomingCallSeconds;
    public long outgoingCallSeconds;
    public int incomingCallCount;
    public int outgoingCallCount;
    public int missedCalls;
    public int rejectedCalls;
    public int incomingSms;
    public int outgoingSms;

    public MonthStat(YearMonth month) {
        this.month = month;
    }

    /** Naformátuje sekundy do tvaru HH:MM:SS. */
    public static String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
    }
}
