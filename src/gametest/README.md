# Movement integration tests

With Java 21 configured, run `./gradlew -PgameTests build` to run the unit tests,
Minecraft GameTests, and the mod build. GameTests use `build/gametest-runtime`;
they do not open the development client's saved worlds. Test code is excluded
from the published mod JAR and normal client runs.

The four tests spawn zombies, skeletons, creepers, spiders, cows, pigs, sheep,
chickens, rabbits, and turtles together. Every mob must climb and land on the
platform within 1,200 ticks. Three tests cover a 20-block tower in Fast mode,
Slow mode, and approaching from water. The fourth reproduces the live world's
cobblestone column and single-layer netherite platform, including the air gap
at the final edge. They exercise real AI, mixins, gravity, collisions,
and shared routes, with terrain modification disabled. Player and mob damage
is disabled in these fixtures so combat does not decide the result.

The empty `climbing_tower.nbt` template is 12 by 32 by 12 blocks. Its height
keeps the test enclosure clear of the platform. The test creates its own floor,
tower and platform, leaving a climbable west face.

`live_platform_gap.nbt` copies only the blocks around the reported final edge,
with an added bedrock floor for spawning. Its template origin corresponds to
world coordinates (97, 104, 243). It contains no saved entities or player data.
