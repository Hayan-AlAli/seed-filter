package seedfilter;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

public class FilterScreen extends Screen {
    private static final int ROWS = 5, ROW = 20, LIST = 68; // fits a 240 px tall GUI with tabs, pages and size row
    private static final String ANY = "";                   // "Any biome" entry in the spawn list
    private static final String DEFAULT_DIST = "500", DEFAULT_NETHER_DIST = "200";

    private enum Tab {
        SPAWN("Spawn"), STRUCTURES("Structures"), NEARBY("Nearby"), NETHER("Nether");
        final String label;
        Tab(String label) { this.label = label; }
    }

    private final CreateWorldScreen world;
    private Tab tab = Tab.SPAWN;
    private String spawnBiome;                                        // null = any
    private Native.Size spawnSize;
    private boolean hideSeed;                                         // display preference, kept by Clear
    private String netherBiome;                                       // null = any
    private String fortress, bastion;                                 // distance text, null = rule off
    private Native.Bastion bastionType = Native.Bastion.ANY;
    private final Map<String, String> structures = new LinkedHashMap<>(); // ticked id -> distance text
    private final Map<String, String> nearby = new LinkedHashMap<>();     // ticked biome -> distance text
    private final EnumMap<Tab, EditBox> searches = new EnumMap<>(Tab.class); // kept across rebuildWidgets()
    private final EnumMap<Tab, Integer> pages = new EnumMap<>(Tab.class);
    private String pageLabel = "";

    public FilterScreen(CreateWorldScreen world) {
        super(Component.literal("Seed Filter"));
        this.world = world;
        Filter saved = Filter.load(SeedFilterMod.filterFile());
        spawnBiome = saved.spawnBiome();
        spawnSize = saved.spawnSize();
        hideSeed = saved.hideSeed();
        netherBiome = saved.netherBiome();
        fortress = saved.fortressDist() > 0 ? Integer.toString(saved.fortressDist()) : null;
        bastion = saved.bastionDist() > 0 ? Integer.toString(saved.bastionDist()) : null;
        bastionType = saved.bastionType();
        saved.rules().forEach(r -> structures.put(r.structure(), Integer.toString(r.maxDist())));
        saved.nearby().forEach(n -> nearby.put(n.biome(), Integer.toString(n.maxDist())));
    }

    static String label(String biome) { return I18n.get("biome.minecraft." + biome); }

    private static String spawnLabel(String b) { return b.equals(ANY) ? "Any biome" : label(b); }

    static String structureLabel(String id) { return Ids.structure(id).map(Ids.Structure::label).orElse(id); }

    /** Selected biomes first (so they can always be unticked), then unselected matches of {@code query}, by label. */
    private static List<String> listing(Collection<String> selected, Collection<String> all, Function<String, String> label, String query) {
        String q = query.toLowerCase(Locale.ROOT);
        return Stream.concat(selected.stream(), all.stream()
                        .filter(b -> !selected.contains(b))
                        .filter(b -> label.apply(b).toLowerCase(Locale.ROOT).contains(q) || b.contains(q))
                        .sorted(Comparator.comparing(label)))
                .toList();
    }

    static List<String> page(Collection<String> selected, Collection<String> all, Function<String, String> label, String query, int page, int rows) {
        List<String> l = listing(selected, all, label, query);
        int from = page * rows;
        return from >= l.size() ? List.of() : l.subList(from, Math.min(l.size(), from + rows));
    }

    static int pageCount(Collection<String> selected, Collection<String> all, Function<String, String> label, String query, int rows) {
        return Math.max(1, Math.ceilDiv(listing(selected, all, label, query).size(), rows));
    }

    @Override
    protected void init() {
        int x0 = width / 2 - 150;
        for (Tab t : Tab.values())
            addRenderableWidget(Button.builder(Component.literal(t.label), b -> { tab = t; rebuildWidgets(); })
                    .bounds(width / 2 - 152 + t.ordinal() * 76, 24, 74, 20).build()).active = t != tab;

        if (tab == Tab.NETHER) netherTab(x0);
        else listTab(x0);

        addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
            spawnBiome = null;
            spawnSize = Native.Size.ANY;
            structures.clear();
            nearby.clear();
            netherBiome = null;
            fortress = bastion = null;
            bastionType = Native.Bastion.ANY;
            rebuildWidgets();
        }).bounds(width / 2 - 154, height - 28, 100, 20).build());
        addRenderableWidget(Checkbox.builder(Component.literal("Show seed"), font).pos(width / 2 - 44, height - 26)
                .selected(!hideSeed)
                .onValueChange((c, on) -> hideSeed = !on)
                .tooltip(Tooltip.create(Component.literal("Print the found seed in chat when the world opens")))
                .build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 + 54, height - 28, 100, 20).build());
    }

    private void listTab(int x0) {
        EditBox search = searches.computeIfAbsent(tab, t -> {
            EditBox e = new EditBox(font, 0, 0, 300, 14, Component.literal("Search"));
            e.setHint(Component.literal(t == Tab.STRUCTURES ? "search structures…" : "search biomes…"));
            e.setResponder(s -> { pages.put(t, 0); rebuildWidgets(); });
            return e;
        });
        search.setPosition(x0, 50);
        addRenderableWidget(search);
        setInitialFocus(search);

        switch (tab) {
            case SPAWN -> spawnTab(x0, search.getValue());
            case STRUCTURES -> distanceTab(x0, search.getValue(), structures,
                    Ids.structures().stream().map(Ids.Structure::id).toList(), FilterScreen::structureLabel, Native.MAX_RULES);
            case NEARBY -> distanceTab(x0, search.getValue(), nearby, Ids.biomes().keySet(), FilterScreen::label, Native.MAX_NEAR);
            case NETHER -> { }
        }
    }

    private void netherTab(int x0) {
        pageLabel = "";
        List<String> options = new ArrayList<>();
        options.add(ANY);
        options.addAll(Ids.netherBiomes().keySet());
        String chosen = netherBiome == null ? ANY : netherBiome;
        for (int i = 0; i < options.size(); i++) {
            String b = options.get(i);
            addRenderableWidget(Button.builder(choice(b.equals(ANY) ? "Any" : label(b), b.equals(chosen)), btn -> {
                netherBiome = b.equals(ANY) ? null : b;
                rebuildWidgets();
            }).bounds(x0 + (i % 3) * 101, 60 + (i / 3) * 20, 98, 18).build());
        }
        netherRule(x0, 108, "Fortress within", fortress, v -> fortress = v);
        netherRule(x0, 132, "Bastion within", bastion, v -> bastion = v);
        if (bastion != null)
            for (Native.Bastion t : Native.Bastion.values())
                addRenderableWidget(Button.builder(choice(bastionLabel(t), t == bastionType), btn -> { bastionType = t; rebuildWidgets(); })
                        .bounds(x0 + 50 + t.ordinal() * 50, 156, 48, 18).build());
    }

    /** Checkbox plus distance box; {@code dist} is null while the rule is off. */
    private void netherRule(int x0, int y, String label, String dist, Consumer<String> set) {
        addRenderableWidget(Checkbox.builder(Component.literal(label), font).pos(x0, y).selected(dist != null)
                .onValueChange((c, on) -> { set.accept(on ? DEFAULT_NETHER_DIST : null); rebuildWidgets(); })
                .build());
        if (dist != null) {
            EditBox d = new EditBox(font, x0 + 256, y, 44, 18, Component.literal("Max distance"));
            d.setMaxLength(4);
            d.setValue(dist);
            d.setResponder(s -> set.accept(s.replaceAll("\\D", ""))); // 26.3 EditBox has no input filter
            d.setTooltip(Tooltip.create(Component.literal("Nether blocks from where a portal at spawn leads, 50–1000")));
            addRenderableWidget(d);
        }
    }

    /** Chosen option: green underlined (same width as the plain label, readable without colour too). */
    private static Component choice(String text, boolean chosen) {
        return chosen ? Component.literal(text).withStyle(ChatFormatting.GREEN, ChatFormatting.UNDERLINE) : Component.literal(text);
    }

    private static String bastionLabel(Native.Bastion t) {
        return switch (t) { case ANY -> "Any"; case HOUSING -> "Housing"; case STABLES -> "Stables"; case TREASURE -> "Treasure"; case BRIDGE -> "Bridge"; };
    }

    private static int netherDist(String s) {
        return s == null ? 0 : s.isEmpty() ? Native.MIN_DIST : Integer.parseInt(s);
    }

    /** Adds ◀ ▶ for the current tab when needed and returns the visible slice. */
    private List<String> visible(int x0, Collection<String> pinned, Collection<String> all, Function<String, String> label, String query) {
        int n = pageCount(pinned, all, label, query, ROWS);
        int p = Math.clamp(pages.getOrDefault(tab, 0), 0, n - 1);
        pages.put(tab, p);
        pageLabel = n > 1 ? (p + 1) + "/" + n : "";
        if (n > 1) {
            int y = LIST + ROWS * ROW;
            addRenderableWidget(Button.builder(Component.literal("◀"), b -> { pages.put(tab, p - 1); rebuildWidgets(); })
                    .bounds(x0, y, 20, 16).build()).active = p > 0;
            addRenderableWidget(Button.builder(Component.literal("▶"), b -> { pages.put(tab, p + 1); rebuildWidgets(); })
                    .bounds(x0 + 80, y, 20, 16).build()).active = p < n - 1;
        }
        return page(pinned, all, label, query, p, ROWS);
    }

    private void spawnTab(int x0, String query) {
        List<String> pinned = spawnBiome == null ? List.of(ANY) : List.of(ANY, spawnBiome);
        List<String> rows = visible(x0, pinned, Ids.biomes().keySet(), FilterScreen::spawnLabel, query);
        String chosen = spawnBiome == null ? ANY : spawnBiome;
        for (int i = 0; i < rows.size(); i++) {
            String b = rows.get(i);
            addRenderableWidget(Button.builder(Component.literal((b.equals(chosen) ? "● " : "○ ") + spawnLabel(b)), btn -> {
                spawnBiome = b.equals(ANY) ? null : b;
                rebuildWidgets();
            }).bounds(x0, LIST + i * ROW, 300, 18).build());
        }
        int y = LIST + ROWS * ROW + 20;
        for (Native.Size s : Native.Size.values()) {
            Button sb = addRenderableWidget(Button.builder(choice(sizeLabel(s), s == spawnSize), btn -> { spawnSize = s; rebuildWidgets(); })
                    .bounds(x0 + 60 + s.ordinal() * 60, y, 58, 18).build());
            sb.setTooltip(Tooltip.create(Component.literal(sizeHint(s))));
        }
    }

    private static String sizeLabel(Native.Size s) {
        return switch (s) { case ANY -> "Any"; case SMALL -> "Small"; case MEDIUM -> "Medium"; case LARGE -> "Large"; };
    }

    private static String sizeHint(Native.Size s) {
        return switch (s) {
            case ANY -> "Any size";
            case SMALL -> "Patch under 250×250 blocks";
            case MEDIUM -> "Patch 250×250 to 600×600 blocks";
            case LARGE -> "Patch over 600×600 blocks";
        };
    }

    private void distanceTab(int x0, String query, Map<String, String> picked, Collection<String> all,
                             Function<String, String> label, int limit) {
        List<String> rows = visible(x0, picked.keySet(), all, label, query);
        for (int i = 0; i < rows.size(); i++) {
            String id = rows.get(i);
            int y = LIST + i * ROW;
            Checkbox cb = addRenderableWidget(Checkbox.builder(Component.literal(label.apply(id)), font).pos(x0, y)
                    .selected(picked.containsKey(id))
                    .onValueChange((c, on) -> { if (on) picked.put(id, DEFAULT_DIST); else picked.remove(id); rebuildWidgets(); })
                    .build());
            cb.active = picked.containsKey(id) || picked.size() < limit;
            if (picked.containsKey(id)) {
                EditBox d = new EditBox(font, x0 + 256, y, 44, 18, Component.literal("Max distance"));
                d.setMaxLength(4);
                d.setValue(picked.get(id));
                d.setResponder(s -> picked.put(id, s.replaceAll("\\D", ""))); // 26.3 EditBox has no input filter
                d.setTooltip(Tooltip.create(Component.literal("Max distance from spawn, 50–3000 blocks")));
                addRenderableWidget(d);
            }
        }
    }

    private static <T> List<T> toList(Map<String, String> m, BiFunction<String, Integer, T> make) {
        return m.entrySet().stream()
                .map(e -> make.apply(e.getKey(), e.getValue().isEmpty() ? Native.MIN_DIST : Integer.parseInt(e.getValue())))
                .toList();
    }

    private Filter currentFilter() {
        return new Filter(spawnBiome, spawnSize, toList(structures, Filter.Rule::new), toList(nearby, Filter.Near::new), hideSeed,
                netherBiome, netherDist(fortress), netherDist(bastion), bastionType)
                .sanitize(); // clamps distances to 50..3000
    }

    /** Done and Esc: save, turn the filter on for this world (off if empty), back to Create World. */
    @Override
    public void onClose() {
        Filter f = currentFilter();
        f.save(SeedFilterMod.filterFile());
        SeedFilterMod.arm(world, f);
        minecraft.gui.setScreen(world);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int x0 = width / 2 - 150, below = LIST + ROWS * ROW;
        g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
        if (tab == Tab.NETHER) {
            g.text(font, "Arrival biome (where a portal built at spawn leads):", x0, 48, 0xFFA0A0A0);
            if (bastion != null) g.text(font, "Type:", x0 + 10, 161, 0xFFA0A0A0);
        }
        g.text(font, pageLabel, x0 + 32, below + 4, 0xFFA0A0A0);
        List<String> warnings = tab == Tab.STRUCTURES ? currentFilter().warnings() : List.of();
        if (!warnings.isEmpty()) g.centeredText(font, warnings.getFirst(), width / 2, below + 25, 0xFFFFD040);
        else g.text(font, switch (tab) {
            case SPAWN -> "Size:";
            case STRUCTURES -> "All ticked must be within their distance";
            case NEARBY -> "All ticked must exist within their distance";
            case NETHER -> "Measured in the Nether from where a portal at spawn leads";
        }, x0, below + 25, 0xFFA0A0A0);
    }
}
