package cz.jirnec.callandsmsstats;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Souhrn statistik hovorů a SMS za jedno období (den/týden/měsíc/rok).
 * Období je určeno svým počátečním dnem a granularitou.
 */
public class PeriodStat {

    public final LocalDate start;
    public final Period period;
    public long incomingCallSeconds;
    public long outgoingCallSeconds;
    public int incomingCallCount;
    public int outgoingCallCount;
    public int missedCalls;
    public int rejectedCalls;
    public int incomingSms;
    public int outgoingSms;
    /** Mobilní data (přijatá + odeslaná) v bajtech; -1 = neznámo/nedostupné. */
    public long mobileDataBytes = -1;
    /** Byla už mobilní data pro toto období dotažena? (líné načítání) */
    public boolean dataLoaded = false;
    /** Probíhá právě dotažení dat? (aby se nespouštělo vícekrát) */
    public boolean dataLoading = false;

    public PeriodStat(LocalDate start, Period period) {
        this.start = start;
        this.period = period;
    }

    /** Naformátuje sekundy do tvaru HH:MM:SS. */
    public static String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
    }

    /** Naformátuje počet bajtů do čitelné podoby (B/kB/MB/GB). */
    public static String formatDataSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.getDefault(), "%.1f kB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.getDefault(), "%.1f MB", mb);
        }
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0);
    }
}
