package taxi.sim;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import taxi.sim.TaxiValidationEngine.Bound;

/**
 * The tiered validation bar (WP0 decision Q2), written beside validation.csv
 * as validation_tiers.csv. Every value is measured from the run.
 * <ul>
 *   <li>A: constraints set by construction or by law; they must hold and are
 *       not claimed as validation.</li>
 *   <li>B: THTA regulatory metrics that emerge from the simulation; the
 *       calibration test.</li>
 *   <li>D: diagnostics, reported but not graded.</li>
 * </ul>
 * Tier C (external fidelity against the Person-Trip Survey) is computed by the
 * Python harness, not here.
 * <p>
 * The operator-owned baseline CSV sets each row's target, tolerance, bound and
 * (optionally) tier. DEFS below keeps the definitions, the row set and the
 * fallback tier; it holds no thresholds. An A or B row with no baseline row is
 * NO_TARGET and counts against its tier.
 */
final class TaxiValidationTiers {

    static final String ZONE_PICKUPS = "Pickups Within Operating Zone";
    static final String ZONE_BOTH_OUTSIDE = "Both Ends Outside Operating Zone";
    static final String ZONE_NOT_APPLICABLE = "n/a (operating zone disabled)";

    private static final class Def {
        final String metric, tier, definition;

        Def(String metric, String tier, String definition) {
            this.metric = metric; this.tier = tier; this.definition = definition;
        }
    }

    /** A baseline row as the bar reads it; an empty tier keeps the DEFS tier. */
    static final class Target {
        final double value, tolerancePct;
        final String tier;
        final Bound bound;

        Target(double value, double tolerancePct, String tier, Bound bound) {
            this.value = value; this.tolerancePct = tolerancePct; this.tier = tier; this.bound = bound;
        }

        /** A two-sided target that keeps the DEFS tier. */
        static Target of(double value, double tolerancePct) {
            return new Target(value, tolerancePct, "", Bound.TWO_SIDED);
        }
    }

    private static final List<Def> DEFS = new ArrayList<>();
    static {
        a("Fleet Operating Rate", "active vehicles / registered vehicles x 100 (set by construction)");
        a("LOCAL Type Share", "share of active vehicles of this type (configured)");
        a("CITYWIDE Type Share", "share of active vehicles of this type (configured)");
        a("APP_PREFERRED Type Share", "share of active vehicles of this type (configured)");
        a("HUB Type Share", "share of active vehicles of this type (configured)");
        a("RIDE_HAIL_PRHS Type Share", "share of active vehicles of this type (configured)");
        a("Trip Distance Min Check", "shortest passenger leg, km (truncation bound)");
        a("Trip Distance Max Check", "longest passenger leg, km (truncation bound)");
        a(ZONE_PICKUPS, "percent of passenger legs whose pickup is inside the operating zone (polbnda adm_code)");
        a(ZONE_BOTH_OUTSIDE, "percent of passenger legs with both ends outside the operating zone (not permitted)");
        b("Avg Passenger Trips per Taxi", "passenger legs / active vehicles (THTA)");
        b("Total Daily Distance per Taxi", "loaded + empty km / active vehicles (THTA)");
        b("Loaded Distance Ratio", "loaded km / total km x 100, jisharitsu (THTA)");
        b("Total Daily Revenue per Taxi", "metered fares / active vehicles, yen (THTA)");
        b("Avg Fare", "metered fare per passenger leg, yen (THTA-derived)");
        b("Avg Trip Distance", "mean passenger leg, km (THTA)");
        b("Night Trip Ratio", "percent of passenger legs in the 22:00-05:00 surcharge window");
        d("Empty Trip Count Ratio", "empty legs / all exported legs x 100");
        d("Empty Running Ratio (distance)", "empty km / total km x 100 (= 100 - loaded ratio)");
        d("Nearby Trip Ratio", "percent of pickups within nearby.trip.radius.km of the previous drop-off (detector of the empty-leg distribution)");
        d("Trips Within City Bounds", "percent of passenger legs with both ends in the city.bounds box");
        d("Vacancy Time Share", "percent of scheduled shift time without a passenger");
    }

    private static void a(String m, String def) { DEFS.add(new Def(m, "A", def)); }
    private static void b(String m, String def) { DEFS.add(new Def(m, "B", def)); }
    private static void d(String m, String def) { DEFS.add(new Def(m, "D", def)); }

    /** Every metric DEFS defines, in row order. */
    static List<String> definedMetrics() {
        List<String> names = new ArrayList<>();
        for (Def def : DEFS) names.add(def.metric);
        return names;
    }

    /** The DEFS tier of a metric, or null when DEFS does not define it. */
    static String defaultTier(String metric) {
        for (Def def : DEFS) {
            if (def.metric.equals(metric)) return def.tier;
        }
        return null;
    }

    static final class TierRow {
        final String tier, metric, status, definition, note;
        final double actual, target, tolerancePct;

        TierRow(String tier, String metric, double actual, double target, double tolerancePct,
                String status, String definition, String note) {
            this.tier = tier; this.metric = metric; this.actual = actual; this.target = target;
            this.tolerancePct = tolerancePct; this.status = status; this.definition = definition;
            this.note = note;
        }
    }

    private TaxiValidationTiers() {}

    /**
     * @param metrics      measured values keyed by metric name
     * @param baseline     metric name -> the baseline row (target, tolerance, tier, bound)
     * @param zoneEnabled  whether an operating zone was loaded for this run
     */
    static List<TierRow> build(Map<String, Double> metrics, Map<String, Target> baseline,
                               boolean zoneEnabled) {
        List<TierRow> rows = new ArrayList<>();
        for (Def def : DEFS) {
            Target ref = baseline.get(def.metric);
            String tier = ref == null || ref.tier.isEmpty() ? def.tier : ref.tier;
            boolean zoneRow = def.metric.equals(ZONE_PICKUPS) || def.metric.equals(ZONE_BOTH_OUTSIDE);
            if (zoneRow && !zoneEnabled) {
                rows.add(new TierRow(tier, def.metric, Double.NaN, ref == null ? Double.NaN : ref.value,
                    Double.NaN, "INFO", def.definition, ZONE_NOT_APPLICABLE));
                continue;
            }
            Double actual = metrics.get(def.metric);
            if (actual == null) {
                rows.add(new TierRow(tier, def.metric, Double.NaN, Double.NaN, Double.NaN,
                    "MISSING", def.definition, "not computed"));
            } else if (tier.equals("D")) {
                rows.add(new TierRow(tier, def.metric, actual,
                    ref == null ? Double.NaN : ref.value, ref == null ? Double.NaN : ref.tolerancePct,
                    "INFO", def.definition, "not graded"));
            } else if (ref == null) {
                rows.add(new TierRow(tier, def.metric, actual, Double.NaN, Double.NaN,
                    "NO_TARGET", def.definition, "no baseline row"));
            } else {
                rows.add(graded(tier, def, actual, ref));
            }
        }
        return rows;
    }

    private static TierRow graded(String tier, Def def, double actual, Target ref) {
        String status = ref.bound.passes(actual, ref.value, ref.tolerancePct / 100.0) ? "PASS" : "FAIL";
        switch (ref.bound) {
            case AT_LEAST:
                return new TierRow(tier, def.metric, actual, ref.value, Double.NaN, status, def.definition,
                    ">= " + ref.value);
            case AT_MOST:
                return new TierRow(tier, def.metric, actual, ref.value, Double.NaN, status, def.definition,
                    "<= " + ref.value);
            default:
                return new TierRow(tier, def.metric, actual, ref.value, ref.tolerancePct, status, def.definition, "");
        }
    }

    static void writeCsv(List<TierRow> rows, String path) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8))) {
            w.println("tier,metric,actual,target,tolerance_pct,status,definition,note");
            for (TierRow r : rows) {
                w.println(String.join(",", r.tier, csv(r.metric), num(r.actual), num(r.target),
                    num(r.tolerancePct), r.status, csv(r.definition), csv(r.note)));
            }
            w.println("# " + summary(rows));
        }
    }

    /**
     * "Tier A: p/n hold; Tier B: p/n within THTA tolerance; operating zone on|off[; not graded: ...]".
     * The denominator is every defined row of the tier that applies to the run
     * (zone rows drop out only when the zone is off), so a row that is missing
     * or has no baseline target counts against the bar instead of vanishing.
     */
    static String summary(List<TierRow> rows) {
        boolean zoneOn = rows.stream().noneMatch(r -> r.note.equals(ZONE_NOT_APPLICABLE));
        List<String> notGraded = new ArrayList<>();
        for (TierRow r : rows) {
            if ((r.tier.equals("A") || r.tier.equals("B"))
                    && (r.status.equals("MISSING") || r.status.equals("NO_TARGET"))) {
                notGraded.add(r.metric);
            }
        }
        return "Tier A: " + count(rows, "A", "PASS") + "/" + applicable(rows, "A") + " hold; Tier B: "
            + count(rows, "B", "PASS") + "/" + applicable(rows, "B") + " within THTA tolerance; operating zone "
            + (zoneOn ? "on" : "off") + (notGraded.isEmpty() ? "" : "; not graded: " + String.join(", ", notGraded));
    }

    private static long count(List<TierRow> rows, String tier, String status) {
        return rows.stream().filter(r -> r.tier.equals(tier) && r.status.equals(status)).count();
    }

    private static long applicable(List<TierRow> rows, String tier) {
        return rows.stream().filter(r -> r.tier.equals(tier) && !r.note.equals(ZONE_NOT_APPLICABLE)).count();
    }

    private static String num(double v) {
        return Double.isFinite(v) ? String.format("%.4f", v) : "";
    }

    private static String csv(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
