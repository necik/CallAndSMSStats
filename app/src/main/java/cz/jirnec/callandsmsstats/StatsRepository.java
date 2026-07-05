package cz.jirnec.callandsmsstats;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Process;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Telephony;

import androidx.core.content.ContextCompat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Načítá záznamy z deníku hovorů a SMS a agreguje je po zvoleném období.
 */
public class StatsRepository {

    private final Context context;

    public StatsRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Vrátí statistiky za zvolené období, seřazené od nejnovějšího do nejstaršího.
     * Musí běžet mimo hlavní vlákno (dotazy ContentResolveru mohou trvat).
     */
    public List<PeriodStat> loadStats(Period period) {
        Map<LocalDate, PeriodStat> byPeriod = new HashMap<>();
        ZoneId zone = ZoneId.systemDefault();

        readCalls(byPeriod, zone, period);
        readSms(byPeriod, zone, period);

        if (byPeriod.isEmpty()) {
            return new ArrayList<>();
        }

        // Souvislá řada období od dneška (resp. nejnovějších dat) po nejstarší data;
        // období bez aktivity se zobrazí s nulami.
        LocalDate oldest = Collections.min(byPeriod.keySet());
        LocalDate newest = period.startOf(LocalDate.now(zone));
        LocalDate newestData = Collections.max(byPeriod.keySet());
        if (newestData.isAfter(newest)) {
            newest = newestData;
        }

        List<PeriodStat> result = new ArrayList<>();
        for (LocalDate cur = newest; !cur.isBefore(oldest); cur = period.startOf(cur.minusDays(1))) {
            PeriodStat stat = byPeriod.get(cur);
            result.add(stat != null ? stat : new PeriodStat(cur, period));
        }
        // Mobilní data se nedotahují tady – načítají se líně (jen pro zobrazená období),
        // viz queryMobileData(). Pro export slouží fillMobileData().
        return result;
    }

    /** Zda má aplikace udělený „Usage access" (nutný pro čtení spotřeby mobilních dat). */
    public static boolean hasUsageAccess(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) {
            return false;
        }
        int mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Celková mobilní data zařízení (přijatá + odeslaná) za jedno období v bajtech,
     * nebo -1 při chybě / bez přístupu. Používá se pro líné načítání jednotlivých položek.
     */
    @SuppressWarnings("deprecation")
    public long queryMobileData(PeriodStat period) {
        NetworkStatsManager nsm =
                (NetworkStatsManager) context.getSystemService(Context.NETWORK_STATS_SERVICE);
        if (nsm == null) {
            return -1;
        }
        ZoneId zone = ZoneId.systemDefault();
        long start = period.start.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = period.period.next(period.start).atStartOfDay(zone).toInstant().toEpochMilli();
        try {
            NetworkStats.Bucket bucket = nsm.querySummaryForDevice(
                    ConnectivityManager.TYPE_MOBILE, null, start, end);
            if (bucket != null) {
                return bucket.getRxBytes() + bucket.getTxBytes();
            }
        } catch (Exception e) {
            // Bez přístupu nebo chyba dotazu – vrátíme -1.
        }
        return -1;
    }

    /** Doplní mobilní data ke všem obdobím najednou – pro export. */
    public void fillMobileData(List<PeriodStat> periods) {
        for (PeriodStat p : periods) {
            p.mobileDataBytes = queryMobileData(p);
            p.dataLoaded = true;
        }
    }

    /**
     * Načte jednotlivé hovory a SMS v daném časovém rozsahu (od nejnovějšího),
     * tedy přesně záznamy, ze kterých vznikl souhrn pro dané období.
     */
    public List<DetailEntry> loadEntriesInRange(long startMillis, long endMillis) {
        List<DetailEntry> entries = new ArrayList<>();
        readCallEntries(entries, startMillis, endMillis);
        readSmsEntries(entries, startMillis, endMillis);
        entries.sort(Comparator.comparingLong((DetailEntry e) -> e.timestamp).reversed());
        return entries;
    }

    /** Načte všechny hovory a SMS napříč všemi měsíci (od nejnovějšího) – pro export. */
    public List<DetailEntry> loadAllEntries() {
        List<DetailEntry> entries = new ArrayList<>();
        readCallEntries(entries, 0L, Long.MAX_VALUE);
        readSmsEntries(entries, 0L, Long.MAX_VALUE);
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

    private void readCalls(Map<LocalDate, PeriodStat> byPeriod, ZoneId zone, Period period) {
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
                PeriodStat stat = getOrCreate(byPeriod, zone, period, dateMillis);

                switch (type) {
                    case CallLog.Calls.INCOMING_TYPE:
                        stat.incomingCallSeconds += duration;
                        stat.incomingCallCount += 1;
                        break;
                    case CallLog.Calls.OUTGOING_TYPE:
                        stat.outgoingCallSeconds += duration;
                        stat.outgoingCallCount += 1;
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

    private void readSms(Map<LocalDate, PeriodStat> byPeriod, ZoneId zone, Period period) {
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
                PeriodStat stat = getOrCreate(byPeriod, zone, period, dateMillis);

                if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) {
                    stat.incomingSms += 1;
                } else if (type == Telephony.Sms.MESSAGE_TYPE_SENT) {
                    stat.outgoingSms += 1;
                }
            }
        }
    }

    private PeriodStat getOrCreate(Map<LocalDate, PeriodStat> byPeriod, ZoneId zone,
                                   Period period, long epochMillis) {
        LocalDate date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate();
        LocalDate key = period.startOf(date);
        PeriodStat stat = byPeriod.get(key);
        if (stat == null) {
            stat = new PeriodStat(key, period);
            byPeriod.put(key, stat);
        }
        return stat;
    }
}
