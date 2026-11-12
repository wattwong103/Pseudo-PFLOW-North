package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * TASK-14: the two Tokyo priors pinned to THTA's 2014 tachograph survey, one published figure each.
 * Leg spread s_L is set so the simulated base-fare trip share matches 27.0%; the night-group share p3 so the
 * 22:00-04:59 trip share matches 23.52%, with the day and afternoon groups kept at their prior ratio 4:3.
 * Selection: 18_multiyear_protocol.md, Amendment 2.
 */
class TokyoTachographPriorsTest {

    private static final double LOADED_SIGMA = 0.88;
    private static final double NIGHT_SHARE = 0.17;

    private TaxiConfig tokyo() {
        return load("config/taxi/tokyo/taxi_config.properties");
    }

    private TaxiConfig load(String path) {
        TaxiConfig config = TaxiConfig.getInstance();
        assertTrue(config.loadFromFile(path));
        return config;
    }

    @Test
    void theScenarioBConfigCarriesTheSamePriorsAndNearbyOff() {
        TaxiConfig main = tokyo();
        double[] expected = {main.getTripDistanceLoadedSigma(), main.getShiftStart1Prob(), main.getShiftStart2Prob(),
            main.getShiftStart3Prob()};
        assertFalse(main.isUseNearbyTrips());
        TaxiConfig r8 = load("config/taxi/tokyo/taxi_config_tokyo_r8.properties");
        double[] actual = {r8.getTripDistanceLoadedSigma(), r8.getShiftStart1Prob(), r8.getShiftStart2Prob(),
            r8.getShiftStart3Prob()};
        assertArrayEquals(expected, actual, 1e-12);
        assertFalse(r8.isUseNearbyTrips());
    }

    @Test
    void theLegSpreadIsThePinnedValue() {
        assertEquals(LOADED_SIGMA, tokyo().getTripDistanceLoadedSigma(), 1e-12);
    }

    @Test
    void theNightGroupShareIsThePinnedValueAndStartsAt22() {
        TaxiConfig c = tokyo();
        assertEquals(NIGHT_SHARE, c.getShiftStart3Prob(), 1e-12);
        assertEquals(22 * 3600L, c.getShiftStart3Time());
    }

    @Test
    void theShiftSharesSumToOneAndKeepTheDayToAfternoonRatio() {
        TaxiConfig c = tokyo();
        assertEquals(1.0, c.getShiftStart1Prob() + c.getShiftStart2Prob() + c.getShiftStart3Prob(), 1e-9);
        // rounded to three decimals, so the 4:3 ratio holds to within the rounding
        assertEquals(4.0 / 3.0, c.getShiftStart1Prob() / c.getShiftStart2Prob(), 0.01);
    }
}
