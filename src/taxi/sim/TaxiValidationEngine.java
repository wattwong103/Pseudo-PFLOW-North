package taxi.sim;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

import util.MetricsDashboard;

/**
 * Validation engine for taxi simulation results.
 * <p>
 * Mirrors the truck ABM's ValidationEngine architecture: CSV-driven baseline
 * metrics with category grouping, tolerance-based pass/fail, and letter grading.
 * <p>
 * Data sources:
 * - Tokyo Taxi Association (THTA) 2024 statistics
 * - MLIT National Taxi Survey
 * - Industry standard operational metrics
 *
 * @version 1.0
 */
public class TaxiValidationEngine {

    // ── How a baseline row is graded (optional column 10, "bound") ──

    public enum Bound {
        /** |actual - target| within tolerance x |target|; a zero target needs a zero actual. The default. */
        TWO_SIDED,
        /** actual >= target; the tolerance is not used. */
        AT_LEAST,
        /** actual <= target; the tolerance is not used. */
        AT_MOST;

        boolean passes(double actual, double target, double toleranceFraction) {
            if (!Double.isFinite(actual)) return false;
            switch (this) {
                case AT_LEAST: return actual >= target;
                case AT_MOST: return actual <= target;
                default:
                    double error = Math.abs(actual - target);
                    return target == 0 ? (actual == 0) : (error <= toleranceFraction * Math.abs(target));
            }
        }

        /** two_sided, at_least or at_most, in any case; empty means TWO_SIDED. */
        static Bound parse(String s) {
            if (s.isEmpty()) return TWO_SIDED;
            for (Bound b : values()) {
                if (b.name().equalsIgnoreCase(s)) return b;
            }
            throw new IllegalArgumentException("unknown bound '" + s + "' (two_sided, at_least or at_most)");
        }
    }

    // ── Baseline metric (one CSV row) ──

    private static class BaselineMetric {
        final String name, category, unit, source, notes, priority;
        final double surveyValue, tolerancePct;
        /** Optional column 9: A, B or D; empty keeps the tier TaxiValidationTiers defines. */
        final String tier;
        /** Optional column 10; TWO_SIDED when absent. */
        final Bound bound;

        BaselineMetric(String name, String category, double surveyValue, String unit,
                       double tolerancePct, String source, String notes, String priority,
                       String tier, Bound bound) {
            this.name = name; this.category = category; this.surveyValue = surveyValue;
            this.unit = unit; this.tolerancePct = tolerancePct; this.source = source;
            this.notes = notes; this.priority = priority; this.tier = tier; this.bound = bound;
        }

        double toleranceFraction() { return tolerancePct / 100.0; }
    }

    // ── Validation result ──

    public static class ValidationResult {
        public final String metric, category;
        public final double actual, target, tolerance;
        public final Bound bound;
        public final boolean passed;

        public ValidationResult(String metric, String category, double actual,
                                double target, double tolerance) {
            this(metric, category, actual, target, tolerance, Bound.TWO_SIDED);
        }

        public ValidationResult(String metric, String category, double actual,
                                double target, double tolerance, Bound bound) {
            this.metric = metric; this.category = category;
            this.actual = actual; this.target = target; this.tolerance = tolerance;
            this.bound = bound;
            this.passed = bound.passes(actual, target, tolerance);
        }

        /** Relative distance from the target; a one-sided row has none while its bound holds. */
        public double getErrorPercent() {
            if (bound != Bound.TWO_SIDED && passed) return 0.0;
            if (target == 0) return actual == 0 ? 0.0 : 100.0;
            return Math.abs(actual - target) / Math.abs(target) * 100.0;
        }

        @Override
        public String toString() {
            String icon = passed ? "PASS" : "FAIL";
            if (bound != Bound.TWO_SIDED) {
                return String.format("  %-40s %14.2f  (target: %s %11.2f one-sided)  %s",
                        metric, actual, bound == Bound.AT_LEAST ? ">=" : "<=", target, icon);
            }
            return String.format("  %-40s %14.2f  (target: %14.2f +/-%3.0f%%)  [error: %5.1f%%]  %s",
                    metric, actual, target, tolerance * 100, getErrorPercent(), icon);
        }
    }

    // ── Validation report ──

    public static class ValidationReport {
        public final List<ValidationResult> results;
        public final int totalTests, passedTests;
        public final double passRate;
        public final String grade;

        public ValidationReport(List<ValidationResult> results) {
            this.results = results;
            this.totalTests = results.size();
            this.passedTests = (int) results.stream().filter(r -> r.passed).count();
            this.passRate = totalTests > 0 ? (double) passedTests / totalTests : 0.0;

            if (passRate >= 0.95) grade = "A+";
            else if (passRate >= 0.90) grade = "A";
            else if (passRate >= 0.85) grade = "A-";
            else if (passRate >= 0.80) grade = "B+";
            else if (passRate >= 0.75) grade = "B";
            else if (passRate >= 0.70) grade = "B-";
            else if (passRate >= 0.65) grade = "C+";
            else if (passRate >= 0.60) grade = "C";
            else grade = "F";
        }

        public void printReport() {
            String sep = "====================================================================================================";
            System.out.println("\n" + sep);
            System.out.println("TAXI VALIDATION REPORT");
            System.out.println(sep);

            LinkedHashSet<String> categories = new LinkedHashSet<>();
            for (ValidationResult r : results) categories.add(r.category);

            for (String cat : categories) {
                System.out.println("\n[" + cat + "]");
                for (ValidationResult r : results) {
                    if (r.category.equals(cat)) System.out.println(r);
                }
            }

            System.out.println("\n" + sep);
            System.out.printf("SUMMARY: %d/%d tests passed (%.1f%%)%n", passedTests, totalTests, passRate * 100);
            System.out.println("GRADE: " + grade);

            List<ValidationResult> failures = results.stream()
                    .filter(r -> !r.passed).collect(Collectors.toList());
            if (!failures.isEmpty()) {
                System.out.println("\nFAILING TESTS:");
                for (ValidationResult r : failures) {
                    System.out.printf("  - %s (error: %.1f%%, tolerance: %.0f%%)%n",
                            r.metric, r.getErrorPercent(), r.tolerance * 100);
                }
            }
            System.out.println(sep);
        }

        /** Write validation results to a CSV file via MetricsDashboard. */
        public void writeToFile(String outputDir) {
            List<String[]> rows = new ArrayList<>();
            for (ValidationResult r : results) {
                rows.add(new String[]{
                        r.category, r.metric,
                        String.format("%.2f", r.actual),
                        String.format("%.2f", r.target),
                        String.format("%.0f", r.tolerance * 100),
                        String.format("%.1f", r.getErrorPercent()),
                        r.passed ? "PASS" : "FAIL"
                });
            }
            MetricsDashboard.writeValidation(outputDir, rows, grade, passRate);
        }
    }

    // ── Engine ──

    private final List<BaselineMetric> baseline = new ArrayList<>();
    private Map<String, Double> lastMetrics;
    private boolean lastZoneEnabled;

    /**
     * Load baseline metrics from CSV file.
     * Format: metric_name,category,survey_value,unit,tolerance_pct,source,notes,priority[,tier,bound]
     * The split is a plain split(","), so a row whose field count differs from the
     * header's (a comma in notes, a truncated row) is rejected rather than read with
     * shifted columns. Only blank lines are skipped: a dropped row could take an
     * operator-assigned Tier A or B constraint out of the gate.
     *
     * @throws IllegalArgumentException on such a row, a repeated metric, a missing
     *         or non-finite number, an unknown tier or an unknown bound
     */
    public void loadBaseline(String csvPath) {
        File file = new File(csvPath);
        if (!file.exists()) {
            System.out.println("[VALIDATION] Baseline CSV not found: " + csvPath);
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String header = br.readLine();
            int fields = header == null ? 0 : header.split(",", -1).length;
            if (header != null && fields < 8) {
                throw new IllegalArgumentException(csvPath + ": header has " + fields + " fields, needs at least 8");
            }
            String line;
            int lineNo = 1;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",", -1);
                if (parts.length != fields) {
                    throw new IllegalArgumentException(String.format(
                        "%s line %d: %d fields, header has %d (a comma inside a field?): %s",
                        csvPath, lineNo, parts.length, fields, line));
                }
                String name = parts[0].trim();
                if (baseline.stream().anyMatch(bm -> bm.name.equals(name))) {
                    throw new IllegalArgumentException(csvPath + " line " + lineNo + ": repeated metric " + name);
                }
                try {
                    baseline.add(new BaselineMetric(
                            name, parts[1].trim(),
                            finite(parts[2].trim(), "survey_value"),
                            parts[3].trim(),
                            finite(parts[4].trim(), "tolerance_pct"),
                            parts[5].trim(), parts[6].trim(), parts[7].trim(),
                            parts.length > 8 ? parseTier(parts[8].trim()) : "",
                            parts.length > 9 ? Bound.parse(parts[9].trim()) : Bound.TWO_SIDED
                    ));
                } catch (IllegalArgumentException e) {  // includes NumberFormatException
                    throw new IllegalArgumentException(csvPath + " line " + lineNo + ": " + e.getMessage(), e);
                }
            }
            System.out.printf("[VALIDATION] Loaded %d baseline metrics from %s%n", baseline.size(), csvPath);
        } catch (IOException e) {
            System.err.println("[VALIDATION] Error reading baseline: " + e.getMessage());
        }
    }

    /**
     * Validate simulation results against baseline metrics.
     *
     * @param taxis     list of taxi agents
     * @param trips     list of all trips (passenger + empty)
     * @param config    simulation configuration
     * @return validation report with pass/fail results
     */
    public ValidationReport validate(List<TaxiAgent> taxis, List<TaxiTrip> trips, TaxiConfig config) {
        return validate(taxis, trips, config, null);
    }

    /**
     * As {@link #validate(List, List, TaxiConfig)}, with the run's spatial
     * validator so the operating-zone shares can be measured (null = no zone).
     */
    public ValidationReport validate(List<TaxiAgent> taxis, List<TaxiTrip> trips, TaxiConfig config,
                                     TaxiGeoValidator geoValidator) {
        // Compute all metrics from simulation output
        Map<String, Double> metrics = computeMetrics(taxis, trips, config, geoValidator);
        lastMetrics = metrics;
        lastZoneEnabled = geoValidator != null && geoValidator.isOperatingZoneEnabled();

        // For runtime-computed targets: scale by fleet size × configured trips average
        return new ValidationReport(compare(metrics, config.getTaxiFleetSize() * config.getTaxiTripsAverage()));
    }

    /**
     * Compares each baseline row with its computed value under the row's bound;
     * a row with no computed value is skipped.
     *
     * @param totalTripsTarget target for a "Total Passenger Trips" row whose survey value is 0
     */
    List<ValidationResult> compare(Map<String, Double> metrics, double totalTripsTarget) {
        List<ValidationResult> results = new ArrayList<>();
        for (BaselineMetric bm : baseline) {
            Double actual = metrics.get(bm.name);
            if (actual == null) {
                System.err.println("[VALIDATION] No computed value for metric: " + bm.name);
                continue;
            }
            double target = bm.surveyValue;
            if ("Total Passenger Trips".equals(bm.name) && target == 0.0) {
                target = totalTripsTarget;
            }
            results.add(new ValidationResult(bm.name, bm.category, actual, target, bm.toleranceFraction(), bm.bound));
        }
        return results;
    }

    /**
     * Writes validation_tiers.csv (the tiered bar) for the last validate() call;
     * the loaded baseline rows give each row's target, tolerance, bound and tier.
     */
    public void writeTiers(String csvPath) throws IOException {
        if (lastMetrics == null) throw new IllegalStateException("validate() has not been called");
        List<TaxiValidationTiers.TierRow> rows =
            TaxiValidationTiers.build(lastMetrics, baselineTargets(), lastZoneEnabled);
        TaxiValidationTiers.writeCsv(rows, csvPath);
        System.out.println("[VALIDATION] " + TaxiValidationTiers.summary(rows) + " -> " + csvPath);
    }

    /** The loaded rows as the tier bar reads them, in file order; a repeated name keeps its last row. */
    Map<String, TaxiValidationTiers.Target> baselineTargets() {
        Map<String, TaxiValidationTiers.Target> targets = new LinkedHashMap<>();
        for (BaselineMetric bm : baseline) {
            targets.put(bm.name, new TaxiValidationTiers.Target(bm.surveyValue, bm.tolerancePct, bm.tier, bm.bound));
        }
        return targets;
    }

    /** Metric names of the loaded rows, in file order. */
    List<String> baselineMetricNames() {
        return baseline.stream().map(bm -> bm.name).collect(Collectors.toList());
    }

    /** A finite number; a blank, unreadable or infinite value would grade wrongly or drop the row. */
    private static double finite(String s, String column) {
        double v = Double.parseDouble(s);
        if (!Double.isFinite(v)) throw new IllegalArgumentException(column + " is not finite: " + s);
        return v;
    }

    /** A, B or D in any case (Tier C is computed by the Python harness); empty keeps the defined tier. */
    private static String parseTier(String s) {
        String tier = s.toUpperCase(Locale.ROOT);
        if (tier.isEmpty() || tier.equals("A") || tier.equals("B") || tier.equals("D")) return tier;
        throw new IllegalArgumentException("unknown tier '" + s + "' (A, B, D or empty)");
    }

    /**
     * Dashboard rows that state the revenue basis (TASK-11): the consumption-tax rate and the revenue per taxi
     * and per passenger leg net of it, beside the existing tax-inclusive REVENUE rows.
     */
    static Map<String, Double> revenueTaxBasisMetrics(double totalRevenue, long passengerTrips, int fleetSize,
                                                      double taxRate) {
        double net = totalRevenue / (1.0 + taxRate);
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("REVENUE.consumption_tax_rate", taxRate);
        m.put("REVENUE.per_taxi_yen_excl_tax", fleetSize > 0 ? net / fleetSize : 0.0);
        m.put("REVENUE.avg_fare_yen_excl_tax", passengerTrips > 0 ? net / passengerTrips : 0.0);
        return m;
    }

    /**
     * Compute all validation metrics from simulation output.
     */
    private Map<String, Double> computeMetrics(List<TaxiAgent> taxis, List<TaxiTrip> trips, TaxiConfig config,
                                               TaxiGeoValidator geoValidator) {
        Map<String, Double> m = new LinkedHashMap<>();

        int fleetSize = config.getTaxiFleetSize();
        long passengerTrips = trips.stream().filter(TaxiTrip::isPassengerIn).count();
        long emptyTrips = trips.stream().filter(t -> !t.isPassengerIn()).count();
        long totalTrips = trips.size();

        double passengerDistance = trips.stream()
                .filter(TaxiTrip::isPassengerIn).mapToDouble(TaxiTrip::getDistanceKm).sum();
        double emptyDistance = trips.stream()
                .filter(t -> !t.isPassengerIn()).mapToDouble(TaxiTrip::getDistanceKm).sum();
        double totalDistance = passengerDistance + emptyDistance;

        double totalRevenue = trips.stream()
                .filter(TaxiTrip::isPassengerIn).mapToDouble(TaxiTrip::getFareYen).sum();

        long nightTrips = trips.stream()
                .filter(TaxiTrip::isPassengerIn).filter(TaxiTrip::isNightTrip).count();

        // Fleet metrics — 5-type when shift_time, 3-type when legacy.
        long localCount = taxis.stream().filter(t -> t.getTaxiType() == TaxiType.LOCAL).count();
        long citywideCount = taxis.stream().filter(t -> t.getTaxiType() == TaxiType.CITYWIDE).count();
        long appPreferredCount = taxis.stream().filter(t -> t.getTaxiType() == TaxiType.APP_PREFERRED).count();
        long hubCount = taxis.stream().filter(t -> t.getTaxiType() == TaxiType.HUB).count();
        long rideHailPrhsCount = taxis.stream().filter(t -> t.getTaxiType() == TaxiType.RIDE_HAIL_PRHS).count();

        // Fleet Operating Rate = 実働率 per MLIT 旅客自動車運送事業等報告規則
        // 延実働車両数 / 延実在車両数 × 100 (THTA FY2024 = 68.6% for 特別区・武三)
        m.put("Fleet Operating Rate", 100.0 * fleetSize / config.getTaxiTotalRegistered());
        m.put("LOCAL Type Share", 100.0 * localCount / fleetSize);
        m.put("CITYWIDE Type Share", 100.0 * citywideCount / fleetSize);
        m.put("HUB Type Share", 100.0 * hubCount / fleetSize);
        // B2 / Phase 4 (v7.0): two new fleet-type metrics. Always reported; will be 0
        // when running legacy engine (which only generates 3 types).
        m.put("APP_PREFERRED Type Share", 100.0 * appPreferredCount / fleetSize);
        m.put("RIDE_HAIL_PRHS Type Share", 100.0 * rideHailPrhsCount / fleetSize);

        // Trip metrics
        m.put("Avg Passenger Trips per Taxi", (double) passengerTrips / fleetSize);
        m.put("Total Passenger Trips", (double) passengerTrips);

        // Distance metrics
        double avgDistance = passengerTrips > 0 ? passengerDistance / passengerTrips : 0.0;
        m.put("Avg Trip Distance", avgDistance);
        m.put("Total Daily Distance per Taxi", totalDistance / fleetSize);

        // Empty / occupancy metrics — MLIT 旅客自動車運送事業等報告規則 definitions
        // 実車率 (loaded distance ratio) = 実車キロ / 走行キロ × 100
        // 空車率 (empty distance ratio) = 1 - 実車率
        m.put("Loaded Distance Ratio", totalDistance > 0 ? 100.0 * passengerDistance / totalDistance : 0.0);
        m.put("Empty Running Ratio (distance)", totalDistance > 0 ? 100.0 * emptyDistance / totalDistance : 0.0);
        m.put("Empty Trip Count Ratio", totalTrips > 0 ? 100.0 * emptyTrips / totalTrips : 0.0);

        // Revenue metrics, on the targets' basis: THTA revenue excludes consumption tax, tariff fares include it
        double netRevenue = totalRevenue / (1.0 + config.getFareConsumptionTaxRate());
        double avgFare = passengerTrips > 0 ? netRevenue / passengerTrips : 0.0;
        m.put("Avg Fare", avgFare);
        m.put("Total Daily Revenue per Taxi", netRevenue / fleetSize);

        // Temporal metrics
        m.put("Night Trip Ratio", passengerTrips > 0 ? 100.0 * nightTrips / passengerTrips : 0.0);

        // Spatial metrics — measured (WP0). Until 2026-10 these rows echoed
        // nearby.trip.probability and a constant 99.5; the shift-time engine
        // has no nearby-trip mechanism, so the ratio now measures the realised
        // pickup-to-previous-drop-off distance from each taxi's trip chain.
        m.put("Nearby Trip Ratio", TaxiSpatialMetrics.nearbyTripRatio(taxis, config.getNearbyTripRadiusKm()));
        m.put("Trips Within City Bounds", TaxiSpatialMetrics.bothEndsInBoxShare(trips,
                config.getBoundsMinLon(), config.getBoundsMaxLon(),
                config.getBoundsMinLat(), config.getBoundsMaxLat()));
        if (geoValidator != null && geoValidator.isOperatingZoneEnabled()) {
            m.put(TaxiValidationTiers.ZONE_PICKUPS,
                    TaxiSpatialMetrics.pickupInsideShare(trips, geoValidator::isInOperatingZone));
            m.put(TaxiValidationTiers.ZONE_BOTH_OUTSIDE,
                    TaxiSpatialMetrics.bothEndsOutsideShare(trips, geoValidator::isInOperatingZone));
        }
        m.put("Vacancy Time Share", TaxiSpatialMetrics.vacancyTimeShare(taxis));

        // Distance range checks
        double minDist = trips.stream().filter(TaxiTrip::isPassengerIn)
                .mapToDouble(TaxiTrip::getDistanceKm).min().orElse(0.0);
        double maxDist = trips.stream().filter(TaxiTrip::isPassengerIn)
                .mapToDouble(TaxiTrip::getDistanceKm).max().orElse(0.0);
        m.put("Trip Distance Min Check", minDist);
        m.put("Trip Distance Max Check", maxDist);

        return m;
    }
}
