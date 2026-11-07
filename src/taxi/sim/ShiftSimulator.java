package taxi.sim;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * Shift-time taxi simulator (v7.0 engine). Activated by config key
 * {@code taxi.simulation.engine=shift_time}.
 *
 * <p><b>B2 / Phase 4 implementation (current):</b> 5-state machine driving
 * trip generation by elapsed shift time, not by config'd trip count. Each
 * taxi cycles through:
 * <pre>
 *   shift_start
 *     │
 *     ▼
 *   ┌─────────────┐  stand_share(type)   ┌──────────┐
 *   │  DUAL_MODE  │ ───────────────────▶│ AT_STAND │
 *   │  (cruising) │                      │ (B3 will │
 *   │             │ ◀────────────────────┤ add Exp  │
 *   │ empty leg + │  next iteration      │ wait)    │
 *   │ pickup mode │                      └────┬─────┘
 *   └──────┬──────┘                           │ pickup
 *          │  pickup (street or app)          │
 *          ▼                                  ▼
 *   ┌──────────────────────────────────────────┐
 *   │           OCCUPIED (loaded leg)          │
 *   │   distance ~ lognormal(4.6 km, σ=0.6)    │
 *   └──────┬──────────────────────────────────┘
 *          │  dropoff
 *          ▼
 *   (loop back to top — sample stand vs DUAL_MODE)
 * </pre>
 *
 * <p>Trip count emerges from {@code shift_h ÷ cycle_h} ≈ 14h / 0.54h ≈ 26
 * cycles, matching THTA FY2024 target of 25.3 trips/active-taxi/day by
 * construction (DESIGN.md §2.5). The legacy {@code taxi.trips.average} cap
 * is irrelevant in this engine.
 *
 * <p><b>B2 partial scope:</b> the AT_STAND branch in this batch moves the
 * taxi to the nearest hub zone but does NOT yet add an exponential wait
 * (mean 8/12/6 min by stand type). B3 / Phase 5 wires the {@code Exp(λ)}
 * wait. Stand pickups are still counted in the per-type mode-mix observer.
 *
 * <p><b>B3 / Phase 6:</b> RIDE_HAIL_PRHS taxis will have their shifts clipped
 * to MLIT-permitted operating windows ({@code prhs.window.N} config keys).
 * Currently they run full shifts.
 *
 * <p><b>B2 finding — cycle-overhead caveat (open, deferred to paper §7):</b>
 * DESIGN.md §2.5 predicted ~26 cycles per 14h shift from {@code shift / (4.6/18 + 5.1/18)} ≈
 * 14h / 0.539h. That math counts only driving time. The legacy config keeps
 * {@code taxi.pickup.time=300s} (5 min boarding) and {@code taxi.break.time=600s}
 * (10 min post-dropoff break) as artifacts of the count-driven legacy engine
 * where they were noise (the trip count was capped externally). In the
 * cycle-driven shift_time engine these become first-order — adding 15 min per
 * cycle nearly halves the achievable count to ~14 cycles. Realised:
 * 14.69 trips/taxi vs 25.3 target (-41.9%). All distance/revenue derivatives
 * follow.
 *
 * <p>Real Tokyo industry data (THTA FY2024 p.13: 243.7 km/day at 18 km/h
 * weighted-avg speed = 13.5h driving in a 14h shift) implies ~1 min/trip of
 * non-driving overhead — already absorbed into the 12-hour weighted speed of
 * 18 km/h. The B2 cycle should likely use ~zero pickup/break or a separate
 * shift_time-only key set. Decision deferred to post-B6 review.
 */
class ShiftSimulator {

    private final TaxiConfig config;
    private final TaxiSimulation host;
    private final ModeMixResolver modeMix;
    private final Random random;
    /** B7: per-cycle diagnostic recorder (no-op when disabled). */
    private final DiagnosticWriter diagnostics;
    /** B7: pre-computed sampled taxi-id set for stratified diagnostic emit. */
    private final java.util.Set<Integer> diagnosticSampleIds;

    // Manhattan factor cached once per simulator (config getter is hot).
    private final double manhattanFactor;
    // Empty-leg log-normal parameters (DESIGN.md §2.6, also B1 config keys).
    private final double emptyMu;
    private final double emptySigma;
    // Empty-leg bounds (DESIGN.md §2.6: [0.3, 12] km road-km).
    static final double EMPTY_MIN_ROAD_KM = 0.3;
    static final double EMPTY_MAX_ROAD_KM = 12.0;

    // B3 / Phase 6: pre-parsed PRHS windows filtered to today's day-of-week.
    // Empty list ⇒ no PRHS operating today (Sun in R8 contraction, etc.) ⇒ all
    // RIDE_HAIL_PRHS taxis stay OFF_DUTY for the day.
    private final List<PrhsWindow> prhsWindowsToday;

    // WP0: empty legs decomposed by how their end point was produced.
    private final EmptyLegStats emptyLegStats = new EmptyLegStats();
    // Path of the most recent sampleDualModeEmptyLeg() result (single-threaded).
    private EmptyLegPath lastEmptyLegPath = EmptyLegPath.FIRST_DRAW;

    ShiftSimulator(TaxiConfig config, TaxiSimulation host,
                   ModeMixResolver modeMix, Random random,
                   DiagnosticWriter diagnostics,
                   java.util.Set<Integer> diagnosticSampleIds) {
        this.config = config;
        this.host = host;
        this.modeMix = modeMix;
        this.random = random;
        this.diagnostics = diagnostics;
        this.diagnosticSampleIds = diagnosticSampleIds;
        this.manhattanFactor = config.getManhattanFactor();
        // Lognormal mu corrected so E[exp(N(mu,sigma^2))] = empty.mean.km.
        this.emptySigma = config.getTripDistanceEmptySigma();
        this.emptyMu = Math.log(config.getTripDistanceEmptyMeanKm())
                       - 0.5 * emptySigma * emptySigma;
        // B3 / Phase 6: pre-parse PRHS windows for today's day-of-week.
        this.prhsWindowsToday = parsePrhsWindowsForToday(config);
        if (config.getPrhsWindowCount() > 0) {
            System.out.println("[PRHS] Day=" + config.getSimDayOfWeek()
                + ", active windows: " + (prhsWindowsToday.isEmpty()
                    ? "(none today)"
                    : prhsWindowsToday.toString()));
        }
    }

    EmptyLegStats getEmptyLegStats() {
        return emptyLegStats;
    }

    private static List<PrhsWindow> parsePrhsWindowsForToday(TaxiConfig config) {
        try {
            PrhsWindow.Day day = PrhsWindow.Day.parse(config.getSimDayOfWeek());
            return Collections.unmodifiableList(
                PrhsWindow.parseAndFilter(config.getPrhsWindowSpecs(), day));
        } catch (Exception e) {
            System.out.println("[PRHS] WARN: failed to parse windows: " + e.getMessage()
                + " — PRHS taxis will run unrestricted.");
            return Collections.emptyList();
        }
    }

    /**
     * Simulate one taxi's full shift using the v7.0 state machine.
     *
     * <p>The taxi starts at home and enters DUAL_MODE (or AT_STAND for HUB types
     * that roll into a stand on their first decision). Each cycle:
     * <ol>
     *   <li>Decide stand vs DUAL_MODE based on type's stand share
     *   <li>Empty leg: log-normal distance, direction toward attractive zone
     *       (DUAL_MODE) or nearest hub (AT_STAND)
     *   <li>Sample pickup mode (street vs app, conditional on not stand)
     *   <li>OCCUPIED: log-normal loaded leg, dropoff
     *   <li>Loop until shift_remaining &lt; cycle_estimate
     * </ol>
     */
    void simulateShift(TaxiAgent taxi) {
        final TaxiType type = taxi.getTaxiType();
        final long shiftEnd = taxi.getShiftEndTime();
        final double emptyTripThresholdKm = config.getEmptyTripThresholdKm();
        final boolean isPrhs = (type == TaxiType.RIDE_HAIL_PRHS);

        final long shiftStart = taxi.getShiftStartTime();
        long currentTime = shiftStart;
        double curLon = taxi.getHomeLongitude();
        double curLat = taxi.getHomeLatitude();

        // B7: cycle counter for diagnostic emit and end-of-shift marker.
        int cycleIdx = 0;
        // WP0: the latest empty leg not yet followed by a passenger leg. If the
        // next cycle starts, or the shift ends, first, it is an orphan.
        EmptyLegPath pendingEmptyPath = null;
        double pendingEmptyKm = 0.0;
        boolean shiftEndEmitted = false;  // sentinel to avoid duplicate SHIFT_END_MARKER
        final boolean diagnose = diagnostics != null && diagnostics.isEnabled()
            && diagnosticSampleIds != null && diagnosticSampleIds.contains(taxi.getTaxiId());

        // B3 / Phase 6: PRHS taxis can only operate inside MLIT-permitted windows
        // for today's day-of-week. If no window is active today, the taxi stays
        // OFF_DUTY (zero trips).
        if (isPrhs && prhsWindowsToday.isEmpty()) {
            if (diagnose) {
                diagnostics.emitShiftEnd(taxi, 0, 0, DiagnosticWriter.EndReason.PRHS_NO_WINDOW);
            }
            return;  // PRHS not permitted today (e.g. Sun in R8 contraction)
        }

        // Loop until shift end. Each iteration emits one OCCUPIED trip (and
        // possibly a preceding empty trip). The loop exits when an empty +
        // loaded cycle would push past shiftEnd. PRHS taxis additionally
        // skip-forward to the next window if currently outside one.
        while (currentTime < shiftEnd) {

            // B3 / Phase 6: PRHS window gate — advance to next valid window
            // if currently outside, or end the shift if no future window today.
            if (isPrhs) {
                PrhsWindow window = PrhsWindow.findCurrentOrNext(prhsWindowsToday, currentTime);
                if (window == null) {
                    if (diagnose) {
                        diagnostics.emitShiftEnd(taxi, cycleIdx,
                            currentTime - shiftStart, DiagnosticWriter.EndReason.PRHS_NO_WINDOW);
                        shiftEndEmitted = true;
                    }
                    break;  // no future window today
                }
                if (currentTime < window.startSeconds) {
                    currentTime = window.startSeconds;  // skip to window start
                    if (currentTime >= shiftEnd) {
                        if (diagnose) {
                            diagnostics.emitShiftEnd(taxi, cycleIdx,
                                currentTime - shiftStart, DiagnosticWriter.EndReason.SHIFT_END_NORMAL);
                            shiftEndEmitted = true;
                        }
                        break;
                    }
                }
                // Cap shift end to window end so cycle math respects regulation.
                long effectiveEnd = Math.min(shiftEnd, window.endSeconds);
                if (currentTime >= effectiveEnd) {
                    // Window has expired — try next iteration to pick next window.
                    currentTime = window.endSeconds;
                    continue;
                }
            }

            // --- 1. Decide AT_STAND vs DUAL_MODE for this cycle ---
            boolean rollStand = random.nextDouble() < modeMix.standShare(type);
            // RIDE_HAIL_PRHS: regulatorily forbidden from stand; force DUAL_MODE.
            if (type == TaxiType.RIDE_HAIL_PRHS) rollStand = false;

            // Resolve stand zone — DESIGN.md §2.3 says "Move position to nearest
            // hub-zone". If no hub zones configured, falls back to DUAL_MODE.
            DestinationZone standZone = rollStand
                ? host.selectStandHub(curLon, curLat, random)
                : null;
            final boolean atStand = (standZone != null);

            // --- 2. Empty leg + pickup position ---
            final double pickupLon, pickupLat;
            final ModeMixResolver.PickupMode pickupMode;
            final long emptyTravelTime;
            final EmptyLegPath emptyLegPath;

            if (atStand) {
                // Move to the hub's service point (its centroid unless WP0 anchored it); STAND pickup.
                pickupLon = standZone.getAnchorLon();
                pickupLat = standZone.getAnchorLat();
                pickupMode = ModeMixResolver.PickupMode.STAND;
                emptyLegPath = EmptyLegPath.STAND;
            } else {
                // DUAL_MODE: log-normal empty leg toward attractive zone centroid.
                double[] emptyResult = sampleDualModeEmptyLeg(taxi, curLon, curLat, currentTime);
                pickupLon = emptyResult[0];
                pickupLat = emptyResult[1];
                pickupMode = modeMix.sampleStreetVsApp(type);
                emptyLegPath = lastEmptyLegPath;
            }

            double emptyRoadKm = host.calculateDistance(curLon, curLat, pickupLon, pickupLat);
            emptyTravelTime = TaxiTrip.estimateTravelTime(emptyRoadKm);

            // Emit empty-trip record for the cruise (above threshold).
            if (pendingEmptyPath != null) {
                emptyLegStats.markOrphan(pendingEmptyPath, pendingEmptyKm, false);
            }
            if (emptyRoadKm > emptyTripThresholdKm) {
                emitEmptyTrip(taxi, curLon, curLat, pickupLon, pickupLat, currentTime, emptyTravelTime);
                TaxiGeoValidator zoneCheck = host.getGeoValidator();
                emptyLegStats.record(emptyLegPath, emptyRoadKm,
                    zoneCheck == null || zoneCheck.isInOperatingZone(curLon, curLat));
            } else {
                emptyLegStats.recordUnrecorded(emptyLegPath, emptyRoadKm);
            }
            pendingEmptyPath = emptyLegPath;
            pendingEmptyKm = emptyRoadKm;
            currentTime += emptyTravelTime;
            // B7: per-cycle EMPTY_LEG record (always recorded, even sub-threshold).
            if (diagnose) {
                diagnostics.emitLeg(taxi, cycleIdx, DiagnosticWriter.LegType.EMPTY_LEG,
                    emptyRoadKm, emptyTravelTime,
                    currentTime - shiftStart, shiftEnd - currentTime);
            }

            // B3 / Phase 5: AT_STAND queue — sample Exp(λ) wait, accumulate
            // against shift_remaining. Wait time depends on stand classification
            // (airport / major-station / entertainment) per DESIGN.md §2.8.
            if (atStand) {
                long waitSeconds = sampleStandWaitSeconds(standZone);
                currentTime += waitSeconds;
                if (diagnose) {
                    diagnostics.emitLeg(taxi, cycleIdx, DiagnosticWriter.LegType.AT_STAND_WAIT,
                        0.0, waitSeconds,
                        currentTime - shiftStart, Math.max(0, shiftEnd - currentTime));
                }
                if (currentTime >= shiftEnd) {
                    if (diagnose) {
                        diagnostics.emitShiftEnd(taxi, cycleIdx,
                            currentTime - shiftStart, DiagnosticWriter.EndReason.SHIFT_END_NORMAL);
                        shiftEndEmitted = true;
                    }
                    break;  // shift ended during stand wait
                }
            }

            curLon = pickupLon;
            curLat = pickupLat;

            // --- 3. OCCUPIED loaded leg ---
            double[] dropoff = host.sampleLogNormalLoadedLeg(pickupLon, pickupLat);
            if (dropoff == null) {
                // B7.3: Projection failed even after spiral. Previously this
                // ended the entire shift, costing ~36% of all cycles per
                // diagnostic.csv evidence. Instead, we now skip ONLY this
                // cycle: stay in place, advance time by a small "search retry"
                // budget (~30s, equivalent to one break), and try another
                // pickup-dropoff sample on the next iteration.
                currentTime += config.getShiftBreakTimeSeconds();
                if (currentTime >= shiftEnd) {
                    if (diagnose) {
                        diagnostics.emitShiftEnd(taxi, cycleIdx,
                            currentTime - shiftStart, DiagnosticWriter.EndReason.SHIFT_END_NORMAL);
                        shiftEndEmitted = true;
                    }
                    break;
                }
                continue;  // try next cycle from same position
            }
            double dropLon = dropoff[0];
            double dropLat = dropoff[1];
            double loadedRoadKm = host.calculateDistance(pickupLon, pickupLat, dropLon, dropLat);
            long loadedTravel = TaxiTrip.estimateTravelTime(loadedRoadKm);

            // B7.3: shift_time engine uses its own pickup/break overhead keys
            // (THTA-aligned ~30s each), not the legacy 5-min/10-min values.
            long pickupReadyTime = currentTime + config.getShiftPickupTimeSeconds();
            long dropoffTime = pickupReadyTime + loadedTravel;
            if (dropoffTime > shiftEnd) {
                // Wouldn't finish in time — end shift cleanly.
                if (diagnose) {
                    diagnostics.emitShiftEnd(taxi, cycleIdx,
                        currentTime - shiftStart, DiagnosticWriter.EndReason.PREDICTED_OVERRUN);
                    shiftEndEmitted = true;
                }
                break;
            }
            // B3 / Phase 6: PRHS strict interpretation — trip must complete
            // within the regulatory window. Skip if it would overrun.
            if (isPrhs) {
                PrhsWindow w = PrhsWindow.findCurrentOrNext(prhsWindowsToday, currentTime);
                if (w == null || dropoffTime > w.endSeconds) {
                    // Either no window remains, or dropoff would land past
                    // window end. Advance to next window (if any) and retry.
                    if (w != null && dropoffTime > w.endSeconds) {
                        currentTime = w.endSeconds;
                        continue;
                    }
                    if (diagnose) {
                        diagnostics.emitShiftEnd(taxi, cycleIdx,
                            currentTime - shiftStart, DiagnosticWriter.EndReason.PRHS_NO_WINDOW);
                        shiftEndEmitted = true;
                    }
                    break;
                }
            }

            TaxiTrip passengerTrip = new TaxiTrip(host.nextTripId(),
                pickupLon, pickupLat, dropLon, dropLat,
                currentTime, /*passengerIn=*/ true);
            passengerTrip.setTimes(pickupReadyTime, dropoffTime);
            passengerTrip.setAssignedTaxiId(taxi.getTaxiId());
            passengerTrip.setStatus(TaxiTrip.TripStatus.COMPLETED);
            taxi.assignTrip(passengerTrip);
            pendingEmptyPath = null;
            host.recordPassengerTripOutput(passengerTrip);
            host.recordPickupMode(type, pickupMode);
            // B7: per-cycle LOADED_LEG record. Includes pickup_wait + travel.
            if (diagnose) {
                diagnostics.emitLeg(taxi, cycleIdx, DiagnosticWriter.LegType.LOADED_LEG,
                    loadedRoadKm,
                    config.getShiftPickupTimeSeconds() + loadedTravel,
                    dropoffTime - shiftStart, shiftEnd - dropoffTime);
            }

            // Advance to dropoff position for next cycle.
            curLon = dropLon;
            curLat = dropLat;
            currentTime = dropoffTime + config.getShiftBreakTimeSeconds();
            cycleIdx++;
        }
        if (pendingEmptyPath != null) {
            emptyLegStats.markOrphan(pendingEmptyPath, pendingEmptyKm, true);
        }
        // Natural exit fallback: only emit if no break path already did.
        if (diagnose && !shiftEndEmitted) {
            diagnostics.emitShiftEnd(taxi, cycleIdx, currentTime - shiftStart,
                DiagnosticWriter.EndReason.SHIFT_END_NORMAL);
        }
    }

    /**
     * Sample a DUAL_MODE empty leg: log-normal road-km distance toward a zone
     * centroid weighted by attractiveness.
     *
     * <p>If no zones are configured or all attractiveness scores are zero, falls
     * back to a uniform random bearing. If the resulting position fails spatial
     * validation, projects to the nearest valid cell using the same spiral-grid
     * scan as the loaded-leg path.
     */
    private double[] sampleDualModeEmptyLeg(TaxiAgent taxi, double curLon, double curLat, long currentTime) {
        // Same draw order as before WP0 (distance, target, bearing), so a run
        // without an operating zone keeps its random stream.
        final double roadKm = drawEmptyRoadKm();
        final DestinationZone target = pickAttractiveZone(currentTime, curLon, curLat);
        final double[] first = moveToward(target, curLon, curLat, roadKm);

        TaxiGeoValidator gv = host.getGeoValidator();
        if (gv == null || !gv.isOperatingZoneEnabled()) return first;

        // WP0: the cruising end point is the pickup, which must lie inside the
        // operating zone. Re-draw the distance toward the same target; after
        // operating.zone.pickup.max.attempts, deadhead to the target centroid
        // (eligible zones are inside the zone and on valid land).
        final EmptyLegPath firstPath = lastEmptyLegPath;
        DestinationZone anchor = target != null ? target : host.nearestEligibleZone(curLon, curLat);
        double[] fallback = anchor != null
            ? new double[]{anchor.getAnchorLon(), anchor.getAnchorLat()}
            : new double[]{curLon, curLat};
        final boolean[] firstUsed = {false};
        InZoneResult r = acceptInZone(() -> {
            if (!firstUsed[0]) {
                firstUsed[0] = true;
                return first;
            }
            return moveToward(target, curLon, curLat, drawEmptyRoadKm());
        }, gv::isInOperatingZone, fallback, config.getOperatingZoneMaxAttempts());
        emptyLegStats.recordAttempts(r.attempts);
        lastEmptyLegPath = r.fellBack ? EmptyLegPath.CENTROID_DEADHEAD
            : (r.attempts == 1 ? firstPath : EmptyLegPath.RESAMPLED);
        return new double[]{r.lon, r.lat};
    }

    /** Result of {@link #acceptInZone}: the accepted point, draws used, and whether it fell back. */
    static final class InZoneResult {
        final double lon, lat;
        final int attempts;
        final boolean fellBack;

        InZoneResult(double lon, double lat, int attempts, boolean fellBack) {
            this.lon = lon; this.lat = lat; this.attempts = attempts; this.fellBack = fellBack;
        }
    }

    /**
     * Returns the first sample for which inside(lon, lat) holds, drawing at most
     * maxAttempts samples; otherwise the fallback point (fellBack = true).
     */
    static InZoneResult acceptInZone(Supplier<double[]> sampler, BiPredicate<Double, Double> inside,
                                     double[] fallback, int maxAttempts) {
        for (int i = 1; i <= maxAttempts; i++) {
            double[] p = sampler.get();
            if (inside.test(p[0], p[1])) return new InZoneResult(p[0], p[1], i, false);
        }
        return new InZoneResult(fallback[0], fallback[1], maxAttempts, true);
    }

    /** Log-normal road-km for one empty leg, re-drawn into [EMPTY_MIN, EMPTY_MAX] (B7.5). */
    private double drawEmptyRoadKm() {
        // Sample log-normal road-km empty distance per DESIGN.md §2.6.
        // B7.5: reject-and-redraw within [EMPTY_MIN_ROAD_KM, EMPTY_MAX_ROAD_KM]
        // instead of clamping; eliminates at-floor spike artifact.
        double roadKm;
        int attempts = 0;
        do {
            roadKm = Math.exp(emptyMu + emptySigma * random.nextGaussian());
            attempts++;
        } while ((roadKm < EMPTY_MIN_ROAD_KM || roadKm > EMPTY_MAX_ROAD_KM) && attempts < 100);
        if (roadKm < EMPTY_MIN_ROAD_KM || roadKm > EMPTY_MAX_ROAD_KM) {
            roadKm = Math.max(EMPTY_MIN_ROAD_KM, Math.min(EMPTY_MAX_ROAD_KM, roadKm));
        }
        return roadKm;
    }

    /**
     * Move roadKm (as road distance) from the current position toward the target
     * centroid, or on a uniform random bearing if there is no target; project to
     * the nearest valid cell if the end point fails spatial validation, or stay
     * put if projection fails. Sets {@link #lastEmptyLegPath}.
     */
    private double[] moveToward(DestinationZone target, double curLon, double curLat, double roadKm) {
        double haversineKm = roadKm / manhattanFactor;

        // Determine bearing: toward target centroid, or uniform random if no zone.
        double dxKm, dyKm;
        if (target != null) {
            double cosLat = Math.cos(Math.toRadians(curLat));
            dxKm = (target.getCenterLon() - curLon) * 111.32 * cosLat;
            dyKm = (target.getCenterLat() - curLat) * 111.32;
            double mag = Math.sqrt(dxKm * dxKm + dyKm * dyKm);
            if (mag < 1e-6) {
                // Already at the centroid — pick a random bearing.
                double bearing = 2.0 * Math.PI * random.nextDouble();
                dxKm = Math.cos(bearing);
                dyKm = Math.sin(bearing);
            } else {
                dxKm /= mag;
                dyKm /= mag;
            }
        } else {
            double bearing = 2.0 * Math.PI * random.nextDouble();
            dxKm = Math.cos(bearing);
            dyKm = Math.sin(bearing);
        }

        // Move haversineKm in that direction.
        double cosLat = Math.cos(Math.toRadians(curLat));
        double newLon = curLon + (haversineKm * dxKm) / (111.32 * cosLat);
        double newLat = curLat + (haversineKm * dyKm) / 111.32;

        // Validate; project if invalid.
        TaxiGeoValidator gv = host.getGeoValidator();
        if (gv != null && !gv.isValidLocation(newLon, newLat)) {
            double[] projected = host.projectToNearestValidCell(newLon, newLat,
                config.getProjectionRadiusKm());
            if (projected != null) {
                lastEmptyLegPath = EmptyLegPath.PROJECTED;
                return projected;
            }
            // Fall back to current position if projection fails (zero empty leg).
            lastEmptyLegPath = EmptyLegPath.STAY_PUT;
            return new double[]{curLon, curLat};
        }
        lastEmptyLegPath = EmptyLegPath.FIRST_DRAW;
        return new double[]{newLon, newLat};
    }

    /**
     * Pick a destination zone weighted by attractiveness (without distance match).
     * Returns null if no zones or all scores are zero.
     */
    private DestinationZone pickAttractiveZone(long currentTime, double curLon, double curLat) {
        List<DestinationZone> zones = host.getDestinationZonesList();
        if (zones == null || zones.isEmpty()) return null;
        int timePeriod = host.getTimePeriod(currentTime);

        double[] raw = new double[zones.size()];
        for (int i = 0; i < zones.size(); i++) {
            raw[i] = zones.get(i).calculateAttractiveness(timePeriod,
                config.getAttractivenessBeta1(),
                config.getAttractivenessBeta2(),
                config.getAttractivenessBeta3(),
                config.getAttractivenessBeta4());
        }
        double[] scores = eligibleScores(zones,
            distanceDecayed(zones, raw, curLon, curLat, config.getCruiseDistanceDecayKm()));
        double totalScore = 0.0;
        for (double s : scores) totalScore += s;
        if (totalScore <= 0) return null;

        return rouletteZone(zones, scores, random.nextDouble() * totalScore);
    }

    /**
     * Scores multiplied by exp(-(d - dmin)/lambda) (proportional to exp(-d/lambda); dmin keeps small
     * lambdas from underflowing), d = road km (TaxiTrip.distanceKm) from (lon, lat) to each
     * zone's anchor (TASK-8). lambda <= 0 returns the scores unchanged, so the draw and its random
     * stream are exactly as before.
     */
    static double[] distanceDecayed(List<DestinationZone> zones, double[] raw,
                                    double lon, double lat, double lambdaKm) {
        if (!(lambdaKm > 0)) return raw;
        double[] km = new double[raw.length];
        double nearest = Double.MAX_VALUE;
        for (int i = 0; i < raw.length; i++) {
            DestinationZone z = zones.get(i);
            km[i] = TaxiTrip.distanceKm(lon, lat, z.getAnchorLon(), z.getAnchorLat());
            nearest = Math.min(nearest, km[i]);
        }
        // Measured from the nearest anchor: a common factor exp(nearest/lambda) that leaves the
        // draw's probabilities unchanged but keeps a small lambda from underflowing every score to 0.
        double[] out = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = raw[i] * Math.exp(-(km[i] - nearest) / lambdaKm);
        }
        return out;
    }

    /** Attractiveness scores with ineligible zones (outside the operating zone) set to 0. */
    static double[] eligibleScores(List<DestinationZone> zones, double[] raw) {
        double[] scores = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            scores[i] = zones.get(i).isServiceEligible() ? raw[i] : 0.0;
        }
        return scores;
    }

    /**
     * Roulette pick for r in [0, sum(scores)). Ineligible zones (outside the
     * operating zone) are skipped and can never be returned; eligible zones
     * follow the pre-WP0 loop exactly, including its last-zone fallback, so a
     * run without an operating zone (all zones eligible) is unchanged.
     */
    static DestinationZone rouletteZone(List<DestinationZone> zones, double[] scores, double r) {
        double cumulative = 0.0;
        DestinationZone lastEligible = null;
        for (int i = 0; i < zones.size(); i++) {
            if (!zones.get(i).isServiceEligible()) continue;
            cumulative += scores[i];
            lastEligible = zones.get(i);
            if (r <= cumulative) return lastEligible;
        }
        return lastEligible;
    }

    /**
     * B3 / Phase 5 (DESIGN.md §2.8): Sample exponential AT_STAND wait time.
     *
     * <p>Mean wait depends on stand classification:
     * <ul>
     *   <li>Airport (Haneda, Narita) — 8 min default (queue throughput)
     *   <li>Major station (Tokyo, Shinjuku, etc.) — 12 min default
     *   <li>Entertainment (Roppongi) — 6 min default
     * </ul>
     *
     * <p>Classification heuristic: name contains "Airport"/"空港" → airport;
     * name contains "Station"/"駅" → station; else → entertainment. The
     * heuristic is intentional — DESIGN.md §2.8 documents these mean values
     * as "documented assumptions", subject to GSA in B5.
     *
     * <p>Exponential sampling: {@code -mean × ln(U)} where U ~ Uniform(0,1).
     *
     * @return wait in seconds; 0 if the type-stand share or zone is
     *         degenerate (defensive — caller already gated on atStand)
     */
    private long sampleStandWaitSeconds(DestinationZone zone) {
        double meanMinutes = standMeanMinutesFor(zone);
        if (meanMinutes <= 0) return 0;
        // Avoid log(0) by sampling from (0, 1] instead of [0, 1).
        double u = 1.0 - random.nextDouble();
        double waitMinutes = -meanMinutes * Math.log(u);
        return (long)(waitMinutes * 60.0);
    }

    /** Look up the configured Exp mean (minutes) for a hub zone's stand. */
    private double standMeanMinutesFor(DestinationZone zone) {
        if (zone == null) return 0;
        return standMeanMinutes(zone.getStandClass(), zone.getName(),
            config.getTaxiStandWaitAirportMeanMinutes(),
            config.getTaxiStandWaitStationMeanMinutes(),
            config.getTaxiStandWaitEntertainmentMeanMinutes());
    }

    /**
     * Stand wait for a hub: its explicit stand class when zones.csv gives one (TASK-7), otherwise
     * the legacy name rule (so files without the column keep their behaviour).
     */
    static double standMeanMinutes(String standClass, String zoneName,
                                   double airport, double station, double other) {
        if (standClass != null) {
            switch (standClass) {
                case "airport": return airport;
                case "station": return station;
                default: return other;
            }
        }
        String name = zoneName == null ? "" : zoneName;
        // Airport: name contains "Airport" or "空港"
        if (name.contains("Airport") || name.contains("airport") || name.contains("空港")) {
            return airport;
        }
        // Major station: name contains "Station" or "駅"
        if (name.contains("Station") || name.contains("station") || name.contains("駅")) {
            return station;
        }
        // Else: entertainment / other (Roppongi etc.)
        return other;
    }

    /** Construct + record an empty trip and update its taxi's assigned list. */
    private void emitEmptyTrip(TaxiAgent taxi, double srcLon, double srcLat,
                               double dstLon, double dstLat,
                               long startTime, long travelTime) {
        TaxiTrip emptyTrip = TaxiTrip.createEmptyTrip(host.nextTripId(),
            srcLon, srcLat, dstLon, dstLat, startTime);
        emptyTrip.setTimes(startTime, startTime + travelTime);
        emptyTrip.setAssignedTaxiId(taxi.getTaxiId());
        emptyTrip.setStatus(TaxiTrip.TripStatus.COMPLETED);
        taxi.assignTrip(emptyTrip);
        host.recordEmptyTripOutput(emptyTrip);
    }
}
