package taxi.sim;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostic counters for loaded-leg drop-off sampling, grouped by the pickup's
 * municipality: does the Tokyo + Kanagawa land mask (WP0 decision Q3) cost the
 * wards on the northern and eastern edges more failed or projected drop-offs?
 * Counts only; no random draws.
 */
final class LoadedLegBorderStats {

    enum Outcome { DIRECT, PROJECTED, FAILED }

    static final String[] GROUPS = {"north", "east", "rest", "unknown"};

    private final Map<String, long[]> counts = new HashMap<>();

    /** Northern edge: Adachi, Kita, Itabashi, Nerima; eastern edge: Katsushika, Edogawa. */
    static String group(String admCode) {
        if (admCode == null) return "unknown";
        switch (admCode) {
            case "13121": case "13117": case "13119": case "13120": return "north";
            case "13122": case "13123": return "east";
            default: return "rest";
        }
    }

    void record(String pickupAdmCode, Outcome outcome) {
        counts.computeIfAbsent(group(pickupAdmCode), k -> new long[Outcome.values().length])[outcome.ordinal()]++;
    }

    /** Dashboard rows "LOADED_LEGS.&lt;group&gt;_samples/_projected_pct/_failed_pct". */
    Map<String, Double> toDashboardRows() {
        Map<String, Double> m = new LinkedHashMap<>();
        for (String g : GROUPS) {
            long[] c = counts.getOrDefault(g, new long[Outcome.values().length]);
            long n = c[0] + c[1] + c[2];
            m.put("LOADED_LEGS." + g + "_samples", (double) n);
            m.put("LOADED_LEGS." + g + "_projected_pct", n == 0 ? Double.NaN : 100.0 * c[Outcome.PROJECTED.ordinal()] / n);
            m.put("LOADED_LEGS." + g + "_failed_pct", n == 0 ? Double.NaN : 100.0 * c[Outcome.FAILED.ordinal()] / n);
        }
        return m;
    }
}
