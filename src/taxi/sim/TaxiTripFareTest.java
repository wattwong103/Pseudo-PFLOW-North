package taxi.sim;

/**
 * Standalone golden-value test for {@link TaxiTrip#calculateFare(double, double, boolean)}.
 *
 * <p><b>Why this exists.</b> Refactoring-plan #15 tracked a tariff drift: the codebase
 * documented <i>two</i> Tokyo fare schemes — the canonical ¥500 / first&nbsp;1.096&nbsp;km /
 * ¥392&nbsp;per&nbsp;km (verified against 全ハイ連 Taxi Today 2025, effective 2022-11-14) and a
 * legacy ¥730 / 2.0&nbsp;km / ¥320 "alternative" still quoted in a few doc comments. This test
 * pins the canonical tariff so any future edit to
 * {@code config/taxi/tokyo/taxi_config.properties} or to the fare formula that reintroduces
 * the drift fails loudly.
 *
 * <p><b>Not a JUnit test.</b> JUnit is excluded from this project's Maven build
 * (see {@code .claude/rules/config-files.md}); the project compiles via {@code javac} and runs
 * validation through {@code main()}-based tools (cf. {@link StandaloneValidator}). Run from the
 * {@code Pseudo-PFLOW/} directory so the relative config path resolves:
 * <pre>
 *   java -cp "bin;&lt;deps&gt;" taxi.sim.TaxiTripFareTest
 * </pre>
 * Exit code 0 = all checks passed, 1 = drift detected.
 *
 * <p><b>Golden fare points</b> (canonical Tokyo tariff; fares round to the nearest ¥10):
 * <pre>
 *   distance   period    expected   derivation
 *   1 km       day        ¥500      within base distance (1.0 &le; 1.096 km) -&gt; base only
 *   2 km       day        ¥850      500 + (2.000-1.096)*392 = 854.37 -&gt; ¥850
 *   5 km       day       ¥2030      500 + (5.000-1.096)*392 = 2030.37 -&gt; ¥2030
 *   10 km      day       ¥3990      500 + (10.00-1.096)*392 = 3990.37 -&gt; ¥3990
 *   5 km       night     ¥2440      2030.37 * 1.20 = 2436.44 -&gt; ¥2440
 * </pre>
 */
public final class TaxiTripFareTest {

    private static int failures = 0;

    public static void main(String[] args) {
        // Load the REAL Tokyo config so the test validates the production tariff, not just
        // in-memory defaults. Overridable via arg for other cities / scenario configs.
        String cfg = args.length > 0 ? args[0] : "config/taxi/tokyo/taxi_config.properties";
        boolean loaded = TaxiConfig.getInstance().loadFromFile(cfg);
        check("config loaded: " + cfg, loaded);

        TaxiConfig c = TaxiConfig.getInstance();

        // --- Layer 1: tariff pin — canonical Tokyo values (catches config drift, incl. #15) ---
        checkEq("fare.base",            500.0, c.getTaxiFareBase(),           1e-9);
        checkEq("fare.base.distance",   1.096, c.getTaxiFareBaseDistance(),   1e-9);
        checkEq("fare.per.km",          392.0, c.getTaxiFarePerKm(),          1e-9);
        checkEq("fare.night.surcharge", 1.20,  c.getTaxiFareNightSurcharge(), 1e-9);

        // --- Layer 2: golden fare points — calculateFare() output (catches formula drift) ---
        checkEq("1 km  day",    500.0, TaxiTrip.calculateFare(1.0,  0.0, false), 0.5);
        checkEq("2 km  day",    850.0, TaxiTrip.calculateFare(2.0,  0.0, false), 0.5);
        checkEq("5 km  day",   2030.0, TaxiTrip.calculateFare(5.0,  0.0, false), 0.5);
        checkEq("10 km day",   3990.0, TaxiTrip.calculateFare(10.0, 0.0, false), 0.5);
        checkEq("5 km  night", 2440.0, TaxiTrip.calculateFare(5.0,  0.0, true),  0.5);

        System.out.println();
        if (failures == 0) {
            System.out.println("PASS - TaxiTrip.calculateFare: 9/9 checks green (Tokyo tariff pinned).");
            System.exit(0);
        } else {
            System.out.println("FAIL - " + failures + " check(s) failed: tariff or formula drift detected.");
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        System.out.printf("  [%s] %s%n", ok ? "PASS" : "FAIL", name);
        if (!ok) failures++;
    }

    private static void checkEq(String name, double expected, double actual, double tol) {
        boolean ok = Math.abs(expected - actual) <= tol;
        System.out.printf("  [%s] %-20s expected=%10.4f  actual=%10.4f%n",
            ok ? "PASS" : "FAIL", name, expected, actual);
        if (!ok) failures++;
    }
}
