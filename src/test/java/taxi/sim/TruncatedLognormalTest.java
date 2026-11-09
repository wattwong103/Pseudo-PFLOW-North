package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bounded-mean closed form for the leg samplers (TASK-12). Both samplers re-draw log-normal distances that fall
 * outside their bounds, so the location must be solved for the mean of the truncated distribution, not set by
 * mu = ln(m) - s^2/2 (which only holds without bounds).
 */
class TruncatedLognormalTest {

    @TempDir
    Path dir;

    /** The shared TaxiConfig is a singleton: put the leg keys back to their defaults after each test. */
    @AfterEach
    void restoreLegDefaults() throws Exception {
        Path props = dir.resolve("defaults.properties");
        Files.write(props, ("trip.distance.average=5.0\ntrip.distance.loaded.sigma=0.6\ntrip.distance.min=0.5\n"
            + "trip.distance.max=15.0\ntrip.distance.empty.mean.km=5.1\ntrip.distance.empty.sigma=0.7\n")
            .getBytes(StandardCharsets.UTF_8));
        TaxiConfig.getInstance().loadFromFile(props.toString());
    }

    private static double unboundedMu(double mean, double sigma) {
        return Math.log(mean) - 0.5 * sigma * sigma;
    }

    @Test
    void boundedMeanReducesToTheLognormalMeanForWideBounds() {
        assertEquals(4.6, TruncatedLognormal.boundedMean(unboundedMu(4.6, 0.6), 0.6, 1e-6, 1e6), 4.6e-9);
        assertEquals(5.1, TruncatedLognormal.boundedMean(unboundedMu(5.1, 0.7), 0.7, 1e-6, 1e6), 5.1e-9);
    }

    @Test
    void boundedMeanShowsTheShortfallOfTheUnboundedForm() {
        // Analytic truncated means (numpy quadrature, 2026-11-08): 4.434 loaded, 4.377 empty.
        assertEquals(4.434, TruncatedLognormal.boundedMean(unboundedMu(4.6, 0.6), 0.6, 0.5, 15.0), 1e-3);
        assertEquals(4.377, TruncatedLognormal.boundedMean(unboundedMu(5.1, 0.7), 0.7, 0.3, 12.0), 1e-3);
    }

    @Test
    void muForBoundedMeanHitsTheTargetWithinTheBounds() {
        double muL = TruncatedLognormal.muForBoundedMean(4.6, 0.6, 0.5, 15.0);
        double muE = TruncatedLognormal.muForBoundedMean(5.1, 0.7, 0.3, 12.0);
        assertEquals(4.6, TruncatedLognormal.boundedMean(muL, 0.6, 0.5, 15.0), 4.6e-9);
        assertEquals(5.1, TruncatedLognormal.boundedMean(muE, 0.7, 0.3, 12.0), 5.1e-9);
        // The equivalent unbounded means (numpy, 2026-11-08): 4.798 and 6.464.
        assertEquals(4.798, Math.exp(muL + 0.18), 1e-3);
        assertEquals(6.464, Math.exp(muE + 0.245), 1e-3);
    }

    @Test
    void muForBoundedMeanReducesToTheUnboundedFormForWideBounds() {
        assertEquals(unboundedMu(4.6, 0.6), TruncatedLognormal.muForBoundedMean(4.6, 0.6, 1e-6, 1e6), 1e-9);
    }

    @Test
    void boundedMeanStaysFiniteInsideTheBoundsForAnyLocation() {
        for (double mu : new double[] {-50.0, 50.0}) {
            double m = TruncatedLognormal.boundedMean(mu, 0.6, 0.5, 15.0);
            assertTrue(Double.isFinite(m) && m >= 0.5 && m <= 15.0, "mu=" + mu + " gave " + m);
        }
        // A narrow log-normal well inside the bounds keeps its unbounded mean (grid scales with 1/sigma).
        assertEquals(4.6, TruncatedLognormal.boundedMean(unboundedMu(4.6, 0.01), 0.01, 0.3, 30.0), 4.6e-9);
    }

    @Test
    void muForBoundedMeanRejectsImpossibleInputs() {
        assertThrows(IllegalArgumentException.class, () -> TruncatedLognormal.muForBoundedMean(0.5, 0.6, 0.5, 15.0));
        assertThrows(IllegalArgumentException.class, () -> TruncatedLognormal.muForBoundedMean(15.0, 0.6, 0.5, 15.0));
        assertThrows(IllegalArgumentException.class, () -> TruncatedLognormal.muForBoundedMean(4.6, 0.0, 0.5, 15.0));
        assertThrows(IllegalArgumentException.class, () -> TruncatedLognormal.muForBoundedMean(4.6, 0.6, 15.0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> TruncatedLognormal.muForBoundedMean(4.6, 0.6, 0.0, 15.0));
        assertThrows(IllegalArgumentException.class, () -> TruncatedLognormal.muForBoundedMean(Double.NaN, 0.6, 0.5, 15.0));
        assertThrows(IllegalArgumentException.class, () -> TruncatedLognormal.muForBoundedMean(4.6, Double.NaN, 0.5, 15.0));
        assertThrows(IllegalArgumentException.class, () -> TruncatedLognormal.muForBoundedMean(4.6, 0.6, 0.5, Double.NaN));
    }

    @Test
    void redrawStaysInBoundsAndItsSampleMeanHitsTheTarget() {
        double[][] cases = {{4.6, 0.6, 0.5, 15.0}, {5.1, 0.7, 0.3, 12.0}};
        for (double[] c : cases) {
            double mu = TruncatedLognormal.muForBoundedMean(c[0], c[1], c[2], c[3]);
            Random random = new Random(12345);
            int n = 400_000;
            double sum = 0.0;
            for (int i = 0; i < n; i++) {
                double x = TruncatedLognormal.draw(random, mu, c[1], c[2], c[3]);
                assertTrue(x >= c[2] && x <= c[3], "draw out of bounds: " + x);
                sum += x;
            }
            assertEquals(c[0], sum / n, 0.005 * c[0], "sample mean for target " + c[0]);
        }
    }

    @Test
    void bothSamplersUseTheBoundedLocation() throws Exception {
        Path props = dir.resolve("taxi_config.properties");
        Files.write(props, ("city.name=Tokyo\ntaxi.fleet.size=4\ntrip.distance.average=4.6\n"
            + "trip.distance.loaded.sigma=0.6\ntrip.distance.min=0.5\ntrip.distance.max=15.0\n"
            + "trip.distance.empty.mean.km=5.1\ntrip.distance.empty.sigma=0.7\n").getBytes(StandardCharsets.UTF_8));
        TaxiConfig config = TaxiConfig.getInstance();
        assertTrue(config.loadFromFile(props.toString()));
        assertEquals(TruncatedLognormal.muForBoundedMean(4.6, 0.6, 0.5, 15.0), TaxiSimulation.loadedLegMu(config), 1e-12);
        assertEquals(TruncatedLognormal.muForBoundedMean(5.1, 0.7, ShiftSimulator.EMPTY_MIN_ROAD_KM,
            ShiftSimulator.EMPTY_MAX_ROAD_KM), ShiftSimulator.emptyLegMu(config), 1e-12);
    }
}
