package cz.jirnec.callandsmsstats;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Telephony;

import androidx.core.content.ContextCompat;

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

    /**
     * Načte jednotlivé hovory a SMS daného měsíce (od nejnovějšího), tedy přesně
     * záznamy, ze kterých vznikl souhrn pro tento měsíc.
     */
    public List<DetailEntry> loadEntriesForMonth(YearMonth month) {
        ZoneId zone = ZoneId.systemDefault();
        long start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();

        List<DetailEntry> entries = new ArrayList<>();
        readCallEntries(entries, start, end);
        readSmsEntries(entries, start, end);
        entries.sort(Comparator.comparingLong((DetailEntry e) -> e.timestamp).reversed());
        return entries;
    }

    private void readCallEntries(List<DetailEntry> out, long start, long end) {
        String[] projection = {
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME
        };
        String selection = CallLog.Calls.DATE + " >= ? AND " + CallLog.Calls.DATE + " < ?";
        String[] args = {Long.toString(start), Long.toString(end)};

        try (Cursor c = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI, projection, selection, args, null)) {
            if (c == null) {
                return;
            }
            int typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE);
            int dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE);
            int durationIdx = c.getColumnIndexOrThrow(CallLog.Calls.DURATION);
            int numberIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER);
            int nameIdx = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME);

            while (c.moveToNext()) {
                int kind;
                switch (c.getInt(typeIdx)) {
                    case CallLog.Calls.INCOMING_TYPE:
                        kind = DetailEntry.INCOMING_CALL;
                        break;
                    case CallLog.Calls.OUTGOING_TYPE:
                        kind = DetailEntry.OUTGOING_CALL;
                        break;
                    case CallLog.Calls.MISSED_TYPE:
                        kind = DetailEntry.MISSED_CALL;
                        break;
                    case CallLog.Calls.REJECTED_TYPE:
                        kind = DetailEntry.REJECTED_CALL;
                        break;
                    default:
                        continue;
                }
                DetailEntry e = new DetailEntry();
                e.kind = kind;
                e.timestamp = c.getLong(dateIdx);
                e.durationSeconds = c.getLong(durationIdx);
                String name = c.getString(nameIdx);
                e.contact = (name != null && !name.isEmpty()) ? name : c.getString(numberIdx);
                out.add(e);
            }
        }
    }

    private void readSmsEntries(List<DetailEntry> out, long start, long end) {
        boolean canReadContacts = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        Map<String, String> nameCache = new HashMap<>();

        String[] projection = {
                Telephony.Sms.TYPE,
                Telephony.Sms.DATE,
                Telephony.Sms.ADDRESS
        };
        String selection = Telephony.Sms.DATE + " >= ? AND " + Telephony.Sms.DATE + " < ?";
        String[] args = {Long.toString(start), Long.toString(end)};

        try (Cursor c = context.getContentResolver().query(
                Telephony.Sms.CONTENT_URI, projection, selection, args, null)) {
            if (c == null) {
                return;
            }
            int typeIdx = c.getColumnIndexOrThrow(Telephony.Sms.TYPE);
            int dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE);
            int addressIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS);

            while (c.moveToNext()) {
                int kind;
                switch (c.getInt(typeIdx)) {
                    case Telephony.Sms.MESSAGE_TYPE_INBOX:
                        kind = DetailEntry.INCOMING_SMS;
                        break;
                    case Telephony.Sms.MESSAGE_TYPE_SENT:
                        kind = DetailEntry.OUTGOING_SMS;
                        break;
                    default:
                        continue;
                }
                DetailEntry e = new DetailEntry();
                e.kind = kind;
                e.timestamp = c.getLong(dateIdx);
                e.contact = resolveSmsContact(c.getString(addressIdx), canReadContacts, nameCache);
                out.add(e);
            }
        }
    }

    /**
     * Vrátí jméno z adresáře, pokud je adresa SMS telefonní číslo uložené v kontaktech.
     * Textové ID odesílatele (např. "Vodafone") ani neznámé číslo PhoneLookup nenajde,
     * takže se vrátí původní adresa beze změny.
     */
    private String resolveSmsContact(String address, boolean canReadContacts,
                                     Map<String, String> cache) {
        if (address == null || address.isEmpty() || !canReadContacts) {
            return address;
        }
        if (cache.containsKey(address)) {
            return cache.get(address);
        }

        String resolved = address;
        Uri uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address));
        try (Cursor c = context.getContentResolver().query(
                uri, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.isEmpty()) {
                    resolved = name;
                }
            }
        } catch (Exception ignored) {
            // U nečíselných adres může lookup selhat – ponecháme původní text.
        }

        cache.put(address, resolved);
        return resolved;
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
