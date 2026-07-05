package cz.jirnec.callandsmsstats;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Vytváří exportní soubory (CSV / JSON) se souhrny i detaily za všechna období
 * zvolené granularity. Datum/čas se ukládá ve stabilním tvaru nezávislém na
 * národním prostředí, aby byl soubor přenositelný.
 */
public final class Exporter {

    private static final DateTimeFormatter CSV_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private Exporter() {
    }

    public static File writeCsv(Context ctx, List<PeriodStat> periods,
                                List<DetailEntry> entries, Period period) throws IOException {
        ZoneId zone = ZoneId.systemDefault();
        StringBuilder sb = new StringBuilder();

        sb.append("# SUMMARY (period: ").append(period.name()).append(")\n");
        sb.append("PeriodStart,IncomingCallTime,IncomingCallCount,OutgoingCallTime,")
                .append("OutgoingCallCount,MissedCalls,RejectedCalls,IncomingSMS,OutgoingSMS,")
                .append("MobileDataBytes\n");
        for (PeriodStat p : periods) {
            sb.append(p.start).append(',')
                    .append(PeriodStat.formatDuration(p.incomingCallSeconds)).append(',')
                    .append(p.incomingCallCount).append(',')
                    .append(PeriodStat.formatDuration(p.outgoingCallSeconds)).append(',')
                    .append(p.outgoingCallCount).append(',')
                    .append(p.missedCalls).append(',')
                    .append(p.rejectedCalls).append(',')
                    .append(p.incomingSms).append(',')
                    .append(p.outgoingSms).append(',')
                    .append(p.mobileDataBytes >= 0 ? Long.toString(p.mobileDataBytes) : "")
                    .append('\n');
        }

        sb.append('\n');
        sb.append("# DETAILS\n");
        sb.append("PeriodStart,DateTime,Type,Contact,DurationSeconds\n");
        for (DetailEntry e : entries) {
            ZonedDateTime z = Instant.ofEpochMilli(e.timestamp).atZone(zone);
            LocalDate periodStart = period.startOf(z.toLocalDate());
            sb.append(periodStart).append(',')
                    .append(z.format(CSV_DATE_TIME)).append(',')
                    .append(csv(typeLabel(e.kind))).append(',')
                    .append(csv(e.contact == null ? "" : e.contact)).append(',')
                    .append(e.hasDuration() ? Long.toString(e.durationSeconds) : "")
                    .append('\n');
        }

        return write(ctx, "csv", sb.toString());
    }

    public static File writeJson(Context ctx, List<PeriodStat> periods,
                                 List<DetailEntry> entries, Period period) throws IOException {
        ZoneId zone = ZoneId.systemDefault();
        try {
            // Detaily seskupené podle počátku období.
            Map<String, JSONArray> entriesByPeriod = new HashMap<>();
            for (DetailEntry e : entries) {
                ZonedDateTime z = Instant.ofEpochMilli(e.timestamp).atZone(zone);
                String key = period.startOf(z.toLocalDate()).toString();
                JSONObject je = new JSONObject();
                je.put("timestamp", z.format(ISO));
                je.put("type", typeLabel(e.kind));
                je.put("contact", e.contact == null ? JSONObject.NULL : e.contact);
                if (e.hasDuration()) {
                    je.put("durationSeconds", e.durationSeconds);
                }
                JSONArray arr = entriesByPeriod.get(key);
                if (arr == null) {
                    arr = new JSONArray();
                    entriesByPeriod.put(key, arr);
                }
                arr.put(je);
            }

            JSONArray periodsArr = new JSONArray();
            for (PeriodStat p : periods) {
                String key = p.start.toString();
                JSONObject summary = new JSONObject();
                summary.put("incomingCallSeconds", p.incomingCallSeconds);
                summary.put("incomingCallCount", p.incomingCallCount);
                summary.put("outgoingCallSeconds", p.outgoingCallSeconds);
                summary.put("outgoingCallCount", p.outgoingCallCount);
                summary.put("missedCalls", p.missedCalls);
                summary.put("rejectedCalls", p.rejectedCalls);
                summary.put("incomingSms", p.incomingSms);
                summary.put("outgoingSms", p.outgoingSms);
                summary.put("mobileDataBytes",
                        p.mobileDataBytes >= 0 ? p.mobileDataBytes : JSONObject.NULL);

                JSONObject jp = new JSONObject();
                jp.put("start", key);
                jp.put("summary", summary);
                JSONArray arr = entriesByPeriod.get(key);
                jp.put("entries", arr != null ? arr : new JSONArray());
                periodsArr.put(jp);
            }

            JSONObject root = new JSONObject();
            root.put("app", "Call and SMS stats");
            root.put("exportedAt", LocalDateTime.now().format(ISO));
            root.put("period", period.name());
            root.put("periods", periodsArr);

            return write(ctx, "json", root.toString(2));
        } catch (JSONException ex) {
            throw new IOException("Sestavení JSON selhalo", ex);
        }
    }

    private static File write(Context ctx, String extension, String content) throws IOException {
        File dir = new File(ctx.getCacheDir(), "exports");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Nelze vytvořit adresář pro export");
        }
        String name = "CallAndSMSStats-export-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT))
                + "." + extension;
        File file = new File(dir, name);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(content);
        }
        return file;
    }

    /** Anglické, stabilní názvy typů pro exportní soubor (nezávislé na jazyce UI). */
    private static String typeLabel(int kind) {
        switch (kind) {
            case DetailEntry.INCOMING_CALL:
                return "Incoming call";
            case DetailEntry.OUTGOING_CALL:
                return "Outgoing call";
            case DetailEntry.MISSED_CALL:
                return "Missed call";
            case DetailEntry.REJECTED_CALL:
                return "Rejected call";
            case DetailEntry.INCOMING_SMS:
                return "Incoming SMS";
            case DetailEntry.OUTGOING_SMS:
                return "Outgoing SMS";
            default:
                return "";
        }
    }

    /** Ošetření hodnoty pro CSV (uvozovky kolem polí s čárkou/uvozovkou/koncem řádku). */
    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
