package cn.lemwood.mixin;

import cn.lemwood.client.ScandiumClient;
import cn.lemwood.config.ScandiumConfig;
import net.minecraft.client.gui.hud.DebugHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(DebugHud.class)
public class MixinDebugHud {

    @Inject(method = "getLeftText()Ljava/util/List;", at = @At("RETURN"))
    private void onGetLeftText(CallbackInfoReturnable<List<String>> cir) {
        if (ScandiumConfig.getInstance().debugMode) {
            List<String> list = cir.getReturnValue();
            ScandiumConfig config = ScandiumConfig.getInstance();
            
            list.add("");
            list.add("\u00A7d[Scandium] \u00A7fCulling: " + 
                     "\u00A7dFOV: \u00A7f" + ScandiumClient.CULLED_FOV + 
                     " | \u00A7dVert: \u00A7f" + ScandiumClient.CULLED_VERTICAL + 
                     " | \u00A7dMtn: \u00A7f" + ScandiumClient.CULLED_MOUNTAIN +
                     " | \u00A7dHoriz: \u00A7f" + ScandiumClient.CULLED_HORIZONTAL +
                     " | \u00A7dTotal: \u00A7f" + ScandiumClient.TOTAL_CHECKED);
            
            list.add("\u00A7d[Scandium] \u00A7fEnvironment: " + 
                     "\u00A7dSurfaceY: \u00A7f" + ScandiumClient.debugCachedSurfaceY + 
                     " | \u00A7dUnderground: \u00A7f" + (ScandiumClient.debugCachedUnderground ? "\u00A7ayes" : "\u00A7cno"));

            StringBuilder options = new StringBuilder("\u00A7d[Scandium] \u00A7fOptions: ");
            options.append("\u00A7dFOV: ").append(config.fovCullingEnabled ? "\u00A7aON" : "\u00A7cOFF");
            options.append(" | \u00A7dMtn: ").append(config.aggressiveMountainCulling ? "\u00A7aON" : "\u00A7cOFF");
            options.append(" | \u00A7dVert: ").append(config.aggressiveVerticalCulling ? "\u00A7aON" : "\u00A7cOFF");
            options.append(" | \u00A7dTransp: ").append(config.transparencyAwareness ? "\u00A7aON" : "\u00A7cOFF");
            list.add(options.toString());
            
            // 每一帧渲染后重置 HUD 计数器
            ScandiumClient.TOTAL_CHECKED = 0;
            ScandiumClient.CULLED_FOV = 0;
            ScandiumClient.CULLED_VERTICAL = 0;
            ScandiumClient.CULLED_MOUNTAIN = 0;
            ScandiumClient.CULLED_HORIZONTAL = 0;
            ScandiumClient.CULLED_COUNT = 0;
            ScandiumClient.CULLED_BACK = 0;
        }
    }
}
