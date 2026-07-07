package cz.jirnec.callandsmsstats;

/**
 * Spotřeba mobilních dat jedné aplikace (UID) za dané období – přijatá + odeslaná.
 */
public class AppDataUsage {

    public final String label;
    public final long bytes;

    public AppDataUsage(String label, long bytes) {
        this.label = label;
        this.bytes = bytes;
    }
}
