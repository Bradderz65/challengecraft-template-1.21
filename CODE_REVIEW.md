# Code review — 9 September 2026

The initial checkout was clean and matched GitHub `origin/main` at `181e2fe`,
confirmed with `git fetch origin` and a zero ahead/behind count.

Reviewed the Java server and client sources, configuration, networking, mixins,
build configuration, and mod metadata. The sections below record the review and
subsequent fixes; the final section gives the latest validation status.

## Corrections

- Failed A* searches now observe the existing five-second failure cooldown,
  allowing other mobs to use the global search budget.
- Ordinary A* edges are validated once rather than twice. Jump offsets are
  reused, redundant block/damage lookups are removed, and idle target searches
  respect their cooldown even when there is no player nearby.
- Diagonals require clear side headroom; jumps and falls cannot assume that
  intermediate blocks will be dug when those blocks are absent from the route.
- Shaft planning charges each traversed solid once. Following a sideways
  descent no longer digs the floor underneath the mob's previous column.
- Nearby and overhead nodes are not skipped before arrival, including when
  adopting a cached path.
- Zero-hardness solids are classified as requiring breaking. Mixed build/dig
  routes preserve the planner's allowed breaking hardness.
- Shared-route endpoints are checked for obstruction and unloaded chunks.
  A mob targeting another player no longer invalidates the shared corridor
  solely because its target is elsewhere.
- Pillar plans start at the mob's height rather than the target platform.
  Both building systems check entity obstruction and placement success, and
  mark their scaffolds consistently. Active A* cancels competing pillar work.
- Abandoned block damage expires, replacement block states do not inherit
  progress, invalid/air damage is ignored, and distance checks cannot overflow
  into a nearby swarm target. Failed block destruction does not publish a hole.
- Debug path/plan storage is disabled when debugging is off; periodic overlay
  refreshes also skip their list allocations.
- World shutdown clears transient hunt, tower, damage, countdown, and TPS state.
  Anti-tower ownership is updated on replacement and mob destruction, and its
  scan includes the world's minimum build height.
- Hunt range preserves base attributes instead of overwriting other customizations,
  and its modifier updates when the underlying base changes.
- Countdown arithmetic uses long integers. Speed commands respect the configuration
  limit. Disconnect resets client synchronization and cancels the benchmark override;
  benchmarks require an active local challenge.
- Loom is pinned to stable `1.14.10`, the version originally resolved by the snapshot.
  Minecraft compatibility is restricted to the actual compiled target, `1.21`.

## Validation

- Java 21 Gradle build and 40 regression tests pass.
- Fabric server `--initSettings` initializes ChallengeCraft successfully without
  starting a world. Initial missing settings/EULA messages are expected on a new
  development run; the command creates those files and exits.
- `git diff --check` passes.

Run `./gradlew build` with JDK 21 installed. Tests use JUnit Jupiter and Mockito;
their Minecraft world/entity fixtures are mocked, with real block definitions.

This review does not establish that the code is free of every possible bug.
Live-world movement, multiplayer interaction, other-mod compatibility, and
large-swarm FPS/TPS still need gameplay testing and profiling. The startup check
does not exercise a running world or the client renderer. No performance percentage
is claimed, and Minecraft API deprecation warnings remain in the build.

## Movement, damage and pillar follow-up

The running client's configuration confirmed speed 1.0, A* enabled, debug enabled,
and a 200-block hunt range. Its log showed no related exception. The code did contain
horizontal velocity pushes on ordinary A* nodes, an extra water multiplier, and
several routes that applied digging damage more than once per update.

- All custom horizontal velocity pushes, sprint boosts and high-speed teleport steps
  are removed. Navigation receives the configured multiplier directly; vertical wall
  climbing preserves the mob's existing horizontal velocity. The AI hook now runs
  after vanilla goal selection and before navigation, so goals do not immediately
  replace the builder's chosen approach position.
- One mob can dig once per 10 game ticks, across all its digging behaviors and target
  blocks. Solo nominal work is 40 ticks for dirt, 120 for stone and 160 for cobblestone
  or hardness-2 wood. The first chip is immediate, so observed completion may be up
  to half a second earlier. Lag and interrupted work can make it longer.
- Every block shares a 10-tick damage budget across all mobs and arrows: at most two
  solo digging chips, capped at 25% of the block. Additional swarm members cannot
  multiply damage without bound. Arrows affect only the impact block, take at least
  five spaced impacts for dirt and ten for cobblestone, and cannot damage obsidian.
- A* uses the same solo digging times and remaining damage fraction, instead of
  pricing a partly damaged hard wall as almost free.
- Pillars require a failed no-placement route search; stalling, a partial route, or
  an exhausted search budget does not authorize building. Known paths take priority
  immediately, and fresh route checks are required before subsequent placements.
  When a route appears, the builder cancels its remaining plan and follows it.
  Only one builder can construct a pillar for a given player at a time. Completed
  or blocked plans have a retry cooldown instead of immediately starting again.
- Builders steer beside the placement cell, climb while waiting, and use a 20-game-
  tick placement cooldown. Approaching the pillar resets the blocked timeout, and
  builder steering continues between Slow targeting updates. Debug mode now logs
  pillar start/completion/blockage events at INFO so they appear in the normal log.

The 19 additional tests cover movement ownership, water speed, preserved horizontal
climbing velocity, pillar staging and cadence, path-first fallback, shared damage
budgets, duplicate digging, melee reach and hardness-based damage rates. Fabric
initialization also passed with the new mixin hook. Restart the client to load these
code changes; movement and building have not yet been observed in a live world with
this new build.

The later live log confirmed repeated pillar starts after completions and multiple
builders targeting the same player. The five-second stall trigger was removed in
response. Route checks share the existing one-search-per-tick budget, with pending
builder checks prioritized so a mob cannot place while waiting for a search result.

## Live pursuit diagnostics and movement correction

Opt-in INFO diagnostics now record search outcomes, deferred decisions and periodic
movement snapshots, with separate global limits for events and snapshots. They do
not run additional searches or load chunks.

The 22:13 live log showed mobs 537 and 516 retaining an unfinished A* node while
vanilla navigation reported completion and horizontal motion stopped. Nearby planned
nodes now use precise movement control without another vanilla search. Distant
nodes retain navigation, with direct steering when navigation is already finished.

Climb assistance previously stopped 0.6 blocks below the requested height. It now
continues to within 0.05 blocks, and ascent nodes are not marked reached below that
height. Vertical edges no longer steer toward a later node before reaching the
current one. Airborne wall climbers can update their heading while vanilla jump
control retains its jump state. Horizontal velocity and configured speed are not
boosted.

Build and all 46 tests pass, including six new endpoint/climbing regressions. These
latest movement corrections still require a client restart and live verification.

## Final platform and physics verification

Climbers retain their target and receive movement input every AI tick, including
Slow mode. They keep wall grip while routes are replanned, finish the last fraction
of a vertical step, and cross onto solid ledges before releasing that grip. Contact
between hunting mobs no longer pushes supported climbers off the wall. Wide mobs
use clear waypoint positions around wall faces and diagonal corners.

The saved world's final cobblestone approach ended below a single-layer netherite
platform. The planner now recognizes that thin edge at feet or head height. Mobs
outside the planner's range no longer wait indefinitely for a climb route. Swimming
input also resolves conflicts with floating goals and avoids holding mobs above
the water surface.

The final Java 21 build passes 77 unit tests. All four Minecraft physics GameTests
passed in two consecutive runs, covering ten mob types in Fast mode, Slow mode,
water approaches, and a copied version of the reported platform geometry. Tests
use an isolated server world and are excluded from the production JAR. See
`src/gametest/README.md` for the command and fixture details.

The development client must be fully restarted to load the fixes. These tests do
not establish compatibility with every terrain layout or other mod.
