package taxi.sim;

/**
 * How an empty (cruising / repositioning) leg's end point was produced.
 * Used by {@link EmptyLegStats} to decompose realised empty km, because the
 * loaded-km ratio is a cancellation of these terms (calibration-guardian, WP0).
 */
enum EmptyLegPath {
    /** Log-normal draw toward an attractive zone, accepted as drawn. */
    FIRST_DRAW,
    /** Draw failed spatial validation and was moved by the spiral projection. */
    PROJECTED,
    /** Draw re-sampled because its end point left the operating zone. */
    RESAMPLED,
    /** All in-zone attempts failed; the leg ends at the target zone centroid. */
    CENTROID_DEADHEAD,
    /** Projection failed; the vehicle stays where it is (zero-length leg). */
    STAY_PUT,
    /**
     * TASK-13: nearby cruise accepted as drawn. The pickup lies within nearby.trip.radius.km of the
     * drop-off; the leg's km and time are the drawn road km, not the end-point distance.
     */
    NEARBY_CRUISE,
    /** TASK-13: nearby cruise whose end point was moved by the spiral projection; km as NEARBY_CRUISE. */
    NEARBY_PROJECTED,
    /** Leg to the nearest hub for a stand pickup. */
    STAND
}
