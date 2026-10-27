package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The tiered validation sidecar (new bar, decision Q2): A constraints, B regulatory, D diagnostics. */
class TaxiValidationTiersTest {

    static Map<String, Double> metrics(boolean withZone) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("Fleet Operating Rate", 68.6);
        m.put("Avg Passenger Trips per Taxi", 25.6);
        m.put("Total Daily Revenue per Taxi", 48_050.0);
        m.put("Nearby Trip Ratio", 44.9);
        m.put("Trips Within City Bounds", 73.7);
        if (withZone) {
            m.put("Pickups Within Operating Zone", 99.6);
            m.put("Both Ends Outside Operating Zone", 0.7);
        }
        return m;
    }

    static Map<String, TaxiValidationTiers.Target> baseline() {
        Map<String, TaxiValidationTiers.Target> b = new HashMap<>();
        b.put("Fleet Operating Rate", TaxiValidationTiers.Target.of(68.6, 5));
        b.put("Avg Passenger Trips per Taxi", TaxiValidationTiers.Target.of(25.3, 10));
        b.put("Total Daily Revenue per Taxi", TaxiValidationTiers.Target.of(56_139.0, 15));
        b.put("Nearby Trip Ratio", TaxiValidationTiers.Target.of(80.0, 20));
        b.put("Trips Within City Bounds", TaxiValidationTiers.Target.of(99.0, 2));
        b.put("Pickups Within Operating Zone",
            new TaxiValidationTiers.Target(99.0, 0, "A", TaxiValidationEngine.Bound.AT_LEAST));
        b.put("Both Ends Outside Operating Zone",
            new TaxiValidationTiers.Target(0.5, 0, "A", TaxiValidationEngine.Bound.AT_MOST));
        return b;
    }

    static TaxiValidationTiers.TierRow row(List<TaxiValidationTiers.TierRow> rows, String metric) {
        return rows.stream().filter(r -> r.metric.equals(metric)).findFirst()
            .orElseThrow(() -> new AssertionError("missing row " + metric));
    }

    @Test
    void regulatoryRowsAreTierBGradedOnTheirBaselineTolerance() {
        List<TaxiValidationTiers.TierRow> rows = TaxiValidationTiers.build(metrics(true), baseline(), true);
        assertEquals("B", row(rows, "Avg Passenger Trips per Taxi").tier);
        assertEquals("PASS", row(rows, "Avg Passenger Trips per Taxi").status);
        assertEquals("PASS", row(rows, "Total Daily Revenue per Taxi").status);   // -14.4% within 15%
        assertEquals(15.0, row(rows, "Total Daily Revenue per Taxi").tolerancePct, 1e-12);
    }

    @Test
    void echoedAndBoxRowsAreTierDDiagnosticsNotGraded() {
        List<TaxiValidationTiers.TierRow> rows = TaxiValidationTiers.build(metrics(true), baseline(), true);
        assertEquals("D", row(rows, "Nearby Trip Ratio").tier);
        assertEquals("INFO", row(rows, "Nearby Trip Ratio").status);
        assertEquals("D", row(rows, "Trips Within City Bounds").tier);
        assertEquals("INFO", row(rows, "Trips Within City Bounds").status);
    }

    @Test
    void zoneRowsAreOneSidedTierAConstraints() {
        List<TaxiValidationTiers.TierRow> rows = TaxiValidationTiers.build(metrics(true), baseline(), true);
        assertEquals("A", row(rows, "Pickups Within Operating Zone").tier);
        assertEquals("PASS", row(rows, "Pickups Within Operating Zone").status);    // 99.6 >= 99
        assertEquals("FAIL", row(rows, "Both Ends Outside Operating Zone").status); // 0.7 > 0.5
        assertEquals("A", row(rows, "Fleet Operating Rate").tier);
    }

    @Test
    void zoneRowsReadNotApplicableWhenTheZoneIsDisabled() {
        List<TaxiValidationTiers.TierRow> rows = TaxiValidationTiers.build(metrics(false), baseline(), false);
        TaxiValidationTiers.TierRow pickups = row(rows, "Pickups Within Operating Zone");
        assertEquals("INFO", pickups.status);
        assertEquals("n/a (operating zone disabled)", pickups.note);
        assertFalse(Double.isFinite(pickups.actual));
    }

    @Test
    void everyRowCarriesADefinition() {
        for (TaxiValidationTiers.TierRow r : TaxiValidationTiers.build(metrics(true), baseline(), true)) {
            assertFalse(r.definition.isEmpty(), "no definition for " + r.metric);
        }
    }
}
