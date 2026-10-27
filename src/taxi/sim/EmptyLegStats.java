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
    // Orphans: empty legs (recorded or not) that no passenger leg followed.
    private final Map<EmptyLegPath, long[]> orphanCounts = new EnumMap<>(EmptyLegPath.class); // {mid, end}
    private final Map<EmptyLegPath, double[]> orphanKms = new EnumMap<>(EmptyLegPath.class);
    private final Map<EmptyLegPath, long[]> unrecordedCounts = new EnumMap<>(EmptyLegPath.class);
    private double unrecordedKm = 0.0;

    EmptyLegStats() {
        for (EmptyLegPath p : EmptyLegPath.values()) {
            inside.put(p, new Lengths());
            outside.put(p, new Lengths());
            orphanCounts.put(p, new long[2]);
            orphanKms.put(p, new double[1]);
            unrecordedCounts.put(p, new long[1]);
        }
    }

    void record(EmptyLegPath path, double km, boolean startInside) {
        (startInside ? inside : outside).get(path).add(km);
    }

    /** Number of draws needed to keep an end point in the zone; clamped to 1..20. */
    void recordAttempts(int n) {
        attempts[Math.max(1, Math.min(MAX_ATTEMPTS_BUCKET, n))]++;
    }

    /**
     * An empty leg (recorded or not) that no passenger leg followed: the loaded
     * leg failed, a PRHS window closed, or the shift ended (endOfShift).
     */
    void markOrphan(EmptyLegPath path, double km, boolean endOfShift) {
        orphanCounts.get(path)[endOfShift ? 1 : 0]++;
        orphanKms.get(path)[0] += km;
    }

    /** Empty leg at or below the empty-trip threshold: simulated but not exported. */
    void recordUnrecorded(EmptyLegPath path, double km) {
        unrecordedCounts.get(path)[0]++;
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

    long orphanCount() { return orphanCount(false) + orphanCount(true); }

    long orphanCount(boolean endOfShift) {
        long n = 0;
        for (long[] c : orphanCounts.values()) n += c[endOfShift ? 1 : 0];
        return n;
    }

    long orphanCount(EmptyLegPath path) { return orphanCounts.get(path)[0] + orphanCounts.get(path)[1]; }

    double orphanKm() {
        double km = 0.0;
        for (double[] k : orphanKms.values()) km += k[0];
        return km;
    }

    double orphanKm(EmptyLegPath path) { return orphanKms.get(path)[0]; }

    long unrecordedCount() {
        long n = 0;
        for (long[] c : unrecordedCounts.values()) n += c[0];
        return n;
    }

    long unrecordedCount(EmptyLegPath path) { return unrecordedCounts.get(path)[0]; }

    double unrecordedKm() { return unrecordedKm; }

    /** Every simulated empty leg is followed by one passenger leg unless orphaned. */
    long impliedPassengerLegs() { return totalCount() + unrecordedCount() - orphanCount(); }

    /** Recorded legs of the path longer than km. */
    long countOver(EmptyLegPath path, double km) {
        long n = 0;
        for (double v : all(path)) if (v > km) n++;
        return n;
    }

    /**
     * Kolmogorov-Smirnov distance between the FIRST_DRAW leg lengths and the
     * log-normal(mu, sigma) truncated to [lo, hi] they are drawn from. A shift
     * between runs means the empty-leg sampler itself changed, not just the
     * paths around it.
     */
    double ksFirstDraw(double mu, double sigma, double lo, double hi) {
        double[] x = all(EmptyLegPath.FIRST_DRAW);
        int n = x.length;
        if (n == 0) return Double.NaN;
        double d = 0.0;
        for (int i = 0; i < n; i++) {
            double f = truncatedLognormalCdf(x[i], mu, sigma, lo, hi);
            d = Math.max(d, Math.max((i + 1.0) / n - f, f - (double) i / n));
        }
        return d;
    }

    static double truncatedLognormalCdf(double x, double mu, double sigma, double lo, double hi) {
        double a = normalCdf((Math.log(lo) - mu) / sigma);
        double b = normalCdf((Math.log(hi) - mu) / sigma);
        double fx = normalCdf((Math.log(Math.max(x, lo)) - mu) / sigma);
        return Math.max(0.0, Math.min(1.0, (fx - a) / (b - a)));
    }

    static double truncatedLognormalQuantile(double p, double mu, double sigma, double lo, double hi) {
        double a = normalCdf((Math.log(lo) - mu) / sigma);
        double b = normalCdf((Math.log(hi) - mu) / sigma);
        return Math.exp(mu + sigma * normalQuantile(a + p * (b - a)));
    }

    /** Standard normal CDF via erfc (Numerical Recipes erfcc, |error| &lt; 1.2e-7). */
    static double normalCdf(double z) {
        double x = Math.abs(z) / Math.sqrt(2.0);
        double t = 1.0 / (1.0 + 0.5 * x);
        double erfc = t * Math.exp(-x * x - 1.26551223 + t * (1.00002368 + t * (0.37409196
            + t * (0.09678418 + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398
            + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        return z >= 0 ? 1.0 - 0.5 * erfc : 0.5 * erfc;
    }

    /** Standard normal quantile (Acklam's rational approximation, rel. error &lt; 1.2e-9). */
    static double normalQuantile(double p) {
        final double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
            1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        final double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
            6.680131188771972e+01, -1.328068155288572e+01};
        final double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
            -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        final double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
            3.754408661907416e+00};
        double pLow = 0.02425;
        if (p < pLow) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > 1 - pLow) {
            double q = Math.sqrt(-2 * Math.log(1 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        double q = p - 0.5;
        double r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
            / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }

    /**
     * Dashboard rows, keyed "EMPTY_LEGS.&lt;name&gt;". Start-outside counts are
     * written only when an operating zone is loaded (they are 0 otherwise by
     * construction, not by measurement).
     */
    Map<String, Double> toDashboardRows(boolean zoneEnabled) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("EMPTY_LEGS.total_count", (double) totalCount());
        m.put("EMPTY_LEGS.total_km", totalKm());
        m.put("EMPTY_LEGS.implied_passenger_legs", (double) impliedPassengerLegs());
        for (EmptyLegPath p : EmptyLegPath.values()) {
            String k = "EMPTY_LEGS." + p.name().toLowerCase();
            m.put(k + "_count", (double) count(p));
            m.put(k + "_unrecorded_count", (double) unrecordedCount(p));
            m.put(k + "_orphan_count", (double) orphanCount(p));
            if (count(p) == 0) continue;
            if (zoneEnabled) m.put(k + "_start_outside_count", (double) count(p, false));
            m.put(k + "_mean_km", meanKm(p));
            m.put(k + "_p50_km", percentileKm(p, 50));
            m.put(k + "_p90_km", percentileKm(p, 90));
            m.put(k + "_max_km", maxKm(p));
            m.put(k + "_over_12km_count", (double) countOver(p, 12.0));
            m.put(k + "_orphan_km", orphanKm(p));
        }
        m.put("EMPTY_LEGS.orphan_count", (double) orphanCount());
        m.put("EMPTY_LEGS.orphan_mid_shift_count", (double) orphanCount(false));
        m.put("EMPTY_LEGS.orphan_end_of_shift_count", (double) orphanCount(true));
        m.put("EMPTY_LEGS.orphan_km", orphanKm());
        m.put("EMPTY_LEGS.unrecorded_count", (double) unrecordedCount());
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
