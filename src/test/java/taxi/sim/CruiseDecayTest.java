package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Distance decay in the cruise-target draw (TASK-8, ruling P8): each zone's score is multiplied by
 * exp(-d/lambda), d = road km from the taxi to the zone anchor; lambda <= 0 leaves scores untouched.
 */
class CruiseDecayTest {

    @TempDir
    Path dir;

    static DestinationZone zone(String id, double lon, double lat) {
        return new DestinationZone(id, "mixed", id, lon, lat, 1.0, 0.5, 0.5, 0.5, 0.5, false);
    }

    static final double LON = 139.70, LAT = 35.69;
    static final List<DestinationZone> ZONES = List.of(
        zone("NEAR", LON + 0.011, LAT),   // ~1 km east
        zone("FAR", LON + 0.055, LAT));   // ~5 km east

    @Test
    void decayOffLeavesScoresUntouched() {
        double[] raw = {2.0, 2.0};
        assertArrayEquals(raw, ShiftSimulator.distanceDecayed(ZONES, raw, LON, LAT, 0.0));
        assertArrayEquals(raw, ShiftSimulator.distanceDecayed(ZONES, raw, LON, LAT, -1.0));
    }

    @Test
    void theNearerOfTwoEqualZonesScoresHigherByTheDecayFactor() {
        double lambda = 5.1;
        double[] s = ShiftSimulator.distanceDecayed(ZONES, new double[] {2.0, 2.0}, LON, LAT, lambda);
        double dNear = TaxiTrip.distanceKm(LON, LAT, ZONES.get(0).getAnchorLon(), ZONES.get(0).getAnchorLat());
        double dFar = TaxiTrip.distanceKm(LON, LAT, ZONES.get(1).getAnchorLon(), ZONES.get(1).getAnchorLat());
        assertTrue(s[0] > s[1]);
        assertEquals(Math.exp(-(dNear - dFar) / lambda), s[0] / s[1], 1e-12);
    }

    @Test
    void theKeyDefaultsToOffAndReadsItsValue() throws Exception {
        Path file = dir.resolve("taxi_config.properties");
        TaxiConfig config = TaxiConfig.getInstance();
        Files.write(file, "city.name=Tokyo\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(config.loadFromFile(file.toString()));
        assertEquals(0.0, config.getCruiseDistanceDecayKm());
        Files.write(file, "city.name=Tokyo\ncruise.distance.decay.km=5.1\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(config.loadFromFile(file.toString()));
        assertEquals(5.1, config.getCruiseDistanceDecayKm());
        // leave the shared singleton with the decay off for the tests that follow
        Files.write(file, "city.name=Tokyo\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(config.loadFromFile(file.toString()));
    }
}
