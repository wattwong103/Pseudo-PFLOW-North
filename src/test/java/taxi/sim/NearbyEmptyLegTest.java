package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TASK-13: a DUAL_MODE empty leg that "cruises nearby" ends within nearby.trip.radius.km of the
 * drop-off but is driven, timed and recorded over its drawn road km (so total empty km, m_E and the
 * loaded-km ratio are unchanged in expectation). Probability 0, or the key off, is the old behaviour.
 */
class NearbyEmptyLegTest {

    static final double LON = 139.70, LAT = 35.69;
    static final double M_E = 5.1, SIGMA = 0.7, RADIUS = 2.5, MANHATTAN = 1.4;

    @TempDir
    Path dir;

    private static double greatCircleKm(double lon1, double lat1, double lon2, double lat2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private TaxiConfig load(boolean use, double p) throws Exception {
        Path file = dir.resolve("taxi_config.properties");
        String text = "city.name=Tokyo\n"
            + "trip.distance.empty.mean.km=" + M_E + "\n"
            + "trip.distance.empty.sigma=" + SIGMA + "\n"
            + "distance.manhattan.factor=" + MANHATTAN + "\n"
            + "use.nearby.trips=" + use + "\n"
            + "nearby.trip.probability=" + p + "\n"
            + "nearby.trip.radius.km=" + RADIUS + "\n";
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        TaxiConfig config = TaxiConfig.getInstance();
        assertTrue(config.loadFromFile(file.toString()));
        return config;
    }

    /** Leaves the shared singleton with the keys' defaults for the tests that follow. */
    private void restoreDefaults() throws Exception {
        Path file = dir.resolve("taxi_config.properties");
        Files.write(file, ("city.name=Tokyo\nuse.nearby.trips=true\nnearby.trip.probability=0.70\n"
            + "nearby.trip.radius.km=3.0\n").getBytes(StandardCharsets.UTF_8));
        TaxiConfig.getInstance().loadFromFile(file.toString());
    }

    private ShiftSimulator simulator(TaxiConfig config, long seed) {
        return new ShiftSimulator(config, new TaxiSimulation(), null, new Random(seed), null, null);
    }

    @Test
    void displacementIsTheSmallerOfTheRoadLegAndTheRadiusDraw() {
        assertEquals(0.5 / 1.4, ShiftSimulator.nearbyDisplacementKm(0.5, 1.4, 2.5, 0.99), 1e-12);
        assertEquals(2.5 * Math.sqrt(0.25), ShiftSimulator.nearbyDisplacementKm(9.0, 1.4, 2.5, 0.25), 1e-12);
        assertEquals(0.0, ShiftSimulator.nearbyDisplacementKm(9.0, 1.4, 2.5, 0.0), 1e-12);
    }

    @Test
    void withProbabilityOneEveryPickupIsNearbyAndTheLegKeepsItsRoadKm() throws Exception {
        try {
            TaxiConfig config = load(true, 1.0);
            ShiftSimulator sim = simulator(config, 12345L);
            int n = 20_000;
            double sumKm = 0.0, maxDisp = 0.0;
            for (int i = 0; i < n; i++) {
                double[] p = sim.sampleDualModeEmptyLeg(null, LON, LAT, 12 * 3600L);
                double disp = greatCircleKm(LON, LAT, p[0], p[1]);
                double km = sim.lastCruiseKmForTest();
                assertEquals(EmptyLegPath.NEARBY_CRUISE, sim.lastPathForTest());
                assertFalse(Double.isNaN(km), "a nearby leg must carry its drawn road km");
                maxDisp = Math.max(maxDisp, disp);
                assertTrue(disp <= RADIUS + 0.01, "pickup " + disp + " km from the drop-off");
                assertTrue(disp <= km / MANHATTAN + 0.01);
                assertTrue(km >= ShiftSimulator.EMPTY_MIN_ROAD_KM && km <= ShiftSimulator.EMPTY_MAX_ROAD_KM);
                sumKm += km;
            }
            // the recorded empty km are the drawn road km, so their mean stays m_E (it must not shrink to ~1.5)
            assertEquals(M_E, sumKm / n, 0.15);
            assertTrue(maxDisp > 0.9 * RADIUS, "the radius draw should reach out near R");
            assertEquals(n, sim.getEmptyLegStats().nearbyLegs());
        } finally {
            restoreDefaults();
        }
    }

    @Test
    void withTheFeatureOffOrProbabilityZeroTheLegMovesTheWholeRoadKmAsBefore() throws Exception {
        try {
            for (boolean off : new boolean[] {true, false}) {
                TaxiConfig config = off ? load(false, 1.0) : load(true, 0.0);
                ShiftSimulator sim = simulator(config, 777L);
                Random replay = new Random(777L);
                double mu = ShiftSimulator.emptyLegMu(config);
                for (int i = 0; i < 2_000; i++) {
                    double[] p = sim.sampleDualModeEmptyLeg(null, LON, LAT, 12 * 3600L);
                    double roadKm = TruncatedLognormal.draw(replay, mu, SIGMA,
                        ShiftSimulator.EMPTY_MIN_ROAD_KM, ShiftSimulator.EMPTY_MAX_ROAD_KM);
                    if (!off) replay.nextDouble();   // the (lost) coin of probability 0
                    replay.nextDouble();             // bearing
                    assertEquals(roadKm / MANHATTAN, greatCircleKm(LON, LAT, p[0], p[1]), 0.02);
                    assertEquals(EmptyLegPath.FIRST_DRAW, sim.lastPathForTest());
                    assertTrue(Double.isNaN(sim.lastCruiseKmForTest()));
                }
                assertEquals(0, sim.getEmptyLegStats().count(EmptyLegPath.NEARBY_CRUISE));
            }
        } finally {
            restoreDefaults();
        }
    }

    @Test
    void aMixOfNearbyAndFarLegsFollowsTheProbability() throws Exception {
        try {
            ShiftSimulator sim = simulator(load(true, 0.8), 42L);
            int n = 10_000, nearby = 0;
            for (int i = 0; i < n; i++) {
                sim.sampleDualModeEmptyLeg(null, LON, LAT, 12 * 3600L);
                boolean isNearby = sim.lastPathForTest() == EmptyLegPath.NEARBY_CRUISE;
                if (isNearby) nearby++;
                assertEquals(isNearby, !Double.isNaN(sim.lastCruiseKmForTest()));
            }
            assertEquals(0.8, nearby / (double) n, 0.02);
        } finally {
            restoreDefaults();
        }
    }

    @Test
    void anEmptyTripCanCarryItsCruiseDistance() {
        TaxiTrip t = TaxiTrip.createEmptyTrip(1, LON, LAT, LON + 0.01, LAT, 0);
        assertEquals(TaxiTrip.distanceKm(LON, LAT, LON + 0.01, LAT), t.getDistanceKm(), 1e-12);
        t.setDistanceKm(5.1);
        assertEquals(5.1, t.getDistanceKm(), 0.0);
    }
}
