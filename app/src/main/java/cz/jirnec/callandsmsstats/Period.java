package cz.jirnec.callandsmsstats;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Období, po kterých se statistiky sčítají. Klíčem pro seskupení je vždy
 * počáteční den daného období.
 */
public enum Period {
    DAY,
    WEEK,
    MONTH,
    YEAR;

    /** Počáteční den období, do kterého spadá daný den. */
    public LocalDate startOf(LocalDate date) {
        switch (this) {
            case WEEK:
                return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH:
                return date.withDayOfMonth(1);
            case YEAR:
                return date.withDayOfYear(1);
            case DAY:
            default:
                return date;
        }
    }

    /** Počátek následujícího období (tj. exkluzivní konec tohoto). */
    public LocalDate next(LocalDate start) {
        switch (this) {
            case WEEK:
                return start.plusWeeks(1);
            case MONTH:
                return start.plusMonths(1);
            case YEAR:
                return start.plusYears(1);
            case DAY:
            default:
                return start.plusDays(1);
        }
    }
}
