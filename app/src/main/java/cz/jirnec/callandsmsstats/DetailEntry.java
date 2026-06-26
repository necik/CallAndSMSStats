package cz.jirnec.callandsmsstats;

/**
 * Jeden konkrétní záznam (hovor nebo SMS) zobrazený v detailu měsíce.
 */
public class DetailEntry {

    public static final int INCOMING_CALL = 0;
    public static final int OUTGOING_CALL = 1;
    public static final int MISSED_CALL = 2;
    public static final int REJECTED_CALL = 3;
    public static final int INCOMING_SMS = 4;
    public static final int OUTGOING_SMS = 5;

    public int kind;
    public long timestamp;
    public long durationSeconds;
    public String contact;

    /** Délka má smysl jen u uskutečněných (příchozích/odchozích) hovorů. */
    public boolean hasDuration() {
        return kind == INCOMING_CALL || kind == OUTGOING_CALL;
    }
}
