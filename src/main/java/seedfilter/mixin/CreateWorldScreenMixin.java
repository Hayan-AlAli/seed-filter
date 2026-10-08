package seedfilter.mixin;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import seedfilter.SeedFilterMod;

/** "Create New World" searches for a seed first while a filter is on for this screen. */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {
    @Inject(method = "onCreate", at = @At("HEAD"), cancellable = true)
    private void seedfilter$searchFirst(CallbackInfo ci) {
        if (SeedFilterMod.interceptCreate((CreateWorldScreen) (Object) this)) ci.cancel();
    }
}
