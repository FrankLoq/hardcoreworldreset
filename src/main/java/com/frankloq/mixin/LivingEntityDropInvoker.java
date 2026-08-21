package com.frankloq.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface LivingEntityDropInvoker {

    @Invoker("drop")
    void hardcoreworldreset$drop(ServerWorld world, DamageSource damageSource);
}
