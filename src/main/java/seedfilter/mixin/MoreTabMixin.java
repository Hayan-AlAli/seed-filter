package seedfilter.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import seedfilter.SeedFilterMod;

/** Adds "Seed Filter…" under Game Rules / Experiments / Data Packs on Create World's More tab. */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$MoreTab")
public abstract class MoreTabMixin extends GridLayoutTab {
    private MoreTabMixin() { super(null); } // never called; mixins need a ctor to compile

    @Inject(method = "<init>", at = @At("TAIL"))
    private void seedfilter$addButton(CreateWorldScreen screen, CallbackInfo ci) {
        Button b = Button.builder(Component.literal("Seed Filter…"), btn -> SeedFilterMod.open(screen)).width(210).build();
        layout.addChild(b, 3, 0); // vanilla fills rows 0-2
        SeedFilterMod.track(screen, b);
    }
}
