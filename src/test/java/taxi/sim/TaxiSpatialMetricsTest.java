package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

/** Measured spatial and temporal metrics that replace the echoed/constant validation rows. */
class TaxiSpatialMetricsTest {

    static final double LAT = 35.69;
    static final double KM_PER_DEG_LON = 111.32 * Math.cos(Math.toRadians(LAT));

    /** Longitude offset (degrees) for an east-west distance of km at LAT. */
    static double east(double km) {
        return km / KM_PER_DEG_LON;
    }

    static TaxiTrip loaded(double fromLon, double toLon) {
        return new TaxiTrip(0, fromLon, LAT, toLon, LAT, 0L, true);
    }

    static TaxiTrip empty(double fromLon, double toLon) {
        return new TaxiTrip(0, fromLon, LAT, toLon, LAT, 0L, false);
    }

    static TaxiAgent taxiWith(TaxiTrip... legs) {
        TaxiAgent taxi = new TaxiAgent(1, TaxiType.LOCAL, 139.70, LAT, 0L, 36_000L, 5.0);
        for (TaxiTrip leg : legs) taxi.assignTrip(leg);
        return taxi;
    }

    @Test
    void nearbyRatioIsHalfWhenOneOfTwoPickupsIsWithinRadius() {
        double d1 = 139.70;
        double p2 = d1 + east(1.0);   // 1 km from the previous drop-off
        double d2 = p2 + east(3.0);
        double p3 = d2 + east(4.0);   // 4 km from the previous drop-off
        TaxiAgent taxi = taxiWith(loaded(d1 - east(2.0), d1), loaded(p2, d2), loaded(p3, p3 + east(1.0)));
        assertEquals(50.0, TaxiSpatialMetrics.nearbyTripRatio(Collections.singletonList(taxi), 2.5), 1e-9);
    }

    @Test
    void firstPassengerLegHasNoPreviousDropOffSoNoDataIsNaN() {
        TaxiAgent taxi = taxiWith(loaded(139.70, 139.71));
        assertTrue(Double.isNaN(TaxiSpatialMetrics.nearbyTripRatio(Collections.singletonList(taxi), 2.5)));
    }

    @Test
    void emptyLegsDoNotResetThePreviousDropOff() {
        double d1 = 139.70;
        double p2 = d1 + east(1.0);
        // The empty leg runs 1 km from D1 to P2; the loaded pickup P2 is still 1 km from D1.
        TaxiAgent taxi = taxiWith(loaded(d1 - east(2.0), d1), empty(d1, p2), loaded(p2, p2 + east(3.0)));
        assertEquals(100.0, TaxiSpatialMetrics.nearbyTripRatio(Collections.singletonList(taxi), 2.5), 1e-9);
    }

    @Test
    void pickupInsideShareCountsPassengerLegsOnly() {
        BiPredicate<Double, Double> westOf13970 = (lon, lat) -> lon < 139.70;
        List<TaxiTrip> trips = Arrays.asList(
            loaded(139.69, 139.75),   // pickup inside
            loaded(139.71, 139.65),   // pickup outside
            empty(139.60, 139.61));   // ignored
        assertEquals(50.0, TaxiSpatialMetrics.pickupInsideShare(trips, westOf13970), 1e-9);
    }

    @Test
    void bothEndsOutsideShareNeedsBothEndsOutside() {
        BiPredicate<Double, Double> westOf13970 = (lon, lat) -> lon < 139.70;
        List<TaxiTrip> trips = Arrays.asList(
            loaded(139.71, 139.72),   // both outside
            loaded(139.71, 139.69),   // drop inside
            loaded(139.68, 139.69),   // both inside
            empty(139.80, 139.81));   // ignored
        assertEquals(100.0 / 3.0, TaxiSpatialMetrics.bothEndsOutsideShare(trips, westOf13970), 1e-9);
    }

    @Test
    void boxShareNeedsBothEndsInTheBox() {
        List<TaxiTrip> trips = Arrays.asList(
            loaded(139.60, 139.70),   // both in [139.55, 139.92]
            loaded(139.60, 139.95));  // drop-off east of the box
        assertEquals(50.0, TaxiSpatialMetrics.bothEndsInBoxShare(trips, 139.55, 139.92, 35.50, 35.82), 1e-9);
    }

    @Test
    void vacancyIsShiftTimeNotSpentWithAPassenger() {
        TaxiTrip a = loaded(139.70, 139.71);
        a.setTimes(1_000L, 1_000L + 7_200L);    // 2 h loaded
        TaxiTrip b = loaded(139.71, 139.72);
        b.setTimes(20_000L, 20_000L + 7_200L);  // 2 h loaded
        TaxiTrip e = empty(139.72, 139.73);
        e.setTimes(30_000L, 33_000L);           // empty time does not count as loaded
        TaxiAgent taxi = taxiWith(a, e, b);     // 10 h shift (0..36000 s)
        assertEquals(60.0, TaxiSpatialMetrics.vacancyTimeShare(Collections.singletonList(taxi)), 1e-9);
    }
}
