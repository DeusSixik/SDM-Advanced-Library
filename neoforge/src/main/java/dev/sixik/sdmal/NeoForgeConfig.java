package dev.sixik.sdmal;

import dev.sixik.sdmal.core.utils.ObjectArrayView;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import java.util.List;

/** Adapts MinecraftForge's native TOML configuration to common values. */
public final class NeoForgeConfig {
    /** Builder containing the original configuration keys. */
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    /** Whether setup logs the dirt identifier. */
    private static final ForgeConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER.define("logDirtBlock", true);
    /** Example number in [0, Integer.MAX_VALUE]. */
    private static final ForgeConfigSpec.IntValue MAGIC_NUMBER = BUILDER.defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);
    /** Prefix for the example number. */
    private static final ForgeConfigSpec.ConfigValue<String> INTRODUCTION = BUILDER.define("magicNumberIntroduction", Config.DEFAULT.magicNumberIntroduction());
    /** Validated registry identifiers for items to log. */
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> ITEMS = BUILDER.defineListAllowEmpty(ObjectArrayView.of("items"), () -> Config.DEFAULT.items(), SDMAdvancedLibrary::isItem);
    /** Native specification registered as sdmal-common.toml. */
    public static final ForgeConfigSpec SPEC = BUILDER.build();
    /** Most recently loaded common configuration. */
    public static Config current = Config.DEFAULT;

    /** Prevents instantiation of the adapter. */
    private NeoForgeConfig() {}

    /**
     * Updates common values when this mod's configuration changes.
     * @param event native configuration event
     */
    public static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        current = new Config(LOG_DIRT_BLOCK.get(), MAGIC_NUMBER.get(), INTRODUCTION.get(), ObjectArrayView.copyOf(ITEMS.get()));
    }
}
