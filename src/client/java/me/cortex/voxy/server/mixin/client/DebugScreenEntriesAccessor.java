package me.cortex.voxy.server.mixin.client;

//? if HAS_DEBUG_SCREEN {
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DebugScreenEntries.class)
public interface DebugScreenEntriesAccessor {
	@Invoker("register")
	static Identifier invokeRegister(Identifier id, DebugScreenEntry entry) {
		throw new AssertionError();
	}
}
//?} else {
/*
// DebugScreenEntries does not exist before MC 1.21.11.
// Provide an empty interface so compilation succeeds on older versions.
public interface DebugScreenEntriesAccessor {
}
*///?}
