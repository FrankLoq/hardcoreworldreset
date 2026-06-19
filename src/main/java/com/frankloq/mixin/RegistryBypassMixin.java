package com.frankloq.mixin;

import com.mojang.serialization.Lifecycle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(net.minecraft.registry.SimpleRegistry.class)
public class RegistryBypassMixin {

    @Inject(method = "getLifecycle", at = @At("HEAD"), cancellable = true)
    private void forceStableLifecycle(CallbackInfoReturnable<Lifecycle> cir) {
        cir.setReturnValue(Lifecycle.stable());
    }
}