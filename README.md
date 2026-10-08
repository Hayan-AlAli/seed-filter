# Seed Filter

**Pick your spawn biome, nearby structures and Nether — then press Create New World.**
Seed Filter searches thousands of seeds per second and creates the first world that matches.

> Windows · macOS · Linux (x64 and ARM64) · Minecraft 26.3 · Fabric

---

## Features

- **Spawn biome and size.** Spawn in a specific biome (or any) and choose how big it is: Small, Medium or Large.
- **Structures nearby.** Tick any of 16 structures, such as Village, Trial Chambers, Ancient City, Woodland Mansion, Ocean Monument or Stronghold. Set a maximum distance from spawn for each.
- **Biomes nearby.** Require biomes within a distance of spawn, for example "Jungle within 1000".
- **Nether.** Pick the biome a portal at spawn leads to, plus a Fortress and/or Bastion nearby. You can choose the bastion type: Housing, Hoglin Stables, Treasure or Bridge.
- **One way to create worlds.** Set the filter, change game rules or anything else as usual, and press **Create New World**. The search runs first, then your world is created with all your settings.
- **Checked in-game.** When the world opens, the mod re-checks the result with Minecraft's own world generation and tells you in chat if anything is different.
- **Hide the seed** if you'd rather not know it.
- **Helpful while searching.** Shows seeds per second and how rare your filter is. Warns you when a combination may be impossible.

## How to use

1. **Singleplayer → Create New World → More → Seed Filter…**
2. Choose what you want on the **Spawn**, **Structures**, **Nearby** and **Nether** tabs, then press **Done**.
   The button now reads **Seed Filter: On**. Hover it to see a summary.
3. Change any other settings you like, then press **Create New World**.

The filter is only on for the world you're creating. Your choices are remembered for next time, but they start switched off.

## Installation

1. Install **Fabric Loader 0.19.5+** for **Minecraft 26.3**.
2. Put **Seed Filter** and **[Fabric API](https://modrinth.com/mod/fabric-api)** in your `mods` folder.

Seed Filter is client-side. It works when creating singleplayer worlds and isn't needed on servers.

## FAQ

**How accurate is it?**
Seeds are checked with [cubiomes](https://github.com/Cubitect/cubiomes), a recreation of Minecraft's world generation. On rare occasions its prediction differs from the real game. Seed Filter then tells you in chat right after you join, so you can try again.

**Which systems are supported?**
Windows, macOS and Linux, on both x64 and ARM64 (including Apple Silicon). The fast search is written in C, and the jar contains a build for each system. The right one is picked automatically.

**Why is my search slow?**
Speed depends on your CPU (the mod uses all cores but one) and on how rare your filter is. Every condition you add makes a match rarer. Nearby biomes at large distances cost the most. If no match turns up after a minute, try fewer or closer-to-common conditions.

**Is "every structure within 500 blocks" possible?**
Practically, no. Some structures need biomes that almost never sit next to each other, like desert, jungle, snowy plains and deep ocean. Strongholds are 1,280+ blocks from the world center, so a stronghold close to spawn needs a spawn far from the center. It's possible but rare.

**Does it change world generation?**
No. It only chooses the seed. The world is pure vanilla.

## Links

- **Source code:** https://github.com/Hayan-AlAli/seed-filter
- **Report a bug or suggest a feature:** https://github.com/Hayan-AlAli/seed-filter/issues

## Verifying the binaries

The jar includes native libraries (cubiomes plus a small C wrapper, see `native/`) for each supported system.
Every file is built from this repository by [GitHub Actions](https://github.com/Hayan-AlAli/seed-filter/actions), with no files from a developer's PC, and the build is reproducible: the same commit always produces byte-identical files.

- Each run prints SHA-256 sums of the jar and every native library and signs a build provenance attestation.
- To check a downloaded jar: `gh attestation verify seedfilter-<version>.jar --repo Hayan-AlAli/seed-filter`
- Or build it yourself (`./gradlew build` with JDK 25 and Zig 0.17.0) and compare the SHA-256.

## Credits

- Seed checks use **cubiomes** by Cubitect, through the [xu-shawn fork](https://github.com/xu-shawn/cubiomes) (MIT), which adds 26.x support.
- Seed Filter is MIT licensed.
