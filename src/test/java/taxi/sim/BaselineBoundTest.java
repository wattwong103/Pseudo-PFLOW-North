package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import taxi.sim.TaxiValidationEngine.Bound;
import taxi.sim.TaxiValidationEngine.ValidationResult;

/** Baseline rows carry an optional tier and bound (columns 9-10); the bound grades validation.csv. */
class BaselineBoundTest {

    static final String HEADER = "metric_name,category,survey_value,unit,tolerance_pct,source,notes,priority,tier,bound";

    @TempDir
    Path dir;

    /** The shipped baselines; JUnit runs from the checkout root. */
    static Path validationDir() {
        Path d = Paths.get(System.getProperty("user.dir"), "config", "taxi", "validation");
        assertTrue(Files.isDirectory(d), "run JUnit from the checkout root; not found: " + d);
        return d;
    }

    private TaxiValidationEngine load(String... rows) throws IOException {
        Path file = dir.resolve("baseline.csv");
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        for (String r : rows) lines.add(r);
        Files.write(file, lines, StandardCharsets.UTF_8);
        TaxiValidationEngine engine = new TaxiValidationEngine();
        engine.loadBaseline(file.toString());
        return engine;
    }

    // ── ValidationResult honours the bound ──

    @Test
    void atLeastPassesAtEqualityAndFailsJustBelow() {
        assertTrue(new ValidationResult("m", "c", 99.0, 99.0, 0, Bound.AT_LEAST).passed);
        assertTrue(new ValidationResult("m", "c", 100.0, 99.0, 0, Bound.AT_LEAST).passed);
        assertFalse(new ValidationResult("m", "c", 98.999, 99.0, 0, Bound.AT_LEAST).passed);
    }

    @Test
    void atMostPassesAtEqualityAndFailsJustAbove() {
        assertTrue(new ValidationResult("m", "c", 0.5, 0.5, 0, Bound.AT_MOST).passed);
        assertTrue(new ValidationResult("m", "c", 0.0, 0.5, 0, Bound.AT_MOST).passed);
        assertFalse(new ValidationResult("m", "c", 0.501, 0.5, 0, Bound.AT_MOST).passed);
    }

    @Test
    void twoSidedIsUnchangedAndIsTheDefault() {
        assertTrue(new ValidationResult("m", "c", 110.0, 100.0, 0.10).passed);
        assertFalse(new ValidationResult("m", "c", 110.01, 100.0, 0.10).passed);
        assertFalse(new ValidationResult("m", "c", 89.99, 100.0, 0.10).passed);
        assertTrue(new ValidationResult("m", "c", 0.0, 0.0, 0.10).passed);
        assertFalse(new ValidationResult("m", "c", 0.1, 0.0, 0.10).passed);
        assertEquals(Bound.TWO_SIDED, new ValidationResult("m", "c", 1, 1, 0.1).bound);
        assertEquals(new ValidationResult("m", "c", 110.01, 100.0, 0.10).passed,
            new ValidationResult("m", "c", 110.01, 100.0, 0.10, Bound.TWO_SIDED).passed);
    }

    @Test
    void aOneSidedRowHasNoErrorWhileItsBoundHolds() {
        // MorrisDriver averages error_pct over all rows: a held bound must not read as 100% error
        assertEquals(0.0, new ValidationResult("m", "c", 0.0, 0.5, 0, Bound.AT_MOST).getErrorPercent(), 1e-12);
        assertEquals(0.0, new ValidationResult("m", "c", 100.0, 99.0, 0, Bound.AT_LEAST).getErrorPercent(), 1e-12);
        assertEquals(40.0, new ValidationResult("m", "c", 0.7, 0.5, 0, Bound.AT_MOST).getErrorPercent(), 1e-9);
        assertEquals(100.0 / 99.0, new ValidationResult("m", "c", 98.0, 99.0, 0, Bound.AT_LEAST).getErrorPercent(),
            1e-9);
        assertEquals(10.0, new ValidationResult("m", "c", 110.0, 100.0, 0.15).getErrorPercent(), 1e-9);
    }

    // ── the loader reads columns 9-10 ──

    @Test
    void tierAndBoundColumnsAreRead() throws IOException {
        TaxiValidationEngine e = load(
            "Pickups Within Operating Zone,SPATIAL,99.0,percent,0,src,notes,CRITICAL,A,at_least",
            "Trip Distance Max Check,DISTANCE,15.7,km,0,src,notes,LOW,A,AT_MOST",
            "Avg Fare,REVENUE,2219,yen,25,src,notes,HIGH,B,two_sided",
            "Vacancy Time Share,SPATIAL,50,percent,10,src,notes,LOW,,");
        Map<String, TaxiValidationTiers.Target> t = e.baselineTargets();
        assertEquals(Bound.AT_LEAST, t.get("Pickups Within Operating Zone").bound);
        assertEquals("A", t.get("Pickups Within Operating Zone").tier);
        assertEquals(Bound.AT_MOST, t.get("Trip Distance Max Check").bound);
        assertEquals(Bound.TWO_SIDED, t.get("Avg Fare").bound);
        assertEquals("B", t.get("Avg Fare").tier);
        assertEquals("", t.get("Vacancy Time Share").tier);
        assertEquals(Bound.TWO_SIDED, t.get("Vacancy Time Share").bound);
    }

    @Test
    void aRepeatedMetricIsRejected() {
        // the tier bar keeps one row per metric: a lax duplicate would silently replace a strict one
        assertThrows(IllegalArgumentException.class, () -> load(
            "Avg Fare,REVENUE,2219,yen,25,src,notes,HIGH,B,two_sided",
            "Avg Fare,REVENUE,2219,yen,90,src,notes,HIGH,B,two_sided"));
    }

    @Test
    void theTierIsReadInAnyCaseLikeTheBound() throws IOException {
        TaxiValidationEngine e = load("Pickups Within Operating Zone,SPATIAL,99.0,percent,0,src,notes,CRITICAL,a,at_least");
        assertEquals("A", e.baselineTargets().get("Pickups Within Operating Zone").tier);
    }

    @Test
    void anUnknownBoundIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> load("Avg Fare,REVENUE,2219,yen,25,src,notes,HIGH,B,at-least"));
    }

    @Test
    void anUnknownTierIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> load("Avg Fare,REVENUE,2219,yen,25,src,notes,HIGH,C,two_sided"));
    }

    @Test
    void aRowWithMoreFieldsThanTheHeaderIsRejected() {
        // a comma in notes would otherwise shift priority into tier and tier into bound
        assertThrows(IllegalArgumentException.class,
            () -> load("Avg Fare,REVENUE,2219,yen,25,src,a, b,HIGH,B,two_sided"));
    }

    // ── fail closed on malformed or non-finite input (Codex review) ──

    @Test
    void aNonFiniteActualNeverPasses() {
        assertFalse(new ValidationResult("m", "c", Double.POSITIVE_INFINITY, 99.0, 0, Bound.AT_LEAST).passed);
        assertFalse(new ValidationResult("m", "c", Double.NEGATIVE_INFINITY, 0.5, 0, Bound.AT_MOST).passed);
        assertFalse(new ValidationResult("m", "c", Double.NaN, 0.5, 0, Bound.AT_MOST).passed);
        assertFalse(new ValidationResult("m", "c", Double.NaN, 10.0, 0.1).passed);
    }

    @Test
    void aNonFiniteTargetOrToleranceIsRejected() {
        // a two-sided target of Infinity would pass every finite actual (Inf <= Inf)
        assertThrows(IllegalArgumentException.class,
            () -> load("Avg Fare,REVENUE,Infinity,yen,25,src,notes,HIGH,B,two_sided"));
        assertThrows(IllegalArgumentException.class,
            () -> load("Avg Fare,REVENUE,2219,yen,NaN,src,notes,HIGH,B,two_sided"));
    }

    @Test
    void aRowWithAnUnreadableNumberIsRejectedNotSkipped() {
        // skipped, a row promoted to Tier A would fall back to its DEFS tier and leave the gate
        assertThrows(IllegalArgumentException.class,
            () -> load("Nearby Trip Ratio,SPATIAL,,percent,0,src,notes,HIGH,A,at_least"));
    }

    @Test
    void aTruncatedRowIsRejectedAndOnlyBlankLinesAreSkipped() throws IOException {
        assertThrows(IllegalArgumentException.class,
            () -> load("Avg Fare,REVENUE,2219,yen,25,src,notes"));
        TaxiValidationEngine e = load("Avg Fare,REVENUE,2219,yen,25,src,notes,HIGH,B,two_sided", "",
            "Night Trip Ratio,TEMPORAL,25.0,percent,25,src,notes,HIGH,B,two_sided");
        assertEquals(java.util.Arrays.asList("Avg Fare", "Night Trip Ratio"), e.baselineMetricNames());
    }

    @Test
    void theBoundGradesTheLegacyValidationRows() throws IOException {
        TaxiValidationEngine e = load(
            "Trip Distance Max Check,DISTANCE,15.7,km,0,src,notes,LOW,A,at_most",
            "Pickups Within Operating Zone,SPATIAL,99.0,percent,0,src,notes,CRITICAL,A,at_least");
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("Trip Distance Max Check", 15.33);       // two-sided at 0% would fail
        m.put("Pickups Within Operating Zone", 100.0); // two-sided at 0% would fail
        List<ValidationResult> results = e.compare(m, 0.0);
        assertEquals(2, results.size());
        assertTrue(results.get(0).passed);
        assertEquals(Bound.AT_MOST, results.get(0).bound);
        assertTrue(results.get(1).passed);
        m.put("Trip Distance Max Check", 15.71);
        assertFalse(e.compare(m, 0.0).get(0).passed);
    }

    // ── shipped files ──

    @Test
    void theEightColumnOsakaFileLoadsEveryRowItHasToday() throws IOException {
        Path osaka = validationDir().resolve("taxi_baseline_osaka.csv");
        List<String> lines = Files.readAllLines(osaka, StandardCharsets.UTF_8);
        assertEquals(8, lines.get(0).split(",", -1).length, "Osaka stays on the 8-column layout");
        List<String> expected = lines.stream().skip(1).filter(l -> !l.trim().isEmpty())
            .map(l -> l.split(",", -1)[0].trim()).collect(Collectors.toList());

        TaxiValidationEngine e = new TaxiValidationEngine();
        e.loadBaseline(osaka.toString());
        assertEquals(expected, e.baselineMetricNames());
        for (TaxiValidationTiers.Target t : e.baselineTargets().values()) {
            assertEquals("", t.tier);
            assertEquals(Bound.TWO_SIDED, t.bound);
        }
    }

    @Test
    void everyRowOfEveryShippedBaselineHasTheHeaderFieldCount() throws IOException {
        int files = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(validationDir(), "*.csv")) {
            for (Path f : ds) {
                files++;
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                int header = lines.get(0).split(",", -1).length;
                for (int i = 1; i < lines.size(); i++) {
                    if (lines.get(i).trim().isEmpty()) continue;
                    assertEquals(header, lines.get(i).split(",", -1).length,
                        f.getFileName() + " line " + (i + 1) + ": " + lines.get(i));
                }
            }
        }
        assertTrue(files >= 3, "expected the Tokyo, FY2023 and Osaka baselines");
    }
}
