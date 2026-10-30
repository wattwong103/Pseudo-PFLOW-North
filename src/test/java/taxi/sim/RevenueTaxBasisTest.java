package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Revenue graded on the targets' tax basis (TASK-11). THTA's revenue targets exclude consumption tax while the
 * tariff fares include it, so fare.consumption.tax.rate divides the two revenue metrics, and nothing else, by
 * (1 + rate). Fares themselves (trips files) keep what passengers pay.
 */
class RevenueTaxBasisTest {

    @TempDir
    Path dir;

    static final double LAT = 35.69;
    static final String HEADER = "metric_name,category,survey_value,unit,tolerance_pct,source,notes,priority,tier,bound\n";
    static final List<String> GRADED = Arrays.asList("Avg Fare", "Total Daily Revenue per Taxi",
        "Avg Passenger Trips per Taxi", "Avg Trip Distance", "Loaded Distance Ratio", "Total Daily Distance per Taxi");

    private TaxiConfig load(String extra) throws Exception {
        Path props = dir.resolve("taxi_config.properties");
        Files.write(props, ("city.name=Tokyo\ntaxi.fleet.size=2\n" + extra).getBytes(StandardCharsets.UTF_8));
        TaxiConfig config = TaxiConfig.getInstance();
        assertTrue(config.loadFromFile(props.toString()));
        return config;
    }

    private void reset() throws Exception {
        load("");
    }

    /** Two loaded legs and one empty leg on two taxis; returns metric -> actual for the graded rows. */
    private Map<String, Double> actuals(TaxiConfig config, List<TaxiTrip> trips) throws Exception {
        TaxiAgent a = new TaxiAgent(1, TaxiType.LOCAL, 139.70, LAT, 0L, 36_000L, 5.0);
        TaxiAgent b = new TaxiAgent(2, TaxiType.LOCAL, 139.70, LAT, 0L, 36_000L, 5.0);
        a.assignTrip(trips.get(0));
        a.assignTrip(trips.get(2));
        b.assignTrip(trips.get(1));
        StringBuilder csv = new StringBuilder(HEADER);
        for (String metric : GRADED) csv.append(metric).append(",X,1,u,50,src,notes,HIGH,B,two_sided\n");
        Path baseline = dir.resolve("baseline.csv");
        Files.write(baseline, csv.toString().getBytes(StandardCharsets.UTF_8));
        TaxiValidationEngine engine = new TaxiValidationEngine();
        engine.loadBaseline(baseline.toString());
        Map<String, Double> out = new HashMap<>();
        for (TaxiValidationEngine.ValidationResult r : engine.validate(Arrays.asList(a, b), trips, config).results) {
            out.put(r.metric, r.actual);
        }
        return out;
    }

    private static List<TaxiTrip> trips() {
        return Arrays.asList(
            new TaxiTrip(1, 139.70, LAT, 139.73, LAT, 36_000L, true),
            new TaxiTrip(2, 139.73, LAT, 139.77, LAT, 40_000L, true),
            new TaxiTrip(3, 139.73, LAT, 139.74, LAT, 41_000L, false));
    }

    @Test
    void theRevenueMetricsAreTheSummedFaresByDefault() throws Exception {
        try {
            TaxiConfig config = load("");
            assertEquals(0.0, config.getFareConsumptionTaxRate());
            List<TaxiTrip> trips = trips();
            double fares = trips.get(0).getFareYen() + trips.get(1).getFareYen();
            Map<String, Double> m = actuals(config, trips);
            assertEquals(fares / 2, m.get("Avg Fare"), 1e-9);
            assertEquals(fares / 2, m.get("Total Daily Revenue per Taxi"), 1e-9);
        } finally {
            reset();
        }
    }

    @Test
    void aTaxRateDividesOnlyTheTwoRevenueMetricsAndLeavesFaresAlone() throws Exception {
        try {
            List<TaxiTrip> base = trips();
            Map<String, Double> untaxed = actuals(load(""), base);
            TaxiConfig config = load("fare.consumption.tax.rate=0.10\n");
            assertEquals(0.10, config.getFareConsumptionTaxRate());
            List<TaxiTrip> taxedTrips = trips();
            for (int i = 0; i < base.size(); i++) {
                assertEquals(base.get(i).getFareYen(), taxedTrips.get(i).getFareYen(), 0.0, "fare of trip " + i);
            }
            Map<String, Double> taxed = actuals(config, taxedTrips);
            for (String metric : GRADED) {
                boolean revenue = metric.equals("Avg Fare") || metric.equals("Total Daily Revenue per Taxi");
                double expected = revenue ? untaxed.get(metric) / 1.10 : untaxed.get(metric);
                assertEquals(expected, taxed.get(metric), 1e-9, metric);
            }
        } finally {
            reset();
        }
    }

    @Test
    void anInvalidTaxRateStopsTheRunAtConfigLoad() throws Exception {
        try {
            for (String bad : Arrays.asList("-0.1", "1.0", "1.5", "NaN", "Infinity", "abc", "")) {
                assertThrows(IllegalArgumentException.class,
                    () -> load("fare.consumption.tax.rate=" + bad + "\n"), "rate '" + bad + "'");
            }
        } finally {
            reset();
        }
    }

    @Test
    void theDashboardLabelsTheRevenueBasis() {
        Map<String, Double> rows = TaxiValidationEngine.revenueTaxBasisMetrics(11_000.0, 4, 2, 0.10);
        assertEquals(0.10, rows.get("REVENUE.consumption_tax_rate"), 0.0);
        assertEquals(5_000.0, rows.get("REVENUE.per_taxi_yen_excl_tax"), 1e-9);
        assertEquals(2_500.0, rows.get("REVENUE.avg_fare_yen_excl_tax"), 1e-9);
        assertEquals(0.0, TaxiValidationEngine.revenueTaxBasisMetrics(0.0, 0, 2, 0.10).get("REVENUE.avg_fare_yen_excl_tax"));
    }
}
