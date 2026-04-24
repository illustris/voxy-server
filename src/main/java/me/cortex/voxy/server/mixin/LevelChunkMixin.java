package me.cortex.voxy.server.mixin;

import me.cortex.voxy.server.engine.ChunkTimestampStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

	@Shadow
	@Final
	Level level;

	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void onSetBlockState(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
		if (cir.getReturnValue() != null && this.level instanceof ServerLevel serverLevel) {
			// Only track block changes in fully loaded chunks (not during worldgen).
			// During chunk generation, setBlockState fires for every placed block
			// (ores, trees, features etc.) which would flood the timestamp store.
			LevelChunk self = (LevelChunk) (Object) this;
			//? if HAS_FULL_CHUNK_IS_OR_AFTER {
			if (self.getFullStatus() != null && self.getFullStatus().isOrAfter(net.minecraft.server.level.FullChunkStatus.FULL)) {
			//?} else {
			/*if (self.getFullStatus() != null) {
			*///?}
				ChunkTimestampStore.onBlockChanged(serverLevel, pos,
						cir.getReturnValue().toString(), state.toString());
			}
		}
	}
}
