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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Vytváří exportní soubory (CSV / JSON) se souhrny i detaily za všechny měsíce.
 * Datum/čas se ukládá ve stabilním tvaru nezávislém na národním prostředí, aby
 * byl soubor přenositelný.
 */
public final class Exporter {

    private static final DateTimeFormatter CSV_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private Exporter() {
    }

    public static File writeCsv(Context ctx, List<MonthStat> months, List<DetailEntry> entries)
            throws IOException {
        ZoneId zone = ZoneId.systemDefault();
        StringBuilder sb = new StringBuilder();

        sb.append("# SUMMARY\n");
        sb.append("Month,IncomingCallTime,IncomingCallCount,OutgoingCallTime,")
                .append("OutgoingCallCount,MissedCalls,RejectedCalls,IncomingSMS,OutgoingSMS\n");
        for (MonthStat m : months) {
            sb.append(m.month).append(',')
                    .append(MonthStat.formatDuration(m.incomingCallSeconds)).append(',')
                    .append(m.incomingCallCount).append(',')
                    .append(MonthStat.formatDuration(m.outgoingCallSeconds)).append(',')
                    .append(m.outgoingCallCount).append(',')
                    .append(m.missedCalls).append(',')
                    .append(m.rejectedCalls).append(',')
                    .append(m.incomingSms).append(',')
                    .append(m.outgoingSms).append('\n');
        }

        sb.append('\n');
        sb.append("# DETAILS\n");
        sb.append("Month,DateTime,Type,Contact,DurationSeconds\n");
        for (DetailEntry e : entries) {
            ZonedDateTime z = Instant.ofEpochMilli(e.timestamp).atZone(zone);
            sb.append(YearMonth.from(z)).append(',')
                    .append(z.format(CSV_DATE_TIME)).append(',')
                    .append(csv(typeLabel(e.kind))).append(',')
                    .append(csv(e.contact == null ? "" : e.contact)).append(',')
                    .append(e.hasDuration() ? Long.toString(e.durationSeconds) : "")
                    .append('\n');
        }

        return write(ctx, "csv", sb.toString());
    }

    public static File writeJson(Context ctx, List<MonthStat> months, List<DetailEntry> entries)
            throws IOException {
        ZoneId zone = ZoneId.systemDefault();
        try {
            // Detaily seskupené podle měsíce.
            Map<String, JSONArray> entriesByMonth = new HashMap<>();
            for (DetailEntry e : entries) {
                ZonedDateTime z = Instant.ofEpochMilli(e.timestamp).atZone(zone);
                String month = YearMonth.from(z).toString();
                JSONObject je = new JSONObject();
                je.put("timestamp", z.format(ISO));
                je.put("type", typeLabel(e.kind));
                je.put("contact", e.contact == null ? JSONObject.NULL : e.contact);
                if (e.hasDuration()) {
                    je.put("durationSeconds", e.durationSeconds);
                }
                JSONArray arr = entriesByMonth.get(month);
                if (arr == null) {
                    arr = new JSONArray();
                    entriesByMonth.put(month, arr);
                }
                arr.put(je);
            }

            JSONArray monthsArr = new JSONArray();
            for (MonthStat m : months) {
                String key = m.month.toString();
                JSONObject summary = new JSONObject();
                summary.put("incomingCallSeconds", m.incomingCallSeconds);
                summary.put("incomingCallCount", m.incomingCallCount);
                summary.put("outgoingCallSeconds", m.outgoingCallSeconds);
                summary.put("outgoingCallCount", m.outgoingCallCount);
                summary.put("missedCalls", m.missedCalls);
                summary.put("rejectedCalls", m.rejectedCalls);
                summary.put("incomingSms", m.incomingSms);
                summary.put("outgoingSms", m.outgoingSms);

                JSONObject jm = new JSONObject();
                jm.put("month", key);
                jm.put("summary", summary);
                JSONArray arr = entriesByMonth.get(key);
                jm.put("entries", arr != null ? arr : new JSONArray());
                monthsArr.put(jm);
            }

            JSONObject root = new JSONObject();
            root.put("app", "Call and SMS stats");
            root.put("exportedAt", LocalDateTime.now().format(ISO));
            root.put("months", monthsArr);

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
