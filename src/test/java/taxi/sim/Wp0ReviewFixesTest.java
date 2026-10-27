package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Regression tests for the WP0 whole-branch review findings (Codex, code-reviewer). */
class Wp0ReviewFixesTest {

    // ── tier gate must not drop rows from its denominator ──

    static Map<String, Double> allMetrics() {
        Map<String, Double> m = new LinkedHashMap<>();
        for (String k : new String[] {"Fleet Operating Rate", "LOCAL Type Share", "CITYWIDE Type Share",
                "APP_PREFERRED Type Share", "HUB Type Share", "RIDE_HAIL_PRHS Type Share",
                "Trip Distance Min Check", "Trip Distance Max Check", "Avg Passenger Trips per Taxi",
                "Total Daily Distance per Taxi", "Loaded Distance Ratio", "Total Daily Revenue per Taxi",
                "Avg Fare", "Avg Trip Distance", "Night Trip Ratio"}) {
            m.put(k, 10.0);
        }
        m.put(TaxiValidationTiers.ZONE_PICKUPS, 100.0);
        m.put(TaxiValidationTiers.ZONE_BOTH_OUTSIDE, 0.0);
        return m;
    }

    static Map<String, TaxiValidationTiers.Target> baselineMatching(Map<String, Double> metrics) {
        Map<String, TaxiValidationTiers.Target> b = new HashMap<>();
        metrics.forEach((k, v) -> b.put(k, TaxiValidationTiers.Target.of(v, 5)));
        return b;
    }

    @Test
    void aTierBRowWithoutBaselineStillCountsInTheDenominator() {
        Map<String, Double> m = allMetrics();
        Map<String, TaxiValidationTiers.Target> b = baselineMatching(m);
        b.remove("Loaded Distance Ratio");
        String s = TaxiValidationTiers.summary(TaxiValidationTiers.build(m, b, true));
        assertTrue(s.contains("Tier B: 6/7"), s);
        assertTrue(s.contains("not graded: Loaded Distance Ratio"), s);
    }

    @Test
    void aMissingMetricCountsAgainstItsTier() {
        Map<String, Double> m = allMetrics();
        Map<String, TaxiValidationTiers.Target> b = baselineMatching(m);
        m.remove("Avg Fare");
        String s = TaxiValidationTiers.summary(TaxiValidationTiers.build(m, b, true));
        assertTrue(s.contains("Tier B: 6/7"), s);
    }

    @Test
    void summaryStatesWhetherTheZoneWasOn() {
        Map<String, Double> m = allMetrics();
        Map<String, TaxiValidationTiers.Target> b = baselineMatching(m);
        assertTrue(TaxiValidationTiers.summary(TaxiValidationTiers.build(m, b, true))
            .contains("Tier A: 10/10 hold; Tier B: 7/7 within THTA tolerance; operating zone on"));
        m.remove(TaxiValidationTiers.ZONE_PICKUPS);
        m.remove(TaxiValidationTiers.ZONE_BOTH_OUTSIDE);
        assertTrue(TaxiValidationTiers.summary(TaxiValidationTiers.build(m, b, false))
            .contains("Tier A: 8/8 hold; Tier B: 7/7 within THTA tolerance; operating zone off"));
    }

    // ── fail fast when nothing is eligible ──

    @Test
    void noEligibleZoneIsFatal() {
        assertThrows(IllegalStateException.class, () -> TaxiSimulation.checkEligibility(0, 3, 0));
    }

    @Test
    void hubsInTheFileButNoneEligibleIsFatal() {
        assertThrows(IllegalStateException.class, () -> TaxiSimulation.checkEligibility(60, 18, 0));
        assertDoesNotThrow(() -> TaxiSimulation.checkEligibility(60, 18, 12));
        assertDoesNotThrow(() -> TaxiSimulation.checkEligibility(60, 0, 0));
    }

    // ── roulette keeps the legacy behaviour for eligible zones ──

    @Test
    void anEligibleZeroScoreZoneAtRZeroIsReturnedAsTheLegacyLoopDid() {
        DestinationZone first = InZoneCruiseTest.zone("Z00", 139.70, 35.69, false, true);
        DestinationZone second = InZoneCruiseTest.zone("Z01", 139.71, 35.69, false, true);
        List<DestinationZone> zones = Arrays.asList(first, second);
        assertSame(first, ShiftSimulator.rouletteZone(zones, new double[] {0.0, 2.0}, 0.0));
    }

    // ── anchor search must find a point that is valid AND in the zone ──

    @Test
    void spiralSearchSkipsAValidPointOutsideTheZone() {
        // Valid land everywhere except the centre; the zone is only the area north of the centre.
        double lon = 139.80, lat = 35.70;
        double[] p = TaxiSimulation.spiralSearch(lon, lat, 0.5,
            (x, y) -> !(x == lon && y == lat) && y > lat);
        assertTrue(p != null && p[1] > lat, "expected a point north of the centre");
    }

    @Test
    void spiralSearchVisitsRingsInTheLegacyOrder() {
        // First acceptable cell is the 50 m ring at bearing 0 (due east), as in projectToNearestValidCell.
        double lon = 139.80, lat = 35.70;
        double[] p = TaxiSimulation.spiralSearch(lon, lat, 0.5, (x, y) -> !(x == lon && y == lat));
        double expectedLon = lon + 0.05 / (111.32 * Math.cos(Math.toRadians(lat)));
        assertArrayEquals(new double[] {expectedLon, lat}, p, 1e-12);
        assertEquals(null, TaxiSimulation.spiralSearch(lon, lat, 0.5, (x, y) -> false));
    }
}
