package seedfilter;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import seedfilter.mixin.CreateWorldScreenInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

public class SeedFilterMod implements ClientModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("seedfilter");

    @Override
    public void onInitializeClient() {
        loadNative();
        Verifier.register();
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (screen instanceof CreateWorldScreen s && BUTTONS.get(s) instanceof Button b)
                ScreenEvents.afterTick(s).register(x -> update(s, b));
        });
    }

    public static Path filterFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("seedfilter.json");
    }

    /** Empty if the chosen world type can't be modelled by cubiomes; otherwise whether it is Large Biomes. */
    public static Optional<Boolean> largeBiomes(CreateWorldScreen s) {
        Holder<WorldPreset> p = s.getUiState().getWorldType().preset();
        if (p == null) return Optional.empty();
        if (p.is(WorldPresets.NORMAL)) return Optional.of(false);
        if (p.is(WorldPresets.LARGE_BIOMES)) return Optional.of(true);
        return Optional.empty();
    }

    /** Null when Seed Filter can run for this screen's current settings, else why not. */
    public static String disabledReason(CreateWorldScreen s) {
        if (!Native.isAvailable()) return "Seed Filter's search library could not load on this system (see logs/latest.log)";
        if (largeBiomes(s).isEmpty()) return "Only Default / Large Biomes worlds";
        return null;
    }

    public static void open(CreateWorldScreen s) {
        if (disabledReason(s) == null) Minecraft.getInstance().gui.setScreen(new FilterScreen(s));
    }

    // A filter is "on" only for the Create World screen it was set on, so later worlds aren't filtered by surprise.
    private static final Map<CreateWorldScreen, Filter> ARMED = new WeakHashMap<>();
    private static final Set<CreateWorldScreen> BYPASS = Collections.newSetFromMap(new WeakHashMap<>());

    /** Turns the filter on for this screen, or off when it asks for nothing. */
    public static void arm(CreateWorldScreen s, Filter f) {
        if (f.isEmpty()) ARMED.remove(s);
        else ARMED.put(s, f);
    }

    /** Head of CreateWorldScreen.onCreate. True = search first, so skip vanilla creation for now. */
    public static boolean interceptCreate(CreateWorldScreen s) {
        if (BYPASS.remove(s)) return false;
        Filter f = ARMED.get(s);
        if (f == null || disabledReason(s) != null) return false; // off, or e.g. Superflat: plain vanilla creation
        Minecraft.getInstance().gui.setScreen(new SearchScreen(s, f, largeBiomes(s).orElseThrow()));
        return true;
    }

    /** Vanilla creation with the found seed, once, past the interception. */
    public static void createNow(CreateWorldScreen s) {
        BYPASS.add(s);
        ((CreateWorldScreenInvoker) s).seedfilter$onCreate();
    }

    // MoreTab is only built on Create World's first init, but Fabric drops a screen's tick listeners on every
    // init/resize, so the button is remembered here and its listener re-added after each init.
    private static final Map<CreateWorldScreen, Button> BUTTONS = new WeakHashMap<>();

    /** Called by MoreTabMixin when it adds the button. */
    public static void track(CreateWorldScreen s, Button b) {
        BUTTONS.put(s, b);
        update(s, b);
    }

    /** World type can change on the World tab without re-init, so re-evaluate every tick. */
    private static void update(CreateWorldScreen s, Button b) {
        String why = disabledReason(s);
        Filter f = ARMED.get(s);
        boolean on = why == null && f != null;
        b.active = why == null;
        b.setMessage(Component.literal(on ? "Seed Filter: On" : "Seed Filter…"));
        String tip = why != null ? why
                : on ? f.summary(FilterScreen::label, FilterScreen::structureLabel)
                        + "\nSearches for a seed when you press Create New World (replaces the seed field)"
                : "Pick a spawn biome, structures and nearby biomes";
        b.setTooltip(Tooltip.create(Component.literal(tip)));
    }

    private static void loadNative() {
        String os = System.getProperty("os.name"), arch = System.getProperty("os.arch");
        Optional<String> resource = Native.resourceFor(os, arch);
        if (resource.isEmpty()) {
            Native.fail("no native build for " + os + "/" + arch);
            LOG.warn("Seed Filter disabled: {}", Native.error());
            return;
        }
        try (InputStream in = SeedFilterMod.class.getResourceAsStream("/" + resource.get())) {
            byte[] bytes = in.readAllBytes();
            // config/seedfilter/<os-arch>/<library>
            Path dll = FabricLoader.getInstance().getConfigDir().resolve("seedfilter").resolve(resource.get().substring("natives/".length()));
            Files.createDirectories(dll.getParent());
            if (!Files.exists(dll) || !Arrays.equals(Files.readAllBytes(dll), bytes)) Files.write(dll, bytes);
            Native.load(dll);
        } catch (Exception e) {
            Native.fail(e.toString()); // e.g. DLL locked by a second running game with an older copy
        }
        if (!Native.isAvailable()) LOG.error("Seed Filter native library failed to load: {}", Native.error());
    }
}
