package cz.jirnec.callandsmsstats;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

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

    /** Jen skutečné MMS: send-req (128) a retrieve-conf (132); ne doručenky/notifikace. */
    private static final String MMS_TYPE_FILTER = Telephony.Mms.MESSAGE_TYPE + " IN (128,132)";

    private final Context context;

    public StatsRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Vrátí statistiky za zvolené období, seřazené od nejnovějšího do nejstaršího.
     * Musí běžet mimo hlavní vlákno (dotazy ContentResolveru mohou trvat).
     */
    public List<PeriodStat> loadStats(Period period, Integer simSubId) {
        Map<LocalDate, PeriodStat> byPeriod = new HashMap<>();
        ZoneId zone = ZoneId.systemDefault();
        Map<String, Integer> accountToSub = simSubId != null ? phoneAccountToSub() : null;

        readCalls(byPeriod, zone, period, simSubId, accountToSub);
        readSms(byPeriod, zone, period, simSubId);
        readMms(byPeriod, zone, period, simSubId);

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
     * Rozpad spotřeby mobilních dat po aplikacích za daný rozsah (sestupně).
     * Vyžaduje Usage access; bez něj / při chybě vrátí prázdný seznam.
     */
    @SuppressWarnings("deprecation")
    public List<AppDataUsage> loadMobileDataByApp(long startMillis, long endMillis) {
        List<AppDataUsage> result = new ArrayList<>();
        NetworkStatsManager nsm =
                (NetworkStatsManager) context.getSystemService(Context.NETWORK_STATS_SERVICE);
        if (nsm == null) {
            return result;
        }

        Map<Integer, Long> bytesByUid = new HashMap<>();
        NetworkStats stats = null;
        try {
            stats = nsm.querySummary(ConnectivityManager.TYPE_MOBILE, null, startMillis, endMillis);
            NetworkStats.Bucket bucket = new NetworkStats.Bucket();
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket);
                int uid = bucket.getUid();
                long bytes = bucket.getRxBytes() + bucket.getTxBytes();
                Long current = bytesByUid.get(uid);
                bytesByUid.put(uid, (current == null ? 0L : current) + bytes);
            }
        } catch (Exception e) {
            return result;
        } finally {
            if (stats != null) {
                stats.close();
            }
        }

        PackageManager pm = context.getPackageManager();
        for (Map.Entry<Integer, Long> entry : bytesByUid.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            result.add(new AppDataUsage(appLabelForUid(pm, entry.getKey()), entry.getValue()));
        }
        result.sort((a, b) -> Long.compare(b.bytes, a.bytes));
        return result;
    }

    private String appLabelForUid(PackageManager pm, int uid) {
        switch (uid) {
            case NetworkStats.Bucket.UID_REMOVED:
                return context.getString(R.string.data_removed_apps);
            case NetworkStats.Bucket.UID_TETHERING:
                return context.getString(R.string.data_tethering);
            default:
                break;
        }
        if (uid == Process.SYSTEM_UID) {
            return context.getString(R.string.data_android_os);
        }
        String[] packages = pm.getPackagesForUid(uid);
        if (packages != null && packages.length > 0) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(packages[0], 0);
                return pm.getApplicationLabel(info).toString();
            } catch (PackageManager.NameNotFoundException ignored) {
                return packages[0];
            }
        }
        String name = pm.getNameForUid(uid);
        return name != null ? name : "UID " + uid;
    }

    /**
     * Načte jednotlivé hovory a SMS v daném časovém rozsahu (od nejnovějšího),
     * tedy přesně záznamy, ze kterých vznikl souhrn pro dané období.
     */
    public List<DetailEntry> loadEntriesInRange(long startMillis, long endMillis, Integer simSubId) {
        Map<String, Integer> accountToSub = simSubId != null ? phoneAccountToSub() : null;
        List<DetailEntry> entries = new ArrayList<>();
        readCallEntries(entries, startMillis, endMillis, simSubId, accountToSub);
        readSmsEntries(entries, startMillis, endMillis, simSubId);
        readMmsEntries(entries, startMillis, endMillis, simSubId);
        entries.sort(Comparator.comparingLong((DetailEntry e) -> e.timestamp).reversed());
        return entries;
    }

    /** Načte všechny hovory, SMS a MMS napříč obdobími (od nejnovějšího) – pro export. */
    public List<DetailEntry> loadAllEntries(Integer simSubId) {
        return loadEntriesInRange(0L, Long.MAX_VALUE, simSubId);
    }

    private void readCallEntries(List<DetailEntry> out, long start, long end,
                                 Integer simSubId, Map<String, Integer> accountToSub) {
        String[] projection = {
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.PHONE_ACCOUNT_ID
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
            int accountIdx = c.getColumnIndexOrThrow(CallLog.Calls.PHONE_ACCOUNT_ID);

            while (c.moveToNext()) {
                if (!callMatchesSim(c.getString(accountIdx), simSubId, accountToSub)) {
                    continue;
                }
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

    private void readSmsEntries(List<DetailEntry> out, long start, long end, Integer simSubId) {
        boolean canReadContacts = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        Map<String, String> nameCache = new HashMap<>();

        String[] projection = {
                Telephony.Sms.TYPE,
                Telephony.Sms.DATE,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.SUBSCRIPTION_ID
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
            int subIdx = c.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID);

            while (c.moveToNext()) {
                if (simSubId != null && c.getInt(subIdx) != simSubId) {
                    continue;
                }
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

    private void readMmsEntries(List<DetailEntry> out, long start, long end, Integer simSubId) {
        boolean canReadContacts = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        Map<String, String> nameCache = new HashMap<>();

        String[] projection = {
                Telephony.Mms._ID,
                Telephony.Mms.MESSAGE_BOX,
                Telephony.Mms.DATE,
                Telephony.Mms.SUBSCRIPTION_ID
        };
        // MMS DATE je v sekundách → převádíme rozsah na sekundy pro dotaz.
        // m_type IN (128,132) = jen skutečné MMS (send-req / retrieve-conf);
        // vynecháme doručenky (134) a notifikace (130).
        String selection = Telephony.Mms.DATE + " >= ? AND " + Telephony.Mms.DATE + " < ?"
                + " AND " + MMS_TYPE_FILTER;
        String[] args = {Long.toString(toSeconds(start)), Long.toString(toSeconds(end))};

        try (Cursor c = context.getContentResolver().query(
                Telephony.Mms.CONTENT_URI, projection, selection, args, null)) {
            if (c == null) {
                return;
            }
            int idIdx = c.getColumnIndexOrThrow(Telephony.Mms._ID);
            int boxIdx = c.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX);
            int dateIdx = c.getColumnIndexOrThrow(Telephony.Mms.DATE);
            int subIdx = c.getColumnIndexOrThrow(Telephony.Mms.SUBSCRIPTION_ID);

            while (c.moveToNext()) {
                if (simSubId != null && c.getInt(subIdx) != simSubId) {
                    continue;
                }
                int box = c.getInt(boxIdx);
                int kind;
                boolean inbox;
                if (box == Telephony.Mms.MESSAGE_BOX_INBOX) {
                    kind = DetailEntry.INCOMING_MMS;
                    inbox = true;
                } else if (box == Telephony.Mms.MESSAGE_BOX_SENT) {
                    kind = DetailEntry.OUTGOING_MMS;
                    inbox = false;
                } else {
                    continue;
                }
                DetailEntry e = new DetailEntry();
                e.kind = kind;
                e.timestamp = c.getLong(dateIdx) * 1000L; // sekundy → ms
                String address = mmsAddress(c.getLong(idIdx), inbox);
                e.contact = resolveSmsContact(address, canReadContacts, nameCache);
                out.add(e);
            }
        }
    }

    /** Adresa MMS (odesílatel u příchozí, příjemce u odchozí) z pod-tabulky addr. */
    private String mmsAddress(long mmsId, boolean inbox) {
        // Typy z PduHeaders: 137 = FROM, 151 = TO.
        int wantType = inbox ? 137 : 151;
        Uri uri = Uri.parse("content://mms/" + mmsId + "/addr");
        try (Cursor c = context.getContentResolver().query(
                uri, new String[]{"address", "type"}, null, null, null)) {
            if (c != null) {
                int addrIdx = c.getColumnIndexOrThrow("address");
                int typeIdx = c.getColumnIndexOrThrow("type");
                while (c.moveToNext()) {
                    String addr = c.getString(addrIdx);
                    if (c.getInt(typeIdx) == wantType
                            && addr != null && !"insert-address-token".equals(addr)) {
                        return addr;
                    }
                }
            }
        } catch (Exception ignored) {
            // Adresu nelze získat – necháme prázdnou.
        }
        return null;
    }

    private long toSeconds(long millis) {
        return millis == Long.MAX_VALUE ? Long.MAX_VALUE : millis / 1000L;
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

    private void readCalls(Map<LocalDate, PeriodStat> byPeriod, ZoneId zone, Period period,
                           Integer simSubId, Map<String, Integer> accountToSub) {
        String[] projection = {
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.PHONE_ACCOUNT_ID
        };
        try (Cursor c = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI, projection, null, null, null)) {
            if (c == null) {
                return;
            }
            int typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE);
            int dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE);
            int durationIdx = c.getColumnIndexOrThrow(CallLog.Calls.DURATION);
            int accountIdx = c.getColumnIndexOrThrow(CallLog.Calls.PHONE_ACCOUNT_ID);

            while (c.moveToNext()) {
                if (!callMatchesSim(c.getString(accountIdx), simSubId, accountToSub)) {
                    continue;
                }
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

    private void readSms(Map<LocalDate, PeriodStat> byPeriod, ZoneId zone, Period period,
                         Integer simSubId) {
        String[] projection = {
                Telephony.Sms.TYPE,
                Telephony.Sms.DATE,
                Telephony.Sms.SUBSCRIPTION_ID
        };
        try (Cursor c = context.getContentResolver().query(
                Telephony.Sms.CONTENT_URI, projection, null, null, null)) {
            if (c == null) {
                return;
            }
            int typeIdx = c.getColumnIndexOrThrow(Telephony.Sms.TYPE);
            int dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE);
            int subIdx = c.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID);

            while (c.moveToNext()) {
                if (simSubId != null && c.getInt(subIdx) != simSubId) {
                    continue;
                }
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

    private void readMms(Map<LocalDate, PeriodStat> byPeriod, ZoneId zone, Period period,
                         Integer simSubId) {
        String[] projection = {
                Telephony.Mms.MESSAGE_BOX,
                Telephony.Mms.DATE,
                Telephony.Mms.SUBSCRIPTION_ID
        };
        try (Cursor c = context.getContentResolver().query(
                Telephony.Mms.CONTENT_URI, projection, MMS_TYPE_FILTER, null, null)) {
            if (c == null) {
                return;
            }
            int boxIdx = c.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX);
            int dateIdx = c.getColumnIndexOrThrow(Telephony.Mms.DATE);
            int subIdx = c.getColumnIndexOrThrow(Telephony.Mms.SUBSCRIPTION_ID);

            while (c.moveToNext()) {
                if (simSubId != null && c.getInt(subIdx) != simSubId) {
                    continue;
                }
                int box = c.getInt(boxIdx);
                long dateMillis = c.getLong(dateIdx) * 1000L; // MMS DATE je v sekundách
                PeriodStat stat = getOrCreate(byPeriod, zone, period, dateMillis);

                if (box == Telephony.Mms.MESSAGE_BOX_INBOX) {
                    stat.incomingMms += 1;
                } else if (box == Telephony.Mms.MESSAGE_BOX_SENT) {
                    stat.outgoingMms += 1;
                }
            }
        }
    }

    /** Odpovídá hovor zvolené SIM? (best-effort přes PhoneAccount → subId) */
    private boolean callMatchesSim(String accountId, Integer simSubId,
                                   Map<String, Integer> accountToSub) {
        if (simSubId == null) {
            return true;
        }
        if (accountId == null || accountToSub == null) {
            return false;
        }
        Integer sub = accountToSub.get(accountId);
        return sub != null && sub.equals(simSubId);
    }

    /** Mapa PhoneAccount id (z deníku hovorů) → subId SIM. Vyžaduje READ_PHONE_STATE, API 30+. */
    @SuppressLint("MissingPermission")
    private Map<String, Integer> phoneAccountToSub() {
        Map<String, Integer> map = new HashMap<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return map;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return map;
        }
        TelecomManager tm = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        TelephonyManager tel = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null || tel == null) {
            return map;
        }
        try {
            for (PhoneAccountHandle handle : tm.getCallCapablePhoneAccounts()) {
                int sub = tel.getSubscriptionId(handle);
                if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    map.put(handle.getId(), sub);
                }
            }
        } catch (Exception ignored) {
            // Bez oprávnění / nepodporováno – vrátíme, co máme.
        }
        return map;
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
