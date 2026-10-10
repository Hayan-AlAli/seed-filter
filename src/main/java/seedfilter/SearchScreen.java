package seedfilter;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.sdl.SDLVideo;

public class SearchScreen extends Screen {
    private final CreateWorldScreen world;
    private final Filter filter;
    private final Searcher searcher;
    private static final int CORES = Runtime.getRuntime().availableProcessors();
    private Searcher.Cpu cpu = Searcher.Cpu.load(SeedFilterMod.cpuFile());

    public SearchScreen(CreateWorldScreen world, Filter filter, boolean largeBiomes) {
        super(Component.literal("Searching…"));
        this.world = world;
        this.filter = filter;
        // start the most threads any level uses; the CPU button pauses or resumes them while searching
        this.searcher = new Searcher(filter.toQuery(largeBiomes), Searcher.Cpu.MAX.threads(CORES));
        searcher.setActive(cpu.threads(CORES));
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(cpuLabel(), b -> {
            cpu = cpu.next();
            cpu.save(SeedFilterMod.cpuFile());
            searcher.setActive(cpu.threads(CORES));
            b.setMessage(cpuLabel());
            b.setTooltip(cpuTooltip());
        }).bounds(width / 2 - 152, height / 2 + 30, 150, 20).tooltip(cpuTooltip()).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(width / 2 + 2, height / 2 + 30, 150, 20).build());
    }

    private Component cpuLabel() { return Component.literal("CPU usage: " + cpu.label); }

    private Tooltip cpuTooltip() {
        return Tooltip.create(Component.literal("Uses " + cpu.threads(CORES) + " of " + CORES + " CPU threads. "
                + "Low keeps other programs smooth, Max finishes fastest. Click to change, remembered for next time."));
    }

    @Override
    public void tick() {
        if (!searcher.result().isDone()) return;
        long seed = searcher.result().join();
        notifyFound();
        world.getUiState().setSeed(Long.toString(seed));
        Verifier.expect(filter);
        minecraft.gui.setScreen(world);
        // not inside tick(): world creation ticks nested screens and Fabric's screen-tick hook NPEs on return
        minecraft.schedule(() -> SeedFilterMod.createNow(world));
    }

    /** Long searches run in the background (see the CPU button): chime, and flash the taskbar if tabbed out. */
    private void notifyFound() {
        if (searcher.elapsedSeconds() >= 5)
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0F));
        if (!minecraft.getWindow().isFocused()) {
            try {
                SDLVideo.SDL_FlashWindow(minecraft.getWindow().handle(), SDLVideo.SDL_FLASH_UNTIL_FOCUSED);
            } catch (Throwable ignored) { // only a hint; not every platform supports it
            }
        }
    }

    @Override
    public void onClose() { // Esc and Cancel: back to Create World, settings and filter kept
        searcher.cancel();
        minecraft.gui.setScreen(world);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(font, title, width / 2, height / 2 - 30, 0xFFFFFFFF);
        g.centeredText(font, String.format("%,d seeds checked  ·  %,d / s", searcher.checked(), searcher.seedsPerSecond()),
                width / 2, height / 2 - 10, 0xFFA0A0A0);
        int y = height / 2 + 56;
        if (searcher.rarityBound() > 0)
            g.centeredText(font, String.format("No match yet: matches are rarer than about 1 in %,d", searcher.rarityBound()),
                    width / 2, y, 0xFFA0A0A0);
        if (searcher.elapsedSeconds() >= 60)
            g.centeredText(font, "May be extremely rare or impossible: try fewer conditions",
                    width / 2, y += 12, 0xFFFFD040);
        for (String w : filter.warnings())
            g.centeredText(font, w, width / 2, y += 12, Filter.warningColor(w));
    }
}
