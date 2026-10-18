# Changelog

## 1.1.0+26.3

- **Much faster search.** Finding the spawn point is about 10x faster, "biomes nearby" almost 2x, and Nether fortress/bastion filters about 3x. Results are exactly the same as before.
- **CPU usage** button on the search screen: Low, Balanced or Max. You can change it while searching, and it's remembered. Search threads always run at the lowest priority.
- **Warnings for filters that can't (or almost never) match**, such as a Mushroom Fields spawn, a village and outpost too close together, frozen and hot biomes side by side, or too many conditions at once. Impossible ones show in red. Nothing is blocked.
- Warns when **Generate Structures** is off but the filter asks for structures, and when added data packs may change world generation.
- A chime and a taskbar flash when a long search finds a seed.
- Click the seed in chat to copy it.

## 1.0.0+26.3 — first release

- **Seed Filter…** button on Create World's **More** tab. Set a filter, press **Create New World**, and the mod finds a matching seed first.
- **Spawn:** biome (or any) and size (Small / Medium / Large).
- **Structures:** 16 types, each with a maximum distance from spawn.
- **Nearby:** biomes that must exist within a distance of spawn.
- **Nether:** arrival biome under spawn, plus a Fortress and Bastion nearby, with bastion type (Housing, Stables, Treasure, Bridge).
- The result is re-checked with Minecraft's own world generation when the world opens, with a chat warning on any difference.
- Option to hide the seed from chat.
- The search screen shows seeds per second and how rare the filter is, and warns about stronghold distances that rarely match.
- Runs on Windows, macOS and Linux (x64 and ARM64); the right native library is picked automatically.
