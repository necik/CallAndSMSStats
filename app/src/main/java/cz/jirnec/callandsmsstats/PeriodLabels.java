package cz.jirnec.callandsmsstats;

import android.content.Context;
import android.text.format.DateUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Popisky období pro zobrazení – respektují národní prostředí (u dne a týdne
 * i systémový formát data přes {@link DateUtils}).
 */
public final class PeriodLabels {

    private PeriodLabels() {
    }

    public static String label(Context context, Period period, LocalDate start) {
        ZoneId zone = ZoneId.systemDefault();
        switch (period) {
            case DAY: {
                long millis = millis(start, zone);
                return DateUtils.formatDateTime(context, millis,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
                                | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY);
            }
            case WEEK: {
                // Konec = poslední milisekunda neděle. (Půlnoc neděle by DateUtils
                // vykreslila jako sobotu, protože ji bere jako začátek dne.)
                long startMs = millis(start, zone);
                long endMs = millis(period.next(start), zone) - 1;
                return DateUtils.formatDateRange(context, startMs, endMs,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR);
            }
            case YEAR:
                return String.valueOf(start.getYear());
            case MONTH:
            default: {
                Locale locale = Locale.getDefault();
                String text = start.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale));
                return text.substring(0, 1).toUpperCase(locale) + text.substring(1);
            }
        }
    }

    private static long millis(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }
}
