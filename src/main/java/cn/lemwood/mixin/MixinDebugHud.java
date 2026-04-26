package cn.lemwood.mixin;

import cn.lemwood.client.ScandiumClient;
import cn.lemwood.config.ScandiumConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.DebugHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.List;

@Mixin(DebugHud.class)
public class MixinDebugHud {
    @Inject(
        method = "drawText",
        at = @At("HEAD")
    )
    private void onDrawText(DrawContext context, List<String> list, boolean left, CallbackInfo ci) {
        if (left) {
            ScandiumClient.isDebugHudOpen = true;
            list.add("");
            list.add("§b[Scandium] §fRendering Info:");
            list.add(String.format("  §7Checked: §f%d", ScandiumClient.TOTAL_CHECKED));
            list.add(String.format("  §7Culled Total: §f%d (%.1f%%)", ScandiumClient.CULLED_COUNT, (ScandiumClient.TOTAL_CHECKED > 0 ? (ScandiumClient.CULLED_COUNT * 100.0 / ScandiumClient.TOTAL_CHECKED) : 0)));
            list.add(String.format("  §7- FOV/Frustum: §f%d", ScandiumClient.CULLED_FOV));
            list.add(String.format("  §7- Occlusion: §f%d", ScandiumClient.CULLED_HORIZONTAL));
            list.add(String.format("  §7- Backface: §f%d", ScandiumClient.CULLED_BACK));
            list.add(String.format("  §7- Mountain: §f%d", ScandiumClient.CULLED_MOUNTAIN));
            list.add(String.format("  §7- Vertical: §f%d", ScandiumClient.CULLED_VERTICAL));
            
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
