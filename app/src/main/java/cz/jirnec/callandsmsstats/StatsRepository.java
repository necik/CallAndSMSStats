package cz.jirnec.callandsmsstats;

import android.content.Context;
import android.database.Cursor;
import android.provider.CallLog;
import android.provider.Telephony;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Načítá záznamy z deníku hovorů a SMS a agreguje je po kalendářních měsících.
 */
public class StatsRepository {

    private final Context context;

    public StatsRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Vrátí statistiky seřazené od nejnovějšího měsíce do nejstaršího.
     * Musí běžet mimo hlavní vlákno (dotazy ContentResolveru mohou trvat).
     */
    public List<MonthStat> loadStats() {
        Map<YearMonth, MonthStat> byMonth = new HashMap<>();
        ZoneId zone = ZoneId.systemDefault();

        readCalls(byMonth, zone);
        readSms(byMonth, zone);

        List<MonthStat> result = new ArrayList<>(byMonth.values());
        result.sort(Comparator.comparing((MonthStat s) -> s.month).reversed());
        return result;
    }

    private void readCalls(Map<YearMonth, MonthStat> byMonth, ZoneId zone) {
        String[] projection = {
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
        };
        try (Cursor c = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI, projection, null, null, null)) {
            if (c == null) {
                return;
            }
            int typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE);
            int dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE);
            int durationIdx = c.getColumnIndexOrThrow(CallLog.Calls.DURATION);

            while (c.moveToNext()) {
                int type = c.getInt(typeIdx);
                long dateMillis = c.getLong(dateIdx);
                long duration = c.getLong(durationIdx);
                MonthStat stat = getOrCreate(byMonth, toYearMonth(dateMillis, zone));

                switch (type) {
                    case CallLog.Calls.INCOMING_TYPE:
                        stat.incomingCallSeconds += duration;
                        break;
                    case CallLog.Calls.OUTGOING_TYPE:
                        stat.outgoingCallSeconds += duration;
                        break;
                    case CallLog.Calls.MISSED_TYPE:
                        stat.missedCalls += 1;
                        break;
                    case CallLog.Calls.REJECTED_TYPE:
                        stat.rejectedCalls += 1;
                        break;
                    default:
                        // Hlasová schránka, blokované apod. – ignorujeme.
                        break;
                }
            }
        }
    }

    private void readSms(Map<YearMonth, MonthStat> byMonth, ZoneId zone) {
        String[] projection = {
                Telephony.Sms.TYPE,
                Telephony.Sms.DATE
        };
        try (Cursor c = context.getContentResolver().query(
                Telephony.Sms.CONTENT_URI, projection, null, null, null)) {
            if (c == null) {
                return;
            }
            int typeIdx = c.getColumnIndexOrThrow(Telephony.Sms.TYPE);
            int dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE);

            while (c.moveToNext()) {
                int type = c.getInt(typeIdx);
                long dateMillis = c.getLong(dateIdx);
                MonthStat stat = getOrCreate(byMonth, toYearMonth(dateMillis, zone));

                if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) {
                    stat.incomingSms += 1;
                } else if (type == Telephony.Sms.MESSAGE_TYPE_SENT) {
                    stat.outgoingSms += 1;
                }
            }
        }
    }

    private YearMonth toYearMonth(long epochMillis, ZoneId zone) {
        return YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(zone));
    }

    private MonthStat getOrCreate(Map<YearMonth, MonthStat> byMonth, YearMonth month) {
        MonthStat stat = byMonth.get(month);
        if (stat == null) {
            stat = new MonthStat(month);
            byMonth.put(month, stat);
        }
        return stat;
    }
}
