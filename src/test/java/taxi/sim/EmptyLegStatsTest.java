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
        s.markOrphan(2.5);
        s.recordUnrecorded(0.03);
        assertEquals(1, s.totalCount());
        assertEquals(2.5, s.totalKm(), 1e-12);
        assertEquals(1, s.orphanCount());
        assertEquals(2.5, s.orphanKm(), 1e-12);
        assertEquals(1, s.unrecordedCount());
    }
}
