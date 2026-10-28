package taxi.sim;

import dcity.aggr.Boundaries;
import dcity.aggr.ShpLoader;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.opengis.feature.simple.SimpleFeature;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Geographic validation engine for the taxi simulation.
 *
 * <p>Provides shapefile-based spatial validation to ensure that trip pickups,
 * dropoffs, and taxi home locations fall on valid driveable land. Uses a
 * simplified 3-layer approach (vs truck's 6 layers) because taxis operate
 * exclusively in urban areas where zone-based selection already provides
 * urban context.
 *
 * <p>Validation layers:
 * <ul>
 *   <li>Administrative land boundary polygons (polbnda_jpn.shp) — required</li>
 *   <li>Inland water body exclusion (inwatera_jpn.shp) — optional</li>
 *   <li>River centerline buffer exclusion (riverl_jpn.shp) — optional</li>
 * </ul>
 *
 * <p>All optional layers degrade gracefully — if a shapefile is missing, the
 * corresponding validation is skipped rather than failing the simulation.
 *
 * <p>Reuses {@link dcity.aggr.Boundaries} (shared STRtree spatial index
 * infrastructure) without modifying any existing code.
 *
 * @version 1.0
 */
public class TaxiGeoValidator {

    // ── Shapefile-based spatial layers ──────────────────────────────────────
    private Boundaries japanBoundaries;
    private Boundaries waterBodies;
    private Boundaries riverLines;
    private final GeometryFactory geometryFactory;

    // ── Configuration ──────────────────────────────────────────────────────
    private final String shapefileDir;
    private double riverBufferKm = 0.15;

    // ── Cache for land validation (round to ~100m resolution) ──────────────
    private final Map<String, Boolean> landValidationCache = new HashMap<>();

    // ── City boundary (prefecture-filtered polygons) ───────────────────────
    private STRtree cityBoundaryIndex;
    private Envelope cityEnvelope;
    private boolean cityBoundaryEnabled = false;
    private final Map<String, Boolean> cityValidationCache = new HashMap<>();

    // ── Operating zone (exact adm_code match; optional, per city) ──────────
    // Not cached: a ~100 m cache key (as in isWithinCity) would merge points on
    // both sides of a ward border, and this check decides pickup legality.
    private STRtree operatingZoneIndex;
    private Envelope operatingZoneEnvelope;
    private boolean operatingZoneEnabled = false;
    private Set<String> operatingZoneCodes = Collections.emptySet();
    private int operatingZonePolygonCount = 0;

    // ── Non-road exclusion areas (large parks, gardens, cemeteries, palace grounds; optional) ──
    // Not cached, for the same reason as the operating zone: park edges are where the gates are.
    private STRtree exclusionIndex;
    private boolean exclusionEnabled = false;
    private int exclusionPolygonCount = 0;

    // ── Statistics ─────────────────────────────────────────────────────────
    private long totalChecks = 0;
    private long cacheHits = 0;
    private long rejectedOutOfCity = 0;
    private long rejectedWater = 0;
    private long rejectedRiver = 0;
    private long rejectedExcluded = 0;

    /**
     * Creates a new TaxiGeoValidator with the specified shapefile directory.
     *
     * @param shapefileDir Directory containing Japan-wide shapefiles (e.g. "src/shared/gm-jp/")
     */
    public TaxiGeoValidator(String shapefileDir) {
        this.shapefileDir = shapefileDir.endsWith("/") ? shapefileDir : shapefileDir + "/";
        this.geometryFactory = new GeometryFactory();
    }

    /**
     * Sets the river buffer exclusion distance.
     * Default is 0.15 km (150m), smaller than truck's 0.3 km because taxis
     * operate on urban roads that run alongside rivers.
     *
     * @param bufferKm River buffer distance in kilometers
     */
    public void setRiverBufferKm(double bufferKm) {
        this.riverBufferKm = bufferKm;
    }

    // ════════════════════════════════════════════════════════════════════════
    // DATA LOADING
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Loads Japan land boundary polygons (polbnda_jpn.shp).
     * This is the primary spatial layer — required for the validator to function.
     *
     * @throws RuntimeException if shapefile cannot be loaded
     */
    public void loadJapanBoundaries() {
        String path = shapefileDir + "polbnda_jpn.shp";
        System.out.println("[SPATIAL] Loading Japan land boundaries from: " + path);

        try {
            japanBoundaries = Boundaries.create(path);
            System.out.println("[SPATIAL] Loaded " + japanBoundaries.size() +
                " land boundary polygons — spatial index ready");
        } catch (Exception e) {
            System.err.println("[SPATIAL] ERROR: Failed to load Japan boundaries: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Cannot run spatial validation without land boundaries", e);
        }
    }

    /**
     * Loads inland water body polygons for exclusion from land validation.
     * polbnda_jpn.shp treats Lake Biwa as inside Shiga prefecture —
     * inwatera_jpn.shp provides explicit water polygon exclusion.
     * Non-fatal: if file missing, isOnLand() falls back to boundary-only validation.
     */
    public void loadWaterBodies() {
        String path = shapefileDir + "inwatera_jpn.shp";
        try {
            java.io.File shapefile = new java.io.File(path);
            if (!shapefile.exists()) {
                System.err.println("[SPATIAL] Water bodies file not found: " + path +
                    " — inland water exclusion disabled");
                return;
            }
            waterBodies = Boundaries.create(path);
            System.out.println("[SPATIAL] Loaded " + waterBodies.size() +
                " inland water body polygons (lakes, reservoirs)");
        } catch (Exception e) {
            System.err.println("[SPATIAL] Warning: Failed to load water bodies: " + e.getMessage() +
                " — inland water exclusion disabled");
            waterBodies = null;
        }
    }

    /**
     * Loads river line geometries for river channel exclusion buffering.
     * Non-fatal: if file missing, river buffer exclusion is disabled.
     */
    public void loadRiverLines() {
        String path = shapefileDir + "riverl_jpn.shp";
        try {
            java.io.File shapefile = new java.io.File(path);
            if (!shapefile.exists()) {
                System.err.println("[SPATIAL] River lines file not found: " + path +
                    " — river buffer exclusion disabled");
                return;
            }
            riverLines = Boundaries.create(path);
            System.out.println("[SPATIAL] Loaded " + riverLines.size() +
                " river line segments (buffer: " + riverBufferKm + " km)");
        } catch (Exception e) {
            System.err.println("[SPATIAL] Warning: Failed to load river lines: " + e.getMessage() +
                " — river buffer exclusion disabled");
            riverLines = null;
        }
    }

    /**
     * Loads city boundary polygons by filtering polbnda_jpn.shp features
     * whose adm_code starts with one of the specified prefecture codes.
     * Builds a dedicated STRtree for isWithinCity() checks and computes
     * the bounding envelope for random point sampling.
     *
     * <p>A focus area filter (expanded config bounds) is used to exclude remote
     * island municipalities. This is critical for Tokyo-to (pref 13) which includes
     * the Ogasawara/Izu island chains at 20-33°N, 139-154°E — the unfiltered
     * envelope would be ~18°×17° of mostly empty Pacific Ocean.
     *
     * @param prefectureCodes Comma-separated prefecture codes (e.g. "13,14"), or empty to skip
     * @param configBounds Config rectangle as {minLon, maxLon, minLat, maxLat}, used to filter remote islands
     */
    public void loadCityBoundary(String prefectureCodes, double[] configBounds) {
        if (prefectureCodes == null || prefectureCodes.trim().isEmpty()) {
            System.out.println("[SPATIAL] No prefecture codes — city boundary disabled (using config bounds)");
            return;
        }

        Set<String> prefixes = Arrays.stream(prefectureCodes.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());

        if (prefixes.isEmpty()) return;

        String path = shapefileDir + "polbnda_jpn.shp";
        System.out.println("[SPATIAL] Loading city boundary for prefectures: " + prefixes);

        // Build a generous focus area from config bounds (expand by 100%)
        // to filter remote islands while keeping all metro-area municipalities
        Envelope focusArea = null;
        if (configBounds != null && configBounds.length == 4) {
            double lonSpan = configBounds[1] - configBounds[0];
            double latSpan = configBounds[3] - configBounds[2];
            focusArea = new Envelope(
                configBounds[0] - lonSpan,  // minX expanded
                configBounds[1] + lonSpan,  // maxX expanded
                configBounds[2] - latSpan,  // minY expanded
                configBounds[3] + latSpan   // maxY expanded
            );
        }

        try {
            List<SimpleFeature> features = ShpLoader.load(path);
            cityBoundaryIndex = new STRtree();
            cityEnvelope = new Envelope();
            int matchCount = 0;
            int filteredCount = 0;

            for (SimpleFeature feature : features) {
                Object admCodeObj = feature.getAttribute("adm_code");
                if (admCodeObj == null) continue;
                String admCode = admCodeObj.toString().trim();
                if (admCode.length() < 2) continue;

                String prefixTwo = admCode.substring(0, 2);
                if (prefixes.contains(prefixTwo)) {
                    Geometry geom = (Geometry) feature.getDefaultGeometry();
                    if (geom != null) {
                        Envelope env = geom.getEnvelopeInternal();
                        // Filter: only include polygons that intersect the focus area
                        if (focusArea != null && !focusArea.intersects(env)) {
                            filteredCount++;
                            continue;
                        }
                        cityBoundaryIndex.insert(env, geom);
                        cityEnvelope.expandToInclude(env);
                        matchCount++;
                    }
                }
            }

            cityBoundaryIndex.build();
            cityBoundaryEnabled = true;

            System.out.println("[SPATIAL] City boundary: " + matchCount +
                " municipality polygons from " + prefixes.size() + " prefectures" +
                (filteredCount > 0 ? " (" + filteredCount + " remote islands filtered)" : ""));
            System.out.println("[SPATIAL] City envelope: [" +
                String.format("%.4f", cityEnvelope.getMinX()) + "," +
                String.format("%.4f", cityEnvelope.getMinY()) + "] to [" +
                String.format("%.4f", cityEnvelope.getMaxX()) + "," +
                String.format("%.4f", cityEnvelope.getMaxY()) + "]");

        } catch (Exception e) {
            System.err.println("[SPATIAL] Warning: Failed to load city boundary: " +
                e.getMessage() + " — falling back to config bounds");
            cityBoundaryEnabled = false;
        }
    }

    /**
     * Loads the operating zone: the polbnda_jpn.shp municipalities whose
     * adm_code is exactly one of {@code admCodes} (e.g. Tokyo's Special Wards
     * zone = 13101-13123, 13203, 13204). An empty or null set disables the
     * zone, and {@link #isInOperatingZone} then returns true everywhere, so a
     * city without the config key behaves exactly as before.
     *
     * @throws IllegalStateException if codes were given but none was found
     */
    public void loadOperatingZone(Set<String> admCodes) {
        operatingZoneEnabled = false;
        operatingZoneCodes = Collections.emptySet();
        operatingZonePolygonCount = 0;
        if (admCodes == null || admCodes.isEmpty()) {
            System.out.println("[SPATIAL] No operating zone configured");
            return;
        }
        String path = shapefileDir + "polbnda_jpn.shp";
        List<SimpleFeature> features;
        try {
            features = ShpLoader.load(path, "adm_code", admCodes, null);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load operating zone from " + path, e);
        }
        STRtree index = new STRtree();
        Envelope envelope = new Envelope();
        Set<String> found = new TreeSet<>();
        PreparedGeometryFactory prepared = new PreparedGeometryFactory();
        for (SimpleFeature feature : features) {
            Geometry geom = (Geometry) feature.getDefaultGeometry();
            if (geom == null) continue;
            String code = feature.getAttribute("adm_code").toString().trim();
            index.insert(geom.getEnvelopeInternal(), new ZonePolygon(code, prepared.create(geom)));
            envelope.expandToInclude(geom.getEnvelopeInternal());
            found.add(code);
            operatingZonePolygonCount++;
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("Operating zone codes " + admCodes + " matched no polygon in " + path);
        }
        Set<String> missing = new TreeSet<>(admCodes);
        missing.removeAll(found);
        if (!missing.isEmpty()) {
            // A silently smaller zone would mis-state where taxis may operate.
            throw new IllegalStateException("Operating-zone codes not found in " + path + ": " + missing);
        }
        index.build();
        operatingZoneIndex = index;
        operatingZoneEnvelope = envelope;
        operatingZoneCodes = Collections.unmodifiableSet(found);
        operatingZoneEnabled = true;
        System.out.println("[SPATIAL] Operating zone: " + found.size() + " municipalities, " +
            operatingZonePolygonCount + " polygons, envelope [" +
            String.format("%.4f,%.4f] to [%.4f,%.4f]", envelope.getMinX(), envelope.getMinY(),
                envelope.getMaxX(), envelope.getMaxY()));
    }

    /**
     * True if the point lies in the operating zone, or if no zone is loaded.
     * Exact point-in-polygon on every call (no cache).
     */
    public boolean isInOperatingZone(double lon, double lat) {
        if (!operatingZoneEnabled) return true;
        return operatingZoneCodeAt(lon, lat) != null;
    }

    /** adm_code of the operating-zone municipality containing the point; null if outside or disabled. */
    @SuppressWarnings("unchecked")
    public String operatingZoneCodeAt(double lon, double lat) {
        if (!operatingZoneEnabled) return null;
        Point point = geometryFactory.createPoint(new Coordinate(lon, lat));
        List<ZonePolygon> candidates = operatingZoneIndex.query(point.getEnvelopeInternal());
        for (ZonePolygon zp : candidates) {
            if (zp.geometry.covers(point)) return zp.admCode;
        }
        return null;
    }

    /** One operating-zone polygon with its municipality code. */
    private static final class ZonePolygon {
        final String admCode;
        final PreparedGeometry geometry;

        ZonePolygon(String admCode, PreparedGeometry geometry) {
            this.admCode = admCode;
            this.geometry = geometry;
        }
    }

    public boolean isOperatingZoneEnabled() { return operatingZoneEnabled; }

    /**
     * Load the non-road exclusion layer (TASK-7): polygons inside which taxis cannot pick up or set
     * down. A blank path leaves it off; a configured path that cannot be read stops the run.
     */
    public void loadExclusionAreas(String path) {
        exclusionEnabled = false;
        exclusionPolygonCount = 0;
        if (path == null || path.isBlank()) {
            System.out.println("[SPATIAL] No exclusion areas configured");
            return;
        }
        List<Geometry> geometries = new ArrayList<>();
        try {
            for (SimpleFeature feature : ShpLoader.load(path)) {
                Geometry geom = (Geometry) feature.getDefaultGeometry();
                if (geom != null) geometries.add(geom);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load exclusion areas from " + path, e);
        }
        if (geometries.isEmpty()) {
            throw new IllegalStateException("Exclusion layer " + path + " has no polygons");
        }
        loadExclusionGeometries(geometries);
        System.out.println("[SPATIAL] Exclusion areas: " + exclusionPolygonCount + " polygons from " + path);
    }

    /** Index the given exclusion polygons (package-private so tests need no shapefile). */
    void loadExclusionGeometries(List<Geometry> geometries) {
        STRtree index = new STRtree();
        PreparedGeometryFactory prepared = new PreparedGeometryFactory();
        for (Geometry geom : geometries) {
            index.insert(geom.getEnvelopeInternal(), prepared.create(geom));
        }
        index.build();
        exclusionIndex = index;
        exclusionPolygonCount = geometries.size();
        exclusionEnabled = !geometries.isEmpty();
    }

    public boolean isExclusionEnabled() { return exclusionEnabled; }

    /** True if the point lies in an exclusion polygon. Exact test on every call (no cache). */
    @SuppressWarnings("unchecked")
    public boolean isExcluded(double lon, double lat) {
        if (!exclusionEnabled) return false;
        Point point = geometryFactory.createPoint(new Coordinate(lon, lat));
        for (PreparedGeometry g : (List<PreparedGeometry>) exclusionIndex.query(point.getEnvelopeInternal())) {
            if (g.covers(point)) return true;
        }
        return false;
    }

    /** Which check isValidLocation would fail first (for logs): "valid", "out of city", "water", "river buffer". */
    public String describeValidity(double lon, double lat) {
        if (!isWithinCity(lon, lat)) return "out of city";
        if (!isOnLand(lon, lat)) return "water";
        if (isNearRiver(lon, lat, riverBufferKm)) return "river buffer";
        return "valid";
    }

    /** adm_codes actually loaded (empty when the zone is disabled). */
    public Set<String> getOperatingZoneCodes() { return operatingZoneCodes; }

    public int getOperatingZonePolygonCount() { return operatingZonePolygonCount; }

    /** Bounding envelope of the operating zone, or null when disabled. */
    public Envelope getOperatingZoneEnvelope() {
        return operatingZoneEnabled ? operatingZoneEnvelope : null;
    }

    /**
     * Loads all spatial layers in the correct order.
     * Convenience method that calls all individual load methods.
     */
    public void loadAll() {
        loadAll(null);
    }

    /**
     * Loads all spatial layers including optional city boundary.
     *
     * @param prefectureCodes Comma-separated prefecture codes, or null/empty to skip
     */
    public void loadAll(String prefectureCodes) {
        loadAll(prefectureCodes, null);
    }

    /**
     * Loads all spatial layers including optional city boundary with focus area filtering.
     *
     * @param prefectureCodes Comma-separated prefecture codes, or null/empty to skip
     * @param configBounds Config rectangle as {minLon, maxLon, minLat, maxLat} to filter remote islands
     */
    public void loadAll(String prefectureCodes, double[] configBounds) {
        System.out.println("[SPATIAL] ── Initializing Taxi Spatial Validation ──");
        loadJapanBoundaries();
        loadCityBoundary(prefectureCodes, configBounds);
        loadWaterBodies();
        loadRiverLines();
        System.out.println("[SPATIAL] ── Spatial validation ready ──");
    }

    // ════════════════════════════════════════════════════════════════════════
    // VALIDATION METHODS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Checks if coordinates are on land (driveable surface).
     * Uses shapefile polygon validation with land boundary inclusion
     * and inland water body exclusion.
     *
     * <p>Results are cached at ~100m resolution to avoid redundant
     * JTS geometry operations on nearby queries.
     *
     * @param lon Longitude (EPSG:4326 WGS84)
     * @param lat Latitude (EPSG:4326 WGS84)
     * @return true if on land, false if in water/ocean
     */
    public boolean isOnLand(double lon, double lat) {
        if (japanBoundaries == null) {
            return true;  // validation unavailable — assume valid
        }

        // Cache key at ~100m resolution
        String cacheKey = String.format("%.3f,%.3f", lon, lat);
        Boolean cachedResult = landValidationCache.get(cacheKey);
        if (cachedResult != null) {
            cacheHits++;
            return cachedResult;
        }

        // Query spatial index with ±0.01° (~1km) envelope
        double radius = 0.01;
        List<Geometry> candidates = japanBoundaries.query(
            lon - radius, lat - radius,
            lon + radius, lat + radius
        );

        Point point = geometryFactory.createPoint(new Coordinate(lon, lat));
        boolean result = false;
        for (Geometry geometry : candidates) {
            if (geometry.covers(point)) {
                result = true;
                break;
            }
        }

        // Inland water body exclusion (Lake Biwa, reservoirs, etc.)
        if (result && waterBodies != null) {
            List<Geometry> waterCandidates = waterBodies.query(
                lon - radius, lat - radius,
                lon + radius, lat + radius
            );
            for (Geometry wg : waterCandidates) {
                if (wg.covers(point)) {
                    result = false;
                    break;
                }
            }
        }

        landValidationCache.put(cacheKey, result);
        return result;
    }

    /**
     * River proximity check: is the point within bufferKm of any river centerline?
     * Uses riverl_jpn.shp with STRtree spatial index.
     * Returns false if river data not loaded (no exclusion applied).
     *
     * @param lon Longitude
     * @param lat Latitude
     * @param bufferKm Buffer distance in kilometers
     * @return true if within buffer distance of a river
     */
    public boolean isNearRiver(double lon, double lat, double bufferKm) {
        if (riverLines == null) return false;

        double searchDeg = bufferKm / 111.0;
        List<Geometry> candidates = riverLines.query(
            lon - searchDeg, lat - searchDeg,
            lon + searchDeg, lat + searchDeg
        );

        if (candidates.isEmpty()) return false;

        Point point = geometryFactory.createPoint(new Coordinate(lon, lat));
        for (Geometry g : candidates) {
            double distDeg = point.distance(g);
            double distKm = distDeg * 111.0 * Math.cos(Math.toRadians(lat));
            if (distKm <= bufferKm) return true;
        }
        return false;
    }

    /**
     * Checks if coordinates fall within the configured city prefecture boundary.
     * Uses a dedicated STRtree of prefecture-filtered municipality polygons.
     * Returns true if city boundary is not configured (fallback mode).
     *
     * @param lon Longitude (EPSG:4326)
     * @param lat Latitude (EPSG:4326)
     * @return true if within city boundary or if boundary not configured
     */
    @SuppressWarnings("unchecked")
    public boolean isWithinCity(double lon, double lat) {
        if (!cityBoundaryEnabled) return true;

        String cacheKey = String.format("C%.3f,%.3f", lon, lat);
        Boolean cached = cityValidationCache.get(cacheKey);
        if (cached != null) {
            cacheHits++;
            return cached;
        }

        List<Geometry> candidates = cityBoundaryIndex.query(
            new Envelope(lon - 0.01, lon + 0.01, lat - 0.01, lat + 0.01));

        Point point = geometryFactory.createPoint(new Coordinate(lon, lat));
        boolean result = false;
        for (Geometry geom : candidates) {
            if (geom.covers(point)) {
                result = true;
                break;
            }
        }

        cityValidationCache.put(cacheKey, result);
        return result;
    }

    /**
     * Returns the bounding envelope of the city polygon.
     * Used by TaxiSimulation for random point sampling bounds.
     * Returns null if city boundary is not configured.
     */
    public Envelope getCityEnvelope() {
        return cityBoundaryEnabled ? cityEnvelope : null;
    }

    /**
     * Whether city boundary filtering is active.
     */
    public boolean isCityBoundaryEnabled() {
        return cityBoundaryEnabled;
    }

    /**
     * Composite validation: checks all spatial layers for a taxi trip coordinate.
     * <ol>
     *   <li>Must be within city boundary (if configured)</li>
     *   <li>Must be on land (inside admin boundary polygon, outside water bodies)</li>
     *   <li>Must not be within river buffer zone</li>
     * </ol>
     *
     * @param lon Longitude
     * @param lat Latitude
     * @return true if coordinate is acceptable for taxi operation
     */
    public boolean isValidLocation(double lon, double lat) {
        totalChecks++;

        if (!isWithinCity(lon, lat)) {
            rejectedOutOfCity++;
            return false;
        }

        if (!isOnLand(lon, lat)) {
            rejectedWater++;
            return false;
        }

        if (isNearRiver(lon, lat, riverBufferKm)) {
            rejectedRiver++;
            return false;
        }

        if (isExcluded(lon, lat)) {
            rejectedExcluded++;
            return false;
        }

        return true;
    }

    /**
     * Print spatial validation statistics to stdout.
     */
    public void printStatistics() {
        System.out.println("[SPATIAL] Validation statistics:");
        System.out.println("  Total checks: " + totalChecks);
        System.out.println("  Cache hits: " + cacheHits +
            " (" + String.format("%.1f%%", totalChecks > 0 ? 100.0 * cacheHits / totalChecks : 0) + ")");
        System.out.println("  Rejected (out of city): " + rejectedOutOfCity);
        System.out.println("  Rejected (water/ocean): " + rejectedWater);
        System.out.println("  Rejected (river buffer): " + rejectedRiver);
        if (exclusionEnabled) System.out.println("  Rejected (exclusion areas): " + rejectedExcluded);
        System.out.println("  Cache size: " + landValidationCache.size() + " entries");
    }

}
