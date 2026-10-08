package seedfilter;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class SearchScreen extends Screen {
    private final CreateWorldScreen world;
    private final Filter filter;
    private final Searcher searcher;

    public SearchScreen(CreateWorldScreen world, Filter filter, boolean largeBiomes) {
        super(Component.literal("Searching…"));
        this.world = world;
        this.filter = filter;
        this.searcher = new Searcher(filter.toQuery(largeBiomes),
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(width / 2 - 75, height / 2 + 30, 150, 20).build());
    }

    @Override
    public void tick() {
        if (!searcher.result().isDone()) return;
        long seed = searcher.result().join();
        world.getUiState().setSeed(Long.toString(seed));
        Verifier.expect(filter);
        minecraft.gui.setScreen(world);
        // not inside tick(): world creation ticks nested screens and Fabric's screen-tick hook NPEs on return
        minecraft.schedule(() -> SeedFilterMod.createNow(world));
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
            g.centeredText(font, w, width / 2, y += 12, 0xFFFFD040);
    }
}
