package com.frankloq.mixin;

import com.frankloq.reset.WorldResetManager;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.PlayerManager;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

@Mixin(PlayerManager.class)
public class PlayerLoginMixin {

     // Intercepts the login sequence.
     // If the world is actively resetting, this kicks the player at the connection screen.
    @Inject(method = "checkCanJoin", at = @At("HEAD"), cancellable = true)
    private void preventJoinDuringReset(SocketAddress address, GameProfile profile, CallbackInfoReturnable<Text> cir) {
        if (WorldResetManager.isResetting()) {
            cir.setReturnValue(Text.literal("§cThe world is currently resetting.\n§fPlease wait a few seconds and try again."));
        }
    }
}