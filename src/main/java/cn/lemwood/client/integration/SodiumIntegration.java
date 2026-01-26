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
                .setStorageHandler(config::save)
                .setBinding(v -> { config.enabled = v; cn.lemwood.client.util.CullingUtils.resetCache(); }, () -> config.enabled)
                .setDefaultValue(true)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD));

        general.addOption(builder.createBooleanOption(Identifier.of("scandium", "sync_with_sodium"))
                .setName(Text.translatable("scandium.option.sync_with_sodium"))
                .setTooltip(Text.translatable("scandium.option.sync_with_sodium.tooltip"))
                .setStorageHandler(config::save)
                .setBinding(v -> { config.syncWithSodium = v; cn.lemwood.client.util.CullingUtils.resetCache(); }, () -> config.syncWithSodium)
                .setDefaultValue(true));

        general.addOption(builder.createBooleanOption(Identifier.of("scandium", "ignore_spectator_mode"))
                .setName(Text.translatable("scandium.option.ignore_spectator_mode"))
                .setTooltip(Text.translatable("scandium.option.ignore_spectator_mode.tooltip"))
                .setStorageHandler(config::save)
                .setBinding(v -> { config.ignoreSpectatorMode = v; cn.lemwood.client.util.CullingUtils.resetCache(); }, () -> config.ignoreSpectatorMode)
                .setDefaultValue(true));

        page.addOptionGroup(general);

        // Culling Group
        OptionGroupBuilder culling = builder.createOptionGroup();
        culling.setName(Text.literal("Culling"));

        culling.addOption(builder.createBooleanOption(Identifier.of("scandium", "fov_culling"))
                .setName(Text.translatable("scandium.option.fov_culling"))
                .setTooltip(Text.translatable("scandium.option.fov_culling.tooltip"))
                .setStorageHandler(config::save)
                .setBinding(v -> { config.fovCullingEnabled = v; cn.lemwood.client.util.CullingUtils.resetCache(); }, () -> config.fovCullingEnabled)
                .setDefaultValue(true));

        culling.addOption(builder.createIntegerOption(Identifier.of("scandium", "fov_angle"))
                .setName(Text.translatable("scandium.option.fov_angle"))
                .setTooltip(Text.translatable("scandium.option.fov_angle.tooltip"))
                .setRange(70, 150, 5)
                .setStorageHandler(config::save)
                .setValueFormatter(v -> Text.literal(String.valueOf(v)))
                .setBinding(v -> { config.fovAngle = v; cn.lemwood.client.util.CullingUtils.resetCache(); }, () -> config.fovAngle)
                .setDefaultValue(130));

        culling.addOption(builder.createBooleanOption(Identifier.of("scandium", "mountain_culling"))
                .setName(Text.translatable("scandium.option.mountain_culling"))
                .setTooltip(Text.translatable("scandium.option.mountain_culling.tooltip"))
                .setStorageHandler(config::save)
                .setBinding(v -> { config.aggressiveMountainCulling = v; cn.lemwood.client.util.CullingUtils.resetCache(); }, () -> config.aggressiveMountainCulling)
                .setDefaultValue(true));

        culling.addOption(builder.createBooleanOption(Identifier.of("scandium", "transparency_awareness"))
                .setName(Text.translatable("scandium.option.transparency_awareness"))
                .setTooltip(Text.translatable("scandium.option.transparency_awareness.tooltip"))
                .setStorageHandler(config::save)
                .setBinding(v -> { config.transparencyAwareness = v; cn.lemwood.client.util.CullingUtils.resetCache(); }, () -> config.transparencyAwareness)
                .setDefaultValue(true));

        culling.addOption(builder.createBooleanOption(Identifier.of("scandium", "aggressive_vertical_culling"))
                .setName(Text.translatable("scandium.option.aggressive_vertical_culling"))
                .setTooltip(Text.translatable("scandium.option.aggressive_vertical_culling.tooltip"))
                .setStorageHandler(config::save)
                .setBinding(v -> { config.aggressiveVerticalCulling = v; cn.lemwood.client.util.CullingUtils.resetCache(); }, () -> config.aggressiveVerticalCulling)
                .setDefaultValue(false));

        culling.addOption(builder.createBooleanOption(Identifier.of("scandium", "underground_horizontal_culling"))
                .setName(Text.translatable("scandium.option.underground_horizontal_culling"))
                .setTooltip(Text.translatable("scandium.option.underground_horizontal_culling.tooltip"))
                .setStorageHandler(config::save)
                .setBinding(v -> { config.undergroundHorizontalCulling = v; cn.lemwood.client.util.CullingUtils.resetCache(); }, () -> config.undergroundHorizontalCulling)
                .setDefaultValue(true));

        culling.addOption(builder.createIntegerOption(Identifier.of("scandium", "underground_horizontal_distance"))
                .setName(Text.translatable("scandium.option.underground_horizontal_distance"))
                .setTooltip(Text.translatable("scandium.option.underground_horizontal_distance.tooltip"))
                .setRange(2, 16, 1)
                .setStorageHandler(config::save)
                .setValueFormatter(v -> Text.literal(v + " Chunks"))
                .setBinding(v -> { config.undergroundHorizontalDistance = v; cn.lemwood.client.util.CullingUtils.resetCache(); }, () -> config.undergroundHorizontalDistance)
                .setDefaultValue(4));

        page.addOptionGroup(culling);

        // Advanced Group
        OptionGroupBuilder advanced = builder.createOptionGroup();
        advanced.setName(Text.literal("Advanced"));

        advanced.addOption(builder.createIntegerOption(Identifier.of("scandium", "reserved_height"))
                .setName(Text.translatable("scandium.option.reserved_height"))
                .setTooltip(Text.translatable("scandium.option.reserved_height.tooltip"))
                .setRange(0, 10, 1)
                .setStorageHandler(config::save)
                .setValueFormatter(v -> Text.literal(String.valueOf(v)))
                .setBinding(v -> { config.reservedHeight = v; cn.lemwood.client.util.CullingUtils.resetCache(); }, () -> config.reservedHeight)
                .setDefaultValue(2));

        advanced.addOption(builder.createIntegerOption(Identifier.of("scandium", "update_speed"))
                .setName(Text.translatable("scandium.option.update_speed"))
                .setTooltip(Text.translatable("scandium.option.update_speed.tooltip"))
                .setRange(1, 100, 1)
                .setStorageHandler(config::save)
                .setValueFormatter(v -> Text.literal(String.valueOf(v)))
                .setBinding(v -> { config.updateSpeed = v; cn.lemwood.client.util.CullingUtils.resetCache(); }, () -> config.updateSpeed)
                .setDefaultValue(20));

        advanced.addOption(builder.createBooleanOption(Identifier.of("scandium", "debug_mode"))
                .setName(Text.translatable("scandium.option.debug_mode"))
                .setTooltip(Text.translatable("scandium.option.debug_mode.tooltip"))
                .setStorageHandler(config::save)
                .setBinding(v -> { config.debugMode = v; cn.lemwood.client.util.CullingUtils.resetCache(); }, () -> config.debugMode)
                .setDefaultValue(false));

        page.addOptionGroup(advanced);

        builder.registerOwnModOptions()
                .setName("Scandium")
                .addPage(page);
    }
}
