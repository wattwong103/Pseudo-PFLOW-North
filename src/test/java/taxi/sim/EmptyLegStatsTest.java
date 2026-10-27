package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Per-path decomposition of empty legs (calibration-guardian condition, WP0). */
class EmptyLegStatsTest {

    @Test
    void pathTotalsReconcileWithEveryRecordedLeg() {
        EmptyLegStats s = new EmptyLegStats();
        s.record(EmptyLegPath.FIRST_DRAW, 1.0, true);
        s.record(EmptyLegPath.FIRST_DRAW, 3.0, false);
        s.record(EmptyLegPath.STAND, 2.0, true);
        assertEquals(3, s.totalCount());
        assertEquals(6.0, s.totalKm(), 1e-12);
        assertEquals(2, s.count(EmptyLegPath.FIRST_DRAW));
        assertEquals(2.0, s.meanKm(EmptyLegPath.FIRST_DRAW), 1e-12);
        assertEquals(3.0, s.maxKm(EmptyLegPath.FIRST_DRAW), 1e-12);
        assertEquals(0, s.count(EmptyLegPath.CENTROID_DEADHEAD));
    }

    @Test
    void percentilesAreNearestRankOverThePath() {
        EmptyLegStats s = new EmptyLegStats();
        for (int km = 1; km <= 10; km++) s.record(EmptyLegPath.FIRST_DRAW, km, true);
        assertEquals(5.0, s.percentileKm(EmptyLegPath.FIRST_DRAW, 50), 1e-12);
        assertEquals(9.0, s.percentileKm(EmptyLegPath.FIRST_DRAW, 90), 1e-12);
    }

    @Test
    void startInsideSplitsCounts() {
        EmptyLegStats s = new EmptyLegStats();
        s.record(EmptyLegPath.RESAMPLED, 2.0, true);
        s.record(EmptyLegPath.RESAMPLED, 4.0, false);
        s.record(EmptyLegPath.RESAMPLED, 6.0, false);
        assertEquals(1, s.count(EmptyLegPath.RESAMPLED, true));
        assertEquals(2, s.count(EmptyLegPath.RESAMPLED, false));
        assertEquals(5.0, s.meanKm(EmptyLegPath.RESAMPLED, false), 1e-12);
    }

    @Test
    void attemptsHistogramClampsAtTwenty() {
        EmptyLegStats s = new EmptyLegStats();
        s.recordAttempts(1);
        s.recordAttempts(3);
        s.recordAttempts(3);
        s.recordAttempts(45);
        assertEquals(1, s.attempts(1));
        assertEquals(2, s.attempts(3));
        assertEquals(1, s.attempts(20));
    }

    @Test
    void orphansAndUnrecordedLegsAreTalliedApartFromPaths() {
        EmptyLegStats s = new EmptyLegStats();
        s.record(EmptyLegPath.FIRST_DRAW, 2.5, true);
        s.markOrphan(EmptyLegPath.FIRST_DRAW, 2.5, false);
        s.recordUnrecorded(EmptyLegPath.STAY_PUT, 0.0);
        assertEquals(1, s.totalCount());
        assertEquals(2.5, s.totalKm(), 1e-12);
        assertEquals(1, s.orphanCount());
        assertEquals(2.5, s.orphanKm(), 1e-12);
        assertEquals(1, s.unrecordedCount());
        assertEquals(1, s.unrecordedCount(EmptyLegPath.STAY_PUT));
    }

    @Test
    void orphansSplitByPathAndByEndOfShift() {
        EmptyLegStats s = new EmptyLegStats();
        s.markOrphan(EmptyLegPath.STAND, 7.0, true);
        s.markOrphan(EmptyLegPath.FIRST_DRAW, 3.0, true);
        s.markOrphan(EmptyLegPath.FIRST_DRAW, 2.0, false);
        assertEquals(3, s.orphanCount());
        assertEquals(2, s.orphanCount(true));
        assertEquals(1, s.orphanCount(false));
        assertEquals(2, s.orphanCount(EmptyLegPath.FIRST_DRAW));
        assertEquals(5.0, s.orphanKm(EmptyLegPath.FIRST_DRAW), 1e-12);
    }

    @Test
    void impliedPassengerLegsReconcileRecordedPlusUnrecordedMinusOrphans() {
        EmptyLegStats s = new EmptyLegStats();
        for (int i = 0; i < 5; i++) s.record(EmptyLegPath.FIRST_DRAW, 1.0, true);
        s.recordUnrecorded(EmptyLegPath.STAND, 0.0);
        s.markOrphan(EmptyLegPath.FIRST_DRAW, 1.0, true);
        s.markOrphan(EmptyLegPath.STAND, 0.0, false);
        assertEquals(4, s.impliedPassengerLegs());
    }

    @Test
    void legsOverTwelveKmAreCountedPerPath() {
        EmptyLegStats s = new EmptyLegStats();
        s.record(EmptyLegPath.STAND, 11.9, true);
        s.record(EmptyLegPath.STAND, 12.5, true);
        s.record(EmptyLegPath.STAND, 78.5, false);
        assertEquals(2, s.countOver(EmptyLegPath.STAND, 12.0));
    }

    @Test
    void ksStatisticIsSmallForTruncatedLognormalQuantilesAndLargeForAShift() {
        double mu = Math.log(5.1) - 0.5 * 0.7 * 0.7;
        EmptyLegStats exact = new EmptyLegStats();
        EmptyLegStats shifted = new EmptyLegStats();
        int n = 2000;
        for (int i = 1; i <= n; i++) {
            double q = EmptyLegStats.truncatedLognormalQuantile((i - 0.5) / n, mu, 0.7, 0.3, 12.0);
            exact.record(EmptyLegPath.FIRST_DRAW, q, true);
            shifted.record(EmptyLegPath.FIRST_DRAW, q * 1.3, true);
        }
        assertEquals(0.0, exact.ksFirstDraw(mu, 0.7, 0.3, 12.0), 0.002);
        org.junit.jupiter.api.Assertions.assertTrue(shifted.ksFirstDraw(mu, 0.7, 0.3, 12.0) > 0.1);
    }
}
