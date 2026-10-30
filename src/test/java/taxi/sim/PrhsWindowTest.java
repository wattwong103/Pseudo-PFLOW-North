package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import taxi.sim.PrhsWindow.Day;

/**
 * The Tokyo PRHS windows are MLIT's published hour blocks. MLIT writes them inclusive, "7時台～10時台" =
 * 07:00-10:59 (press release 2024-03-13, attachment 001730147.pdf p.2; Tokyo Transport Branch 2026-02-27,
 * to_t260227.pdf p.1). {@link PrhsWindow} is [start, end), so a config end is the hour after the last block.
 */
class PrhsWindowTest {

    /** FY2024, 特別区・武三: {first day, last day, first hour block, last hour block}, all inclusive. */
    static final int[][] FY2024 = {
        {Day.MON.ordinal(), Day.FRI.ordinal(), 7, 10},
        {Day.FRI.ordinal(), Day.SAT.ordinal(), 16, 19},
        {Day.SAT.ordinal(), Day.SAT.ordinal(), 0, 4},
        {Day.SUN.ordinal(), Day.SUN.ordinal(), 10, 13},
    };

    /** R8 (2026-02-26), 特別区・武三: Mon-Fri 7時台～10時台 only. */
    static final int[][] R8 = {
        {Day.MON.ordinal(), Day.FRI.ordinal(), 7, 10},
    };

    static TaxiConfig tokyoConfig(String file) {
        TaxiConfig c = TaxiConfig.getInstance();
        Path p = BaselineBoundTest.validationDir().getParent().resolve("tokyo").resolve(file);
        assertTrue(c.loadFromFile(p.toString()));
        return c;
    }

    static boolean published(int[][] blocks, Day day, int hour) {
        for (int[] b : blocks) {
            if (day.ordinal() >= b[0] && day.ordinal() <= b[1] && hour >= b[2] && hour <= b[3]) {
                return true;
            }
        }
        return false;
    }

    static boolean open(List<PrhsWindow> windows, long t) {
        return windows.stream().anyMatch(w -> w.contains(t));
    }

    /** Every hour block of every day is open, at its first and its last second, exactly when MLIT lists it. */
    static void assertHourBlocks(String file, int[][] blocks) {
        TaxiConfig c = tokyoConfig(file);
        for (Day day : Day.values()) {
            List<PrhsWindow> windows = PrhsWindow.parseAndFilter(c.getPrhsWindowSpecs(), day);
            for (int h = 0; h < 24; h++) {
                boolean expected = published(blocks, day, h);
                assertEquals(expected, open(windows, h * 3600L), file + " " + day + " " + h + ":00:00");
                assertEquals(expected, open(windows, h * 3600L + 3599), file + " " + day + " " + h + ":59:59");
            }
        }
    }

    @Test
    void aPickupAt1030OnMondayIsInsideTheMorningWindow() {
        List<PrhsWindow> mon = PrhsWindow.parseAndFilter(tokyoConfig("taxi_config.properties").getPrhsWindowSpecs(), Day.MON);
        long t = 10 * 3600 + 30 * 60;
        PrhsWindow w = PrhsWindow.findCurrentOrNext(mon, t);
        assertNotNull(w, "no PRHS window at or after 10:30 on Monday");
        assertTrue(w.contains(t), "10:30 Monday falls outside " + w);
    }

    @Test
    void theFy2024WindowsAreMlitsHourBlocks() {
        assertHourBlocks("taxi_config.properties", FY2024);
    }

    @Test
    void theR8WindowIsMlitsWeekdayMorningBlock() {
        try {
            assertHourBlocks("taxi_config_tokyo_r8.properties", R8);
        } finally {
            // leave the shared singleton on the default Tokyo windows for the tests that follow
            tokyoConfig("taxi_config.properties");
        }
    }

    @Test
    void aWindowEndIsExclusive() {
        PrhsWindow w = PrhsWindow.parse("07:00-11:00,Mon-Fri");
        assertTrue(w.contains(7 * 3600L));
        assertTrue(w.contains(11 * 3600L - 1));
        assertFalse(w.contains(11 * 3600L));
    }
}
