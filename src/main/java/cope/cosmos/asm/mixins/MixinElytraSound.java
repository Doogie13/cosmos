package cope.cosmos.asm.mixins;

import cope.cosmos.client.Cosmos;
import cope.cosmos.client.events.MicrowaveEvent;
import net.minecraft.client.audio.ElytraSound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ElytraSound.class)
public class MixinElytraSound {

    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    public void attackEntityFrom(CallbackInfo ci) {
        MicrowaveEvent microwaveEvent = new MicrowaveEvent();
        Cosmos.EVENT_BUS.post(microwaveEvent);

        if (microwaveEvent.isCanceled()) {
            ci.cancel();
        }
    }

}
