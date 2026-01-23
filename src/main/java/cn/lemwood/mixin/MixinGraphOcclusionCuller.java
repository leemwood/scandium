package cn.lemwood.mixin;

import cn.lemwood.client.ScandiumClient;
import cn.lemwood.client.util.CullingUtils;
import cn.lemwood.config.ScandiumConfig;
import net.minecraft.util.math.Box;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller", remap = false)
public class MixinGraphOcclusionCuller {

    @Inject(method = "isWithinFrustum", at = @At("HEAD"), cancellable = true)
    private static void onIsWithinFrustum(net.caffeinemc.mods.sodium.client.render.viewport.Viewport viewport, net.caffeinemc.mods.sodium.client.render.chunk.RenderSection section, CallbackInfoReturnable<Boolean> cir) {
        if (ScandiumClient.isRenderingShadowPass()) return;

        int x = section.getChunkX();
        int y = section.getChunkY();
        int z = section.getChunkZ();

        double minX = x * 16.0;
        double minY = y * 16.0;
        double minZ = z * 16.0;
        Box box = new Box(minX, minY, minZ, minX + 16.0, minY + 16.0, minZ + 16.0);

        if (CullingUtils.shouldCull(box, x, y, z)) {
            cir.setReturnValue(false);
        }
    }
}
