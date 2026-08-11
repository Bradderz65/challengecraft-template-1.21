package com.example.mixin;

import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Creeper.class)
public interface CreeperInvoker {
	@Invoker("setSwellDir")
	void challengemod$invokeSetSwellDir(int swellDir);
}
