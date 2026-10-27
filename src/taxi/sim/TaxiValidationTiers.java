package taxi.sim;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The tiered validation bar (WP0 decision Q2), written beside validation.csv
 * as validation_tiers.csv. Every value is measured from the run.
 * <ul>
 *   <li>A: constraints set by construction or by law; they must hold and are
 *       not claimed as validation.</li>
 *   <li>B: THTA regulatory metrics that emerge from the simulation; the
 *       calibration test, graded on the baseline tolerance.</li>
 *   <li>D: diagnostics, reported but not graded.</li>
 * </ul>
 * Tier C (external fidelity against the Person-Trip Survey) is computed by the
 * Python harness, not here.
 */
final class TaxiValidationTiers {

    static final String ZONE_PICKUPS = "Pickups Within Operating Zone";
    static final String ZONE_BOTH_OUTSIDE = "Both Ends Outside Operating Zone";
    static final double ZONE_PICKUPS_MIN_PCT = 99.0;
    static final double ZONE_BOTH_OUTSIDE_MAX_PCT = 0.5;

    private enum Kind { TWO_SIDED, AT_LEAST, AT_MOST, INFO }

    private static final class Def {
        final String metric, tier, definition;
        final Kind kind;
        final double bound;

        Def(String metric, String tier, Kind kind, double bound, String definition) {
            this.metric = metric; this.tier = tier; this.kind = kind; this.bound = bound;
            this.definition = definition;
        }
    }

    private static final List<Def> DEFS = new ArrayList<>();
    static {
        a2("Fleet Operating Rate", "active vehicles / registered vehicles x 100 (set by construction)");
        a2("LOCAL Type Share", "share of active vehicles of this type (configured)");
        a2("CITYWIDE Type Share", "share of active vehicles of this type (configured)");
        a2("APP_PREFERRED Type Share", "share of active vehicles of this type (configured)");
        a2("HUB Type Share", "share of active vehicles of this type (configured)");
        a2("RIDE_HAIL_PRHS Type Share", "share of active vehicles of this type (configured)");
        a2("Trip Distance Min Check", "shortest passenger leg, km (truncation bound)");
        a2("Trip Distance Max Check", "longest passenger leg, km (truncation bound)");
        DEFS.add(new Def(ZONE_PICKUPS, "A", Kind.AT_LEAST, ZONE_PICKUPS_MIN_PCT,
            "percent of passenger legs whose pickup is inside the operating zone (polbnda adm_code)"));
        DEFS.add(new Def(ZONE_BOTH_OUTSIDE, "A", Kind.AT_MOST, ZONE_BOTH_OUTSIDE_MAX_PCT,
            "percent of passenger legs with both ends outside the operating zone (not permitted)"));
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

    private static void a2(String m, String def) { DEFS.add(new Def(m, "A", Kind.TWO_SIDED, 0, def)); }
    private static void b(String m, String def) { DEFS.add(new Def(m, "B", Kind.TWO_SIDED, 0, def)); }
    private static void d(String m, String def) { DEFS.add(new Def(m, "D", Kind.INFO, 0, def)); }

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
     * @param baseline     metric name -> {target, tolerance percent} from the baseline CSV
     * @param zoneEnabled  whether an operating zone was loaded for this run
     */
    static List<TierRow> build(Map<String, Double> metrics, Map<String, double[]> baseline,
                               boolean zoneEnabled) {
        List<TierRow> rows = new ArrayList<>();
        for (Def def : DEFS) {
            boolean zoneRow = def.metric.equals(ZONE_PICKUPS) || def.metric.equals(ZONE_BOTH_OUTSIDE);
            double[] ref = baseline.get(def.metric);
            if (zoneRow && !zoneEnabled) {
                rows.add(new TierRow(def.tier, def.metric, Double.NaN, def.bound, Double.NaN,
                    "INFO", def.definition, "n/a (operating zone disabled)"));
                continue;
            }
            Double actual = metrics.get(def.metric);
            if (actual == null) {
                rows.add(new TierRow(def.tier, def.metric, Double.NaN, Double.NaN, Double.NaN,
                    "MISSING", def.definition, "not computed"));
                continue;
            }
            switch (def.kind) {
                case AT_LEAST:
                    rows.add(new TierRow(def.tier, def.metric, actual, def.bound, Double.NaN,
                        actual >= def.bound ? "PASS" : "FAIL", def.definition, ">= " + def.bound));
                    break;
                case AT_MOST:
                    rows.add(new TierRow(def.tier, def.metric, actual, def.bound, Double.NaN,
                        actual <= def.bound ? "PASS" : "FAIL", def.definition, "<= " + def.bound));
                    break;
                case TWO_SIDED:
                    if (ref == null) {
                        rows.add(new TierRow(def.tier, def.metric, actual, Double.NaN, Double.NaN,
                            "NO_TARGET", def.definition, "no baseline row"));
                    } else {
                        boolean pass = Math.abs(actual - ref[0]) <= ref[1] / 100.0 * Math.abs(ref[0]);
                        rows.add(new TierRow(def.tier, def.metric, actual, ref[0], ref[1],
                            pass ? "PASS" : "FAIL", def.definition, ""));
                    }
                    break;
                default:
                    rows.add(new TierRow(def.tier, def.metric, actual,
                        ref == null ? Double.NaN : ref[0], ref == null ? Double.NaN : ref[1],
                        "INFO", def.definition, "not graded"));
            }
        }
        return rows;
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

    /** "Tier A: x/y hold; Tier B: k/n within tolerance" over graded rows. */
    static String summary(List<TierRow> rows) {
        return "Tier A: " + count(rows, "A", "PASS") + "/" + graded(rows, "A") + " hold; Tier B: "
            + count(rows, "B", "PASS") + "/" + graded(rows, "B") + " within THTA tolerance";
    }

    private static long count(List<TierRow> rows, String tier, String status) {
        return rows.stream().filter(r -> r.tier.equals(tier) && r.status.equals(status)).count();
    }

    private static long graded(List<TierRow> rows, String tier) {
        return rows.stream().filter(r -> r.tier.equals(tier)
            && (r.status.equals("PASS") || r.status.equals("FAIL"))).count();
    }

    private static String num(double v) {
        return Double.isFinite(v) ? String.format("%.4f", v) : "";
    }

    private static String csv(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
