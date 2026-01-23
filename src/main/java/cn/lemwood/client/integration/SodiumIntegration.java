package cn.lemwood.client.integration;

import cn.lemwood.config.ScandiumConfig;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class SodiumIntegration implements ConfigEntryPoint {

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        ScandiumConfig config = ScandiumConfig.getInstance();

        OptionPageBuilder page = builder.createOptionPage();
        page.setName(Text.translatable("scandium.options.title"));

        // General Group
        OptionGroupBuilder general = builder.createOptionGroup();
        general.setName(Text.literal("General"));

        general.addOption(builder.createBooleanOption(Identifier.of("scandium", "enabled"))
                .setName(Text.translatable("scandium.option.enabled"))
                .setTooltip(Text.translatable("scandium.option.enabled.tooltip"))
                .setBinding(v -> { config.enabled = v; config.save(); }, () -> config.enabled)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD));

        general.addOption(builder.createBooleanOption(Identifier.of("scandium", "sync_with_sodium"))
                .setName(Text.translatable("scandium.option.sync_with_sodium"))
                .setTooltip(Text.translatable("scandium.option.sync_with_sodium.tooltip"))
                .setBinding(v -> { config.syncWithSodium = v; config.save(); }, () -> config.syncWithSodium));

        page.addOptionGroup(general);

        // Culling Group
        OptionGroupBuilder culling = builder.createOptionGroup();
        culling.setName(Text.literal("Culling"));

        culling.addOption(builder.createBooleanOption(Identifier.of("scandium", "fov_culling"))
                .setName(Text.translatable("scandium.option.fov_culling"))
                .setTooltip(Text.translatable("scandium.option.fov_culling.tooltip"))
                .setBinding(v -> { config.fovCullingEnabled = v; config.save(); }, () -> config.fovCullingEnabled));

        culling.addOption(builder.createIntegerOption(Identifier.of("scandium", "fov_angle"))
                .setName(Text.translatable("scandium.option.fov_angle"))
                .setTooltip(Text.translatable("scandium.option.fov_angle.tooltip"))
                .setRange(70, 150, 5)
                .setValueFormatter(v -> Text.literal(String.valueOf(v)))
                .setBinding(v -> { config.fovAngle = v; config.save(); }, () -> config.fovAngle));

        culling.addOption(builder.createBooleanOption(Identifier.of("scandium", "mountain_culling"))
                .setName(Text.translatable("scandium.option.mountain_culling"))
                .setTooltip(Text.translatable("scandium.option.mountain_culling.tooltip"))
                .setBinding(v -> { config.aggressiveMountainCulling = v; config.save(); }, () -> config.aggressiveMountainCulling));

        culling.addOption(builder.createBooleanOption(Identifier.of("scandium", "transparency_awareness"))
                .setName(Text.translatable("scandium.option.transparency_awareness"))
                .setTooltip(Text.translatable("scandium.option.transparency_awareness.tooltip"))
                .setBinding(v -> { config.transparencyAwareness = v; config.save(); }, () -> config.transparencyAwareness));

        page.addOptionGroup(culling);

        // Advanced Group
        OptionGroupBuilder advanced = builder.createOptionGroup();
        advanced.setName(Text.literal("Advanced"));

        advanced.addOption(builder.createIntegerOption(Identifier.of("scandium", "reserved_height"))
                .setName(Text.translatable("scandium.option.reserved_height"))
                .setTooltip(Text.translatable("scandium.option.reserved_height.tooltip"))
                .setRange(0, 10, 1)
                .setValueFormatter(v -> Text.literal(String.valueOf(v)))
                .setBinding(v -> { config.reservedHeight = v; config.save(); }, () -> config.reservedHeight));

        advanced.addOption(builder.createIntegerOption(Identifier.of("scandium", "update_speed"))
                .setName(Text.translatable("scandium.option.update_speed"))
                .setTooltip(Text.translatable("scandium.option.update_speed.tooltip"))
                .setRange(1, 100, 1)
                .setValueFormatter(v -> Text.literal(String.valueOf(v)))
                .setBinding(v -> { config.updateSpeed = v; config.save(); }, () -> config.updateSpeed));

        advanced.addOption(builder.createBooleanOption(Identifier.of("scandium", "debug_mode"))
                .setName(Text.translatable("scandium.option.debug_mode"))
                .setTooltip(Text.translatable("scandium.option.debug_mode.tooltip"))
                .setBinding(v -> { config.debugMode = v; config.save(); }, () -> config.debugMode));

        page.addOptionGroup(advanced);

        builder.registerOwnModOptions()
                .setName("Scandium")
                .addPage(page);
    }
}
