package seedfilter;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** After the first join of a filtered world, re-check the prediction with real game code. */
public final class Verifier {
    private static final int TOLERANCE = 32; // cubiomes spawn is approximate
    private static volatile Filter pending;

    private Verifier() {}

    public static void expect(Filter f) { pending = f; }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            Filter f = pending;
            if (f == null) return;
            pending = null;
            ServerPlayer player = handler.getPlayer();
            ServerLevel level = (ServerLevel) player.level();
            if (!f.hideSeed()) {
                String seed = Long.toString(level.getSeed());
                player.sendSystemMessage(Component.literal("Seed Filter: seed ").withStyle(ChatFormatting.GREEN)
                        .append(Component.literal("[" + seed + "]").withStyle(s -> s.withColor(ChatFormatting.AQUA)
                                .withClickEvent(new ClickEvent.CopyToClipboard(seed))
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy"))))));
            }
            // world spawn, not where the player landed: size samples on a 16-block grid from this exact point
            for (String p : check(level, level.getRespawnData().pos(), f))
                player.sendSystemMessage(Component.literal("Seed Filter: " + p).withStyle(ChatFormatting.YELLOW));
        });
    }

    static List<String> check(ServerLevel level, BlockPos spawn, Filter f) {
        List<String> problems = new ArrayList<>();
        String biome = level.getBiome(spawn).getRegisteredName(); // block-precise, like cubiomes' scale-1 spawn check
        String path = biome.substring(biome.indexOf(':') + 1);
        if (f.spawnBiome() != null && !f.spawnBiome().equals(path))
            problems.add("cubiomes predicted spawn biome " + f.spawnBiome() + " but the world has " + path);
        if (f.spawnSize() != Native.Size.ANY) {
            Native.Size got = patchSize(level, spawn.getX(), spawn.getZ(), path);
            if (got != f.spawnSize()) problems.add("cubiomes predicted a " + f.spawnSize() + " spawn biome but it measures " + got);
        }

        Registry<Structure> reg = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        for (Filter.Rule r : f.rules()) {
            Ids.Structure s = Ids.structure(r.structure()).orElseThrow();
            Optional<HolderSet<Structure>> set = resolve(reg, s.locate());
            if (set.isEmpty()) {
                SeedFilterMod.LOG.warn("Cannot verify {}: {} not in registry", s.id(), s.locate());
                problems.add("could not check " + s.label() + " (" + s.locate() + " is not in this game version)");
                continue;
            }
            if (!anyWithin(level, set.get(), spawn, r.maxDist() + TOLERANCE))
                problems.add("cubiomes predicted " + s.label() + " within " + r.maxDist() + " but the game has none that close");
        }
        checkNether(level, spawn, f, problems);
        for (Filter.Near n : f.nearby())
            if (!biomeNear(level, n.biome(), spawn.getX(), spawn.getZ(), n.maxDist() + TOLERANCE))
                problems.add("cubiomes predicted " + n.biome() + " within " + n.maxDist() + " but the game has none that close");
        return problems;
    }

    private static final int SURFACE_Y = 256, CELL = 16, PATCH_R = 64, NEAR_STEP = 32; // mirror seedfilter.c

    private static String surfaceBiome(ServerLevel level, int x, int z) {
        String n = level.getUncachedNoiseBiome(x >> 2, SURFACE_Y >> 2, z >> 2).getRegisteredName();
        return n.substring(n.indexOf(':') + 1);
    }

    // ponytail: a patch near a size threshold can still disagree if cubiomes' spawn differs from the real one
    static Native.Size patchSize(ServerLevel level, int sx, int sz, String biome) {
        // spawn's block biome can differ from its 4x4 cell's; start from the first cell within 4 blocks that matches
        int[][] origins = {{0, 0}, {4, 0}, {-4, 0}, {0, 4}, {0, -4}, {4, 4}, {4, -4}, {-4, 4}, {-4, -4}};
        int[] o = Arrays.stream(origins).filter(d -> surfaceBiome(level, sx + d[0], sz + d[1]).equals(biome)).findFirst().orElse(null);
        if (o == null) return Native.Size.SMALL;
        return fill(level, sx + o[0], sz + o[1], biome);
    }

    private static Native.Size fill(ServerLevel level, int sx, int sz, String biome) {
        int w = 2 * PATCH_R + 1, cells = 0;
        boolean[] seen = new boolean[w * w];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        seen[PATCH_R * w + PATCH_R] = true;
        queue.add(new int[]{0, 0});
        while (!queue.isEmpty() && (long) cells * CELL * CELL <= 600L * 600) {
            int[] c = queue.poll();
            if (!surfaceBiome(level, sx + c[0] * CELL, sz + c[1] * CELL).equals(biome)) continue;
            cells++;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = c[0] + d[0], nz = c[1] + d[1];
                if (Math.abs(nx) > PATCH_R || Math.abs(nz) > PATCH_R) continue;
                int j = (nz + PATCH_R) * w + nx + PATCH_R;
                if (!seen[j]) { seen[j] = true; queue.add(new int[]{nx, nz}); }
            }
        }
        long area = (long) cells * CELL * CELL;
        return area > 600L * 600 ? Native.Size.LARGE : area >= 250L * 250 ? Native.Size.MEDIUM : Native.Size.SMALL;
    }

    static boolean biomeNear(ServerLevel level, String biome, int sx, int sz, int r) {
        long r2 = (long) r * r;
        for (int ring = 0; ring <= r / NEAR_STEP; ring++)
            for (int i = -ring; i <= ring; i++)
                for (int j = -ring; j <= ring; j++) {
                    if (Math.abs(i) != ring && Math.abs(j) != ring) continue; // ring perimeter only
                    long dx = (long) i * NEAR_STEP, dz = (long) j * NEAR_STEP;
                    if (dx * dx + dz * dz <= r2 && surfaceBiome(level, sx + (int) dx, sz + (int) dz).equals(biome)) return true;
                }
        return false;
    }

    /**
     * Exhaustive: every placement region overlapping the circle. Not findNearestMapStructure, which stops at the
     * first ring of regions with any hit and so can miss a closer structure in a neighbouring region.
     */
    private static final int NETHER_TOLERANCE = 8;
    private static final Map<Native.Bastion, String> BASTION_PIECE = Map.of(
            Native.Bastion.HOUSING, "bastion/units", Native.Bastion.STABLES, "bastion/hoglin_stable",
            Native.Bastion.TREASURE, "bastion/treasure", Native.Bastion.BRIDGE, "bastion/bridge");

    private static void checkNether(ServerLevel level, BlockPos spawn, Filter f, List<String> problems) {
        if (f.netherBiome() == null && f.fortressDist() <= 0 && f.bastionDist() <= 0) return;
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            problems.add("could not check the Nether (dimension missing)");
            return;
        }
        BlockPos np = new BlockPos(Math.floorDiv(spawn.getX(), 8), 64, Math.floorDiv(spawn.getZ(), 8));
        if (f.netherBiome() != null) {
            String n = nether.getBiome(np).getRegisteredName();
            String got = n.substring(n.indexOf(':') + 1);
            if (!got.equals(f.netherBiome()))
                problems.add("cubiomes predicted Nether arrival biome " + f.netherBiome() + " but it is " + got);
        }
        Registry<Structure> reg = nether.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        if (f.fortressDist() > 0 && !resolve(reg, "minecraft:fortress")
                .map(set -> anyWithin(nether, set, np, f.fortressDist() + NETHER_TOLERANCE, null)).orElse(false))
            problems.add("cubiomes predicted a Nether fortress within " + f.fortressDist() + " but the game has none that close");
        if (f.bastionDist() > 0) {
            Native.Bastion t = f.bastionType();
            Predicate<StructureStart> type = t == Native.Bastion.ANY ? null
                    : start -> start.getPieces().getFirst().toString().contains(BASTION_PIECE.get(t));
            if (!resolve(reg, "minecraft:bastion_remnant").map(set -> anyWithin(nether, set, np, f.bastionDist() + NETHER_TOLERANCE, type)).orElse(false))
                problems.add("cubiomes predicted a " + (t == Native.Bastion.ANY ? "" : t.name().toLowerCase(Locale.ROOT) + " ")
                        + "bastion within " + f.bastionDist() + " but the game has none that close");
        }
    }

    static boolean anyWithin(ServerLevel level, HolderSet<Structure> set, BlockPos spawn, int maxDist) {
        return anyWithin(level, set, spawn, maxDist, null);
    }

    /** {@code accept}, when non-null, must also hold for the structure's start (e.g. bastion type). */
    static boolean anyWithin(ServerLevel level, HolderSet<Structure> set, BlockPos spawn, int maxDist, Predicate<StructureStart> accept) {
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        long r2 = (long) maxDist * maxDist;
        for (Holder<Structure> s : set)
            for (StructurePlacement p : state.getPlacementsForStructure(s))
                for (ChunkPos cp : candidates(state, p, spawn, maxDist)) {
                    BlockPos at = p.getLocatePos(cp);
                    long dx = at.getX() - spawn.getX(), dz = at.getZ() - spawn.getZ();
                    if (dx * dx + dz * dz <= r2 && exists(level, s.value(), p, cp, accept)) return true;
                }
        return false;
    }

    private static List<ChunkPos> candidates(ChunkGeneratorStructureState state, StructurePlacement p, BlockPos spawn, int maxDist) {
        if (p instanceof ConcentricRingsStructurePlacement rings) {
            state.ensureStructuresGenerated();
            List<ChunkPos> ring = state.getRingPositionsFor(rings);
            return ring == null ? List.of() : ring;
        }
        if (!(p instanceof RandomSpreadStructurePlacement rs)) return List.of();
        int sp = rs.spacing();
        int x0 = Math.floorDiv((spawn.getX() - maxDist) >> 4, sp), x1 = Math.floorDiv((spawn.getX() + maxDist) >> 4, sp);
        int z0 = Math.floorDiv((spawn.getZ() - maxDist) >> 4, sp), z1 = Math.floorDiv((spawn.getZ() + maxDist) >> 4, sp);
        List<ChunkPos> out = new ArrayList<>();
        for (int rx = x0; rx <= x1; rx++)
            for (int rz = z0; rz <= z1; rz++) {
                ChunkPos c = rs.getPotentialStructureChunk(state.getLevelSeed(), rx * sp, rz * sp);
                if (p.isStructureChunk(state, c.x(), c.z())) out.add(c);
            }
        return out;
    }

    private static boolean exists(ServerLevel level, Structure s, StructurePlacement p, ChunkPos cp, Predicate<StructureStart> accept) {
        StructureCheckResult r = level.structureManager().checkStructurePresence(cp, s, p, false);
        if (r == StructureCheckResult.START_NOT_PRESENT) return false;
        if (r == StructureCheckResult.START_PRESENT && accept == null) return true;
        StructureStart start = level.structureManager().getStartForStructure(s, level.getChunk(cp.x(), cp.z(), ChunkStatus.STRUCTURE_STARTS));
        return start != null && start.isValid() && (accept == null || accept.test(start));
    }

    private static Optional<HolderSet<Structure>> resolve(Registry<Structure> reg, String locate) {
        if (locate.startsWith("#"))
            return reg.get(TagKey.create(Registries.STRUCTURE, Identifier.parse(locate.substring(1)))).map(t -> t);
        return reg.get(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(locate))).map(h -> HolderSet.direct(h));
    }
}
