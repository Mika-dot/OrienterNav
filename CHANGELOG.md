# Changelog

## 0.5.2-beta

- added GPS/camera zero-velocity hold so accelerometer bias cannot move a parked car;
- added lightweight frame-to-frame visual motion and turn cues without claiming unreliable monocular scale;
- constrained uncertain navigation and server priors to the active car-route corridor while retaining a raw pose for real detours;
- limited every visual correction step and tightened multi-frame consistency to break localization feedback loops;
- made rerouting require five recent, moving, absolute off-route observations and added a 25-second cooldown;
- added adaptive road-scene exposure control and a darker initial camera exposure for windscreens at night;
- stabilized map rotation using the route bearing while road lock is active.

## 0.5.1-beta

- allowed the HTTP OrienterNet endpoint inside an encrypted Tailscale tunnel;
- added an automatic OpenStreetMap raster fallback when OpenFreeMap is unavailable;
- added automatic recovery after a CameraX capture-session failure;
- exposed the application version in the settings title to prevent APK mix-ups.

## 0.5.0-beta

- replaced route-only progress simulation with two-dimensional short-gap dead reckoning;
- transformed linear acceleration into the Earth frame on supported devices;
- added uncertainty growth and explicit GPS/vision/inertial source reporting;
- required multiple consistent OrienterNet fixes before accepting visual recovery;
- removed the old 55 m route lock that rejected valid off-route visual fixes;
- added debounced off-route detection and automatic OSRM rerouting;
- constrained route projection to a local window to avoid jumps at route crossings;
- added Russian manoeuvre parsing, voice prompts, address search and arrival detection;
- added overexposure, underexposure and low-detail frame rejection with exposure nudging;
- modernized the map-first Android interface and hid camera preview outside diagnostics;
- restored active Android unit tests for geometry and rerouting behaviour;
- added a beginner-oriented Windows, APK, server, Tailscale and GitHub Release guide.
