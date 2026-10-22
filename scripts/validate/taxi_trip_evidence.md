# Independent taxi export evidence

Requires Python 3.11+. From the repository root:

```text
python scripts/validate/taxi_trip_evidence.py /path/to/run/trips_pseudo_pflow.csv
python -m pytest scripts/validate/test_taxi_trip_evidence.py -q
```

The CLI writes JSON to stdout (redirect to a separate report). Exit 0 means the
export passed the implemented schema and domain checks; exit 2 means input or
inspection failure. Neither result is a city calibration grade. Source files
are never modified. Memory scales with trip IDs and observed taxi/day pairs.

The exact 16-column taxi header is required. Each row represents an occupied
or empty modeled leg, identified by `id`; `taxi_id` joins the fleet roster.
Coordinates are longitude then latitude in degrees. `starttime` is pickup
seconds after midnight; `simulation_day` is the zero-based day offset, and
`starttime_h` repeats the normalized time. `passenger_in` and `is_night_trip`
are literal `true`/`false`. `transport_mode=8`, `occupation=0`, and purpose
is 3 for occupied legs or 0 for empty legs. `distance_km` and `fare_yen` are
exported modeled values; fare is zero for empty legs. Header semantics follow
`TaxiDataExporter.exportTripsPseudoPFlow`.

Integer fields use Python integer parsing; identifiers are compared by numeric
value (so `01` and `1` are duplicates), not by their lexical representation.

Loaded-distance fraction is the sum of occupied kilometers divided by all
exported kilometers, not occupied-trip count divided by all rows. Zero total
distance produces null. Mean occupied distance uses occupied rows only.
Rounded CSV distances may differ slightly from internal simulation totals.

Counts describe this file. Observed taxis can omit zero-trip vehicles, and an
overnight shift contributes to more than one observed taxi/calendar-day pair.
No per-active-vehicle/day rate, registered fleet operating rate, spatial
coverage percentage or calibration threshold is inferred. Use the fleet roster
and declared shift/window/cohort definition for a separate matched comparison.
The hash identifies file bytes, not the producing binary or configuration.

For publication, distinguish three evidence classes: implementation checks
(this tool); fit to tuning statistics; independently withheld observations
matched by geography, reporting cohort, time period and observation process.
An Osaka stand survey requires simulated stand-only pickups at the surveyed
sites and hours. This generic trip export lacks pickup-mode and stand IDs and
cannot alone support that comparison.
