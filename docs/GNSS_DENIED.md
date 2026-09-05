# GNSS-denied navigation: accuracy roadmap

This document separates what OrienterNav v0.2 actually implements from the next research steps that can materially improve accuracy.

## Current v0.2 estimator

The active estimator uses four information classes:

1. **absolute but potentially attacked** — Android GNSS/fused location;
2. **absolute and independent** — OrienterNet camera-to-OSM localization;
3. **relative/high-rate** — phone rotation vector + linear acceleration;
4. **structural** — the selected OSM/OSRM road polyline.

The important property is source independence. GNSS is not allowed to validate itself. Phone IMU can detect a trajectory innovation, but because consumer MEMS drifts it only raises suspicion. Repeated visual agreement is required before GNSS is fully rejected.

## Why route constraints help

Pure inertial dead reckoning has two major planar errors:

- cross-track heading error;
- along-track distance/velocity error.

A road polyline makes cross-track position and road bearing partially observable. `RouteMatcher` therefore projects an inertial candidate onto the route only if:

- the candidate is within a bounded cross-track distance;
- route heading agrees with inertial heading;
- route progress does not jump far backwards.

This is deliberately a soft constraint. At a wrong turn the raw position is allowed to leave the selected route instead of being permanently forced onto it.

## Highest-value next improvements

### 1. Wheel speed from OBD-II / CAN

This is the single most useful next sensor for long GNSS outages. Read-only wheel/vehicle speed removes most of the catastrophic longitudinal drift caused by integrating accelerometer bias.

Recommended fusion input:

```text
wheel distance + phone gyro/yaw + visual absolute fixes + route bearing
```

For a production-grade version, individual wheel speeds and steering angle are preferable to dashboard vehicle speed because they improve turn/curvature estimation.

### 2. Visual odometry between OrienterNet fixes

OrienterNet gives absolute but comparatively expensive fixes. A lightweight visual odometry/VIO frontend can estimate frame-to-frame motion at higher frequency and let OrienterNet act as a slower global correction.

Candidate architecture:

```text
camera frames -> feature/flow frontend -> relative SE(2)/SE(3) motion
                                            |
IMU preintegration -------------------------+
                                            v
                                    local factor graph
                                            |
OrienterNet absolute fix -------------------+
                                            |
OSM road/lane constraints ------------------+
```

The key is not to run another global neural localization pass for every frame.

### 3. Multiple road hypotheses instead of one selected route

The current matcher constrains against the chosen route. A stronger GNSS-free localizer should maintain several nearby candidate road segments with probabilities.

Practical choices:

- Hidden Markov Model + Viterbi map matching;
- particle filter over road-segment/progress hypotheses;
- factor graph with switchable road constraints.

Inputs for the likelihood can include heading, turn sequence, traveled distance, visual road class and intersection geometry.

### 4. Lane/road-edge constraints from the front camera

Lane boundaries, vanishing point, road edges and intersection topology provide lateral and heading corrections even when absolute visual place recognition is weak. This is especially useful on long straight roads where inertial along-track drift remains difficult.

Do not treat lane detection as an absolute position source. Use it as a geometric factor against mapped road/lane structure.

### 5. Barometer + elevation profile

On roads with meaningful elevation variation, phone barometer/pitch can be compared with a DEM/elevation profile. It is weak in flat terrain but nearly free and independent of GNSS coordinates.

### 6. Signals of opportunity

Wi-Fi/cellular observations can provide coarse independent evidence. They should be treated probabilistically and locally cached; they are not always available and can themselves be manipulated.

### 7. Global visual place recognition for cold start

The current OrienterNet backend is prior-conditioned. A true cold start without GNSS/manual area needs a first-stage retrieval database that maps image descriptors to coarse places, followed by OrienterNet refinement.

A scalable hierarchy would be:

```text
frame -> global descriptor retrieval -> top-K geographic tiles
      -> OrienterNet local refinement on each tile
      -> temporal consensus / route likelihood
```

## Spoofing detection improvements

v0.2 already compares GNSS against an independent inertial/visual state. Further integrity work should add:

- innovation normalized by the full covariance, not scalar distance only;
- persistent CUSUM/GLR-style change detection;
- raw GNSS observables when Android exposes them: C/N0, clock bias, pseudorange/range-rate consistency;
- multi-source velocity consistency;
- protection levels / integrity risk rather than only a traffic-light state.

A GNSS receiver reporting `accuracy=3 m` is not proof against spoofing. Reported accuracy is only one input to the integrity calculation.

## Offline operation

GNSS independence is different from internet independence. For a genuinely disconnected navigator package the following locally:

- vector/raster map tiles;
- OSRM graph and routing service or an embedded routing engine;
- geocoder data where needed;
- visual raster/map cache;
- optional visual retrieval descriptors.

The phone should be able to build and follow the route with every WAN interface disabled.

## Field-test protocol

Use a repeatable route with ground truth from a source that is not fed into the estimator during the test. Record all sensor streams and fused outputs.

Recommended scenarios:

1. normal open-sky GNSS baseline;
2. GNSS disabled after 30 s of calibration;
3. GNSS disabled immediately after a visual anchor;
4. synthetic coordinate jump/spoof replay of 100 m, 500 m and 2 km;
5. tunnel/underground parking;
6. parallel roads 10–30 m apart;
7. stacked interchange;
8. deliberate wrong turn from the planned route;
9. night/rain/feature-poor road;
10. phone mount rotated/reinstalled between runs.

Measure at minimum:

- median and 95th percentile horizontal error;
- maximum error during GNSS outage;
- error growth in m/min;
- time to spoof suspicion;
- time to spoof confirmation;
- false suspicion/false confirmation rate;
- route-segment accuracy;
- maneuver timing error;
- visual localization rejection rate;
- recovery time after a new trustworthy absolute fix.

## Acceptance targets for the next iteration

These are engineering targets, not current claims:

| Metric | Target |
|---|---:|
| short GNSS outage (30 s), urban road | < 20 m P95 |
| visual correction recovery | < 5 s after accepted fix |
| wrong parallel-road assignment | < 1% of evaluated frames |
| false spoof confirmation | < 0.1% of trips |
| route maneuver distance error | < 15 m P95 |
| uncertainty calibration | actual error inside reported 2-sigma for ~95% of samples |

The last item matters as much as raw accuracy: a navigator that says `±8 m` while being 80 m wrong is worse than one that reports `±100 m` and degrades safely.
