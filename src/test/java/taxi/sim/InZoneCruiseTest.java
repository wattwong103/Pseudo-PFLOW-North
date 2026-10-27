package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WP0 Task 3: homes, cruise targets, stand hubs and pickups stay inside the operating zone. */
class InZoneCruiseTest {

    @TempDir
    Path dir;

    static DestinationZone zone(String id, double lon, double lat, boolean hub, boolean eligible) {
        DestinationZone z = new DestinationZone(id, hub ? "hub" : "mixed", id, lon, lat, 1.0,
            0.5, 0.5, 0.5, 0.5, hub);
        z.setServiceEligible(eligible);
        return z;
    }

    @Test
    void ineligibleZoneIsNeverDrawnAsCruiseTarget() {
        List<DestinationZone> zones = Arrays.asList(
            zone("A", 139.70, 35.69, false, true),
            zone("OUT", 139.30, 35.66, false, false),
            zone("B", 139.75, 35.68, false, true));
        double[] scores = ShiftSimulator.eligibleScores(zones, new double[] {2.0, 5.0, 3.0});
        assertEquals(0.0, scores[1]);
        Random random = new Random(12345);
        double total = Arrays.stream(scores).sum();
        for (int i = 0; i < 10_000; i++) {
            assertNotSame(zones.get(1), ShiftSimulator.rouletteZone(zones, scores, random.nextDouble() * total));
        }
        // r == 0 must not select a zero-score zone placed first
        List<DestinationZone> zeroFirst = Arrays.asList(zones.get(1), zones.get(0));
        assertSame(zones.get(0), ShiftSimulator.rouletteZone(zeroFirst, new double[] {0.0, 2.0}, 0.0));
    }

    @Test
    void acceptInZoneReturnsTheFirstInsideSample() {
        int[] calls = {0};
        Supplier<double[]> sampler = () -> new double[] {++calls[0], 35.0};
        BiPredicate<Double, Double> inside = (lon, lat) -> lon >= 3.0;
        ShiftSimulator.InZoneResult r = ShiftSimulator.acceptInZone(sampler, inside, new double[] {9, 9}, 20);
        assertEquals(3.0, r.lon);
        assertEquals(3, r.attempts);
        assertFalse(r.fellBack);
        assertEquals(3, calls[0]);
    }

    @Test
    void acceptInZoneFallsBackAfterExactlyMaxAttempts() {
        int[] calls = {0};
        Supplier<double[]> sampler = () -> { calls[0]++; return new double[] {0.0, 0.0}; };
        ShiftSimulator.InZoneResult r = ShiftSimulator.acceptInZone(sampler, (lon, lat) -> false,
            new double[] {139.75, 35.68}, 20);
        assertTrue(r.fellBack);
        assertEquals(20, calls[0]);
        assertEquals(20, r.attempts);
        assertArrayEquals(new double[] {139.75, 35.68}, new double[] {r.lon, r.lat});
    }

    @Test
    void nearestHubSkipsIneligibleHubs() {
        DestinationZone outsideHub = zone("ZR02", 139.83, 35.58, true, false);
        DestinationZone insideHub = zone("Z07", 139.78, 35.55, true, true);
        DestinationZone plain = zone("Z01", 139.82, 35.59, false, true);
        List<DestinationZone> zones = Arrays.asList(outsideHub, insideHub, plain);
        assertSame(insideHub, TaxiSimulation.nearestZone(zones, 139.835, 35.585, true));
    }

    @Test
    void nullTargetFallbackIsTheNearestEligibleZone() {
        DestinationZone out = zone("ZP11", 139.32, 35.66, false, false);
        DestinationZone in = zone("Z40", 139.56, 35.70, false, true);
        assertSame(in, TaxiSimulation.nearestZone(Arrays.asList(out, in), 139.33, 35.66, false));
    }

    @Test
    void zoneWithAValidCentreIsEligibleAndAnchoredAtItsCentre() {
        DestinationZone z = zone("Z01", 139.7673, 35.6809, true, true);
        boolean ok = TaxiSimulation.assignAnchor(z, (lon, lat) -> true, (lon, lat) -> true,
            (lon, lat) -> { throw new AssertionError("no projection needed"); });
        assertTrue(ok);
        assertEquals(139.7673, z.getAnchorLon());
        assertEquals(35.6809, z.getAnchorLat());
    }

    @Test
    void zoneWhoseCentreFailsValidationIsAnchoredAtTheProjectedPoint() {
        // Like Z32 Sumida: centre inside the zone but within the river buffer.
        DestinationZone z = zone("Z32", 139.8016, 35.7108, false, true);
        boolean ok = TaxiSimulation.assignAnchor(z, (lon, lat) -> true,
            (lon, lat) -> lon != 139.8016,                       // centre invalid, elsewhere valid
            (lon, lat) -> new double[] {139.8021, 35.7108});     // spiral finds a point ~45 m east
        assertTrue(ok);
        assertEquals(139.8021, z.getAnchorLon());
        assertEquals(35.7108, z.getAnchorLat());
    }

    @Test
    void zoneWhoseCentreIsOutsideTheOperatingZoneIsIneligible() {
        // Like ZR02 Tokyo Port: centre not inside any operating-zone polygon.
        DestinationZone z = zone("ZR02", 139.8338, 35.5818, true, true);
        assertFalse(TaxiSimulation.assignAnchor(z, (lon, lat) -> false, (lon, lat) -> true,
            (lon, lat) -> new double[] {lon, lat}));
    }

    @Test
    void zoneWithNoValidPointWithinTheSearchRadiusIsIneligible() {
        DestinationZone z = zone("ZX", 139.80, 35.70, false, true);
        assertFalse(TaxiSimulation.assignAnchor(z, (lon, lat) -> true, (lon, lat) -> false,
            (lon, lat) -> null));
    }

    @Test
    void projectedAnchorMustItselfLieInsideTheZone() {
        DestinationZone z = zone("ZB", 139.80, 35.70, false, true);
        assertFalse(TaxiSimulation.assignAnchor(z, (lon, lat) -> lon == 139.80, (lon, lat) -> lon != 139.80,
            (lon, lat) -> new double[] {139.81, 35.70}));
    }

    @Test
    void homeSamplingThrowsWhenNoAcceptablePointExists() {
        Random random = new Random(1);
        assertThrows(IllegalStateException.class, () ->
            TaxiSimulation.sampleHome(new double[] {139.5, 139.9, 35.5, 35.8}, (lon, lat) -> false, 200, random));
    }

    @Test
    void homeSamplingReturnsAnAcceptablePointInsideTheBounds() {
        double[] home = TaxiSimulation.sampleHome(new double[] {139.5, 139.9, 35.5, 35.8},
            (lon, lat) -> lon > 139.8, 200, new Random(1));
        assertTrue(home[0] > 139.8 && home[0] <= 139.9);
        assertTrue(home[1] >= 35.5 && home[1] <= 35.8);
    }

    @Test
    void operatingZoneWithoutSpatialValidationFailsFast() throws Exception {
        Path file = dir.resolve("taxi_config.properties");
        Files.write(file, ("spatial.validation.enabled=false\noperating.zone.adm.codes=13101\n")
            .getBytes(StandardCharsets.UTF_8));
        TaxiConfig config = TaxiConfig.getInstance();
        assertTrue(config.loadFromFile(file.toString()));
        assertThrows(IllegalStateException.class, () -> TaxiSimulation.checkOperatingZoneConfig(config));
    }
}
