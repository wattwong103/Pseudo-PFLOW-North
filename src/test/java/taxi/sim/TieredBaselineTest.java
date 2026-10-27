package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import taxi.sim.TaxiValidationEngine.Bound;
import taxi.sim.TaxiValidationTiers.Target;
import taxi.sim.TaxiValidationTiers.TierRow;

/** Tier, bound and threshold come from the baseline; DEFS keeps the definitions and the fallback tier. */
class TieredBaselineTest {

    static final String PICKUPS = TaxiValidationTiers.ZONE_PICKUPS;
    static final String BOTH_OUTSIDE = TaxiValidationTiers.ZONE_BOTH_OUTSIDE;

    static Map<String, Target> matching(Map<String, Double> metrics) {
        Map<String, Target> b = new java.util.HashMap<>();
        metrics.forEach((k, v) -> b.put(k, Target.of(v, 5)));
        return b;
    }

    static TierRow row(List<TierRow> rows, String metric) {
        return TaxiValidationTiersTest.row(rows, metric);
    }

    @Test
    void zoneRowsAreGradedFromTheBaselineValue() {
        Map<String, Double> m = Wp0ReviewFixesTest.allMetrics();
        m.put(PICKUPS, 98.5);       // fails a 99.0 bound, passes 98.0
        m.put(BOTH_OUTSIDE, 0.7);   // fails a 0.5 bound, passes 1.0
        Map<String, Target> b = matching(m);
        b.put(PICKUPS, new Target(98.0, 0, "A", Bound.AT_LEAST));
        b.put(BOTH_OUTSIDE, new Target(1.0, 0, "A", Bound.AT_MOST));
        List<TierRow> rows = TaxiValidationTiers.build(m, b, true);
        assertEquals("PASS", row(rows, PICKUPS).status);
        assertEquals(98.0, row(rows, PICKUPS).target, 1e-12);
        assertEquals(">= 98.0", row(rows, PICKUPS).note);
        assertEquals("PASS", row(rows, BOTH_OUTSIDE).status);
        assertEquals("<= 1.0", row(rows, BOTH_OUTSIDE).note);

        b.put(PICKUPS, new Target(99.0, 0, "A", Bound.AT_LEAST));
        b.put(BOTH_OUTSIDE, new Target(0.5, 0, "A", Bound.AT_MOST));
        rows = TaxiValidationTiers.build(m, b, true);
        assertEquals("FAIL", row(rows, PICKUPS).status);
        assertEquals("FAIL", row(rows, BOTH_OUTSIDE).status);
    }

    @Test
    void aMissingZoneRowIsNotGradedAndCountsAgainstTierA() {
        Map<String, Double> m = Wp0ReviewFixesTest.allMetrics();
        Map<String, Target> b = matching(m);
        b.remove(PICKUPS);
        List<TierRow> rows = TaxiValidationTiers.build(m, b, true);
        assertEquals("NO_TARGET", row(rows, PICKUPS).status);
        String s = TaxiValidationTiers.summary(rows);
        assertTrue(s.contains("Tier A: 9/10 hold"), s);
        assertTrue(s.contains("not graded: " + PICKUPS), s);
    }

    @Test
    void zoneRowsWithoutABaselineStillDropOutWhenTheZoneIsOff() {
        Map<String, Double> m = Wp0ReviewFixesTest.allMetrics();
        m.remove(PICKUPS);
        m.remove(BOTH_OUTSIDE);
        Map<String, Target> b = matching(m);
        String s = TaxiValidationTiers.summary(TaxiValidationTiers.build(m, b, false));
        assertTrue(s.startsWith("Tier A: 8/8 hold; Tier B: 7/7"), s);
        assertFalse(s.contains("not graded"), s);
    }

    @Test
    void theBaselineBoundOverridesTheDefsBound() {
        Map<String, Double> m = Wp0ReviewFixesTest.allMetrics();
        Map<String, Target> b = matching(m);
        m.put("Trip Distance Max Check", 15.33);
        b.put("Trip Distance Max Check", new Target(15.7, 0, "A", Bound.AT_MOST));
        TierRow max = row(TaxiValidationTiers.build(m, b, true), "Trip Distance Max Check");
        assertEquals("PASS", max.status);
        assertEquals("<= 15.7", max.note);
        assertTrue(Double.isNaN(max.tolerancePct));
        m.put("Trip Distance Max Check", 15.71);  // inside a two-sided +-50%, outside the bound
        assertEquals("FAIL", row(TaxiValidationTiers.build(m, b, true), "Trip Distance Max Check").status);
    }

    @Test
    void theBaselineTierOverridesTheDefsTier() {
        Map<String, Double> m = Wp0ReviewFixesTest.allMetrics();
        Map<String, Target> b = matching(m);
        b.put("Avg Fare", new Target(10.0, 5, "D", Bound.TWO_SIDED));
        List<TierRow> rows = TaxiValidationTiers.build(m, b, true);
        assertEquals("D", row(rows, "Avg Fare").tier);
        assertEquals("INFO", row(rows, "Avg Fare").status);
        assertTrue(TaxiValidationTiers.summary(rows).contains("Tier B: 6/6"));
    }

    @Test
    void retiredRowsStayTierDWithNoTarget() {
        Map<String, Double> m = Wp0ReviewFixesTest.allMetrics();
        m.put("Nearby Trip Ratio", 46.7);
        List<TierRow> rows = TaxiValidationTiers.build(m, matching(Wp0ReviewFixesTest.allMetrics()), true);
        TierRow nearby = row(rows, "Nearby Trip Ratio");
        assertEquals("D", nearby.tier);
        assertEquals("INFO", nearby.status);
        assertTrue(Double.isNaN(nearby.target));
    }

    // ── the shipped Tokyo baselines (B3) ──

    static Map<String, Target> shipped(String file) {
        TaxiValidationEngine e = new TaxiValidationEngine();
        e.loadBaseline(BaselineBoundTest.validationDir().resolve(file).toString());
        return e.baselineTargets();
    }

    @Test
    void shippedTokyoBaselineTiersEqualTheDefsTiers() {
        for (String file : new String[] {"taxi_baseline_tokyo.csv", "taxi_baseline_tokyo_fy2023.csv"}) {
            Map<String, Target> t = shipped(file);
            assertFalse(t.isEmpty(), file);
            t.forEach((metric, target) -> {
                String def = TaxiValidationTiers.defaultTier(metric);
                assertNotNull(def, file + ": no DEFS entry for " + metric);
                assertEquals(def, target.tier, file + ": tier drift on " + metric);
            });
            for (String metric : TaxiValidationTiers.definedMetrics()) {
                String tier = TaxiValidationTiers.defaultTier(metric);
                if (!tier.equals("D")) assertNotNull(t.get(metric), file + " has no row for Tier " + tier + " " + metric);
            }
        }
    }

    // ── the 15.7 km bound is derived from the config (calibration-guardian condition C3) ──

    static TaxiConfig tokyoConfig() {
        TaxiConfig c = TaxiConfig.getInstance();
        Path file = BaselineBoundTest.validationDir().getParent().resolve("tokyo").resolve("taxi_config.properties");
        assertTrue(c.loadFromFile(file.toString()));
        return c;
    }

    /**
     * Longest stored passenger leg the loaded-leg sampler can produce: a draw at
     * trip.distance.max placed as in TaxiSimulation.sampleLogNormalLoadedLeg
     * (111.32 km per degree, uniform bearing), then moved by spiralSearch to each
     * candidate of its outermost ring, measured with TaxiTrip.distanceKm.
     */
    static double worstProjectedLegKm(TaxiConfig c, double radiusKm) {
        double haversineKm = c.getTripDistanceMax() / c.getManhattanFactor();
        double worst = 0;
        for (int i = 0; i <= 7; i++) {
            double lat = 35.50 + 0.05 * i;
            double cosLat = Math.cos(Math.toRadians(lat));
            for (int k = 0; k < 360; k++) {
                double bearing = 2.0 * Math.PI * k / 360;
                double dropLon = 139.7 + (haversineKm * Math.cos(bearing)) / (111.32 * cosLat);
                double dropLat = lat + (haversineKm * Math.sin(bearing)) / 111.32;
                double dropCos = Math.cos(Math.toRadians(dropLat));
                for (int a = 0; a < 8; a++) {
                    final int index = a;
                    double[] p = TaxiSimulation.spiralSearch(dropLon, dropLat, radiusKm, (x, y) -> {
                        double dx = (x - dropLon) * 111.32 * dropCos, dy = (y - dropLat) * 111.32;
                        long sector = Math.round(Math.atan2(dy, dx) / (Math.PI / 4));
                        return Math.hypot(dx, dy) > radiusKm - 1e-6 && Math.floorMod(sector, 8) == index;
                    });
                    assertNotNull(p, "no outermost-ring candidate at index " + a);
                    worst = Math.max(worst, TaxiTrip.distanceKm(139.7, lat, p[0], p[1]));
                }
            }
        }
        return worst;
    }

    @Test
    void theDistanceBoundsAreDerivedFromTheTokyoConfig() {
        TaxiConfig c = tokyoConfig();
        double derivedMax = c.getTripDistanceMax() + c.getManhattanFactor() * c.getProjectionRadiusKm();
        for (String file : new String[] {"taxi_baseline_tokyo.csv", "taxi_baseline_tokyo_fy2023.csv"}) {
            Map<String, Target> t = shipped(file);
            assertEquals(derivedMax, t.get("Trip Distance Max Check").value, 1e-9, file);
            assertEquals(c.getTripDistanceMin(), t.get("Trip Distance Min Check").value, 1e-12, file);
        }
    }

    @Test
    void noProjectedLegCanExceedTheMaxDistanceBound() {
        TaxiConfig c = tokyoConfig();
        double bound = shipped("taxi_baseline_tokyo.csv").get("Trip Distance Max Check").value;
        double worst = worstProjectedLegKm(c, c.getProjectionRadiusKm());
        assertTrue(worst <= bound, "worst case " + worst + " km > bound " + bound);
        assertTrue(worst > c.getTripDistanceMax() + 0.5, "the probe must reach past trip.distance.max: " + worst);
        // the probe has teeth: a 0.6 km projection radius would break the bound
        assertTrue(worstProjectedLegKm(c, 0.6) > bound);
    }

    @Test
    void shippedTokyoBaselinesCarryTheZoneAndDistanceBounds() {
        for (String file : new String[] {"taxi_baseline_tokyo.csv", "taxi_baseline_tokyo_fy2023.csv"}) {
            Map<String, Target> t = shipped(file);
            assertBound(t, file, PICKUPS, 99.0, Bound.AT_LEAST);
            assertBound(t, file, BOTH_OUTSIDE, 0.5, Bound.AT_MOST);
            assertBound(t, file, "Trip Distance Min Check", 0.5, Bound.AT_LEAST);
            assertBound(t, file, "Trip Distance Max Check", 15.7, Bound.AT_MOST);
            for (String retired : new String[] {"Nearby Trip Ratio", "Trips Within City Bounds",
                    "Empty Trip Count Ratio"}) {
                assertNull(t.get(retired), file + " still grades " + retired);
            }
            for (String type : new String[] {"LOCAL", "CITYWIDE", "APP_PREFERRED", "HUB", "RIDE_HAIL_PRHS"}) {
                assertNotNull(t.get(type + " Type Share"), file + " lacks the " + type + " share");
            }
        }
    }

    @Test
    void fy2023CarriesTheFiveTypeDesignShares() {
        Map<String, Target> fy24 = shipped("taxi_baseline_tokyo.csv");
        Map<String, Target> fy23 = shipped("taxi_baseline_tokyo_fy2023.csv");
        for (String type : new String[] {"LOCAL", "CITYWIDE", "APP_PREFERRED", "HUB", "RIDE_HAIL_PRHS"}) {
            String k = type + " Type Share";
            assertEquals(fy24.get(k).value, fy23.get(k).value, 1e-12, k);
            assertEquals(fy24.get(k).tolerancePct, fy23.get(k).tolerancePct, 1e-12, k);
        }
    }

    private static void assertBound(Map<String, Target> t, String file, String metric, double value, Bound bound) {
        Target target = t.get(metric);
        assertNotNull(target, file + " lacks " + metric);
        assertEquals(value, target.value, 1e-12, file + ": " + metric);
        assertEquals(bound, target.bound, file + ": " + metric);
        assertEquals("A", target.tier, file + ": " + metric);
    }
}
