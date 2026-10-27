package taxi.sim;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Counters for empty legs by {@link EmptyLegPath} and by whether the leg
 * started inside the operating zone. Recorded legs (above the empty-trip
 * threshold) reconcile with the exported empty trips; legs at or below the
 * threshold, and empty legs orphaned by a failed loaded leg, are tallied
 * apart. Not thread-safe: one instance per {@link ShiftSimulator}.
 */
final class EmptyLegStats {

    static final int MAX_ATTEMPTS_BUCKET = 20;

    /** Growable list of leg lengths (km). */
    private static final class Lengths {
        private double[] values = new double[64];
        private int size = 0;

        void add(double v) {
            if (size == values.length) values = Arrays.copyOf(values, size * 2);
            values[size++] = v;
        }

        double sum() {
            double s = 0.0;
            for (int i = 0; i < size; i++) s += values[i];
            return s;
        }

        double[] sorted() {
            double[] copy = Arrays.copyOf(values, size);
            Arrays.sort(copy);
            return copy;
        }
    }

    private final Map<EmptyLegPath, Lengths> inside = new EnumMap<>(EmptyLegPath.class);
    private final Map<EmptyLegPath, Lengths> outside = new EnumMap<>(EmptyLegPath.class);
    private final long[] attempts = new long[MAX_ATTEMPTS_BUCKET + 1];
    private long orphanCount = 0;
    private double orphanKm = 0.0;
    private long unrecordedCount = 0;
    private double unrecordedKm = 0.0;

    EmptyLegStats() {
        for (EmptyLegPath p : EmptyLegPath.values()) {
            inside.put(p, new Lengths());
            outside.put(p, new Lengths());
        }
    }

    void record(EmptyLegPath path, double km, boolean startInside) {
        (startInside ? inside : outside).get(path).add(km);
    }

    /** Number of draws needed to keep an end point in the zone; clamped to 1..20. */
    void recordAttempts(int n) {
        attempts[Math.max(1, Math.min(MAX_ATTEMPTS_BUCKET, n))]++;
    }

    /** An already-recorded empty leg whose loaded leg then failed (no passenger). */
    void markOrphan(double km) {
        orphanCount++;
        orphanKm += km;
    }

    /** Empty leg at or below the empty-trip threshold: simulated but not exported. */
    void recordUnrecorded(double km) {
        unrecordedCount++;
        unrecordedKm += km;
    }

    long count(EmptyLegPath path) {
        return inside.get(path).size + outside.get(path).size;
    }

    long count(EmptyLegPath path, boolean startInside) {
        return (startInside ? inside : outside).get(path).size;
    }

    double meanKm(EmptyLegPath path) {
        long n = count(path);
        return n == 0 ? Double.NaN : (inside.get(path).sum() + outside.get(path).sum()) / n;
    }

    double meanKm(EmptyLegPath path, boolean startInside) {
        Lengths l = (startInside ? inside : outside).get(path);
        return l.size == 0 ? Double.NaN : l.sum() / l.size;
    }

    double maxKm(EmptyLegPath path) {
        double[] all = all(path);
        return all.length == 0 ? Double.NaN : all[all.length - 1];
    }

    /** Nearest-rank percentile (p in 1..100) of the path's leg lengths. */
    double percentileKm(EmptyLegPath path, double p) {
        double[] all = all(path);
        if (all.length == 0) return Double.NaN;
        int rank = (int) Math.ceil(p / 100.0 * all.length);
        return all[Math.max(1, Math.min(all.length, rank)) - 1];
    }

    long totalCount() {
        long n = 0;
        for (EmptyLegPath p : EmptyLegPath.values()) n += count(p);
        return n;
    }

    double totalKm() {
        double km = 0.0;
        for (EmptyLegPath p : EmptyLegPath.values()) km += inside.get(p).sum() + outside.get(p).sum();
        return km;
    }

    long attempts(int n) { return attempts[n]; }
    long orphanCount() { return orphanCount; }
    double orphanKm() { return orphanKm; }
    long unrecordedCount() { return unrecordedCount; }
    double unrecordedKm() { return unrecordedKm; }

    /** Dashboard rows, keyed "EMPTY_LEGS.&lt;name&gt;". */
    Map<String, Double> toDashboardRows() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("EMPTY_LEGS.total_count", (double) totalCount());
        m.put("EMPTY_LEGS.total_km", totalKm());
        for (EmptyLegPath p : EmptyLegPath.values()) {
            String k = "EMPTY_LEGS." + p.name().toLowerCase();
            m.put(k + "_count", (double) count(p));
            if (count(p) == 0) continue;
            m.put(k + "_start_outside_count", (double) count(p, false));
            m.put(k + "_mean_km", meanKm(p));
            m.put(k + "_p50_km", percentileKm(p, 50));
            m.put(k + "_p90_km", percentileKm(p, 90));
            m.put(k + "_max_km", maxKm(p));
        }
        m.put("EMPTY_LEGS.orphan_count", (double) orphanCount);
        m.put("EMPTY_LEGS.orphan_km", orphanKm);
        m.put("EMPTY_LEGS.unrecorded_count", (double) unrecordedCount);
        m.put("EMPTY_LEGS.unrecorded_km", unrecordedKm);
        for (int n = 1; n <= MAX_ATTEMPTS_BUCKET; n++) {
            if (attempts[n] > 0) m.put("EMPTY_LEGS.zone_attempts_" + n, (double) attempts[n]);
        }
        return m;
    }

    private double[] all(EmptyLegPath path) {
        double[] a = inside.get(path).sorted();
        double[] b = outside.get(path).sorted();
        double[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        Arrays.sort(all);
        return all;
    }
}
