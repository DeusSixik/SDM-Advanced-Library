package dev.sixik.sdmal;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
//? if >=26.1 {
/*import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
*///?} else {
import net.minecraftforge.eventbus.api.SubscribeEvent;
//?}
import java.util.List;

/** Adapts Forge's native TOML configuration to the common immutable values. */
@Mod.EventBusSubscriber(modid = SDMAdvancedLibrary.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ForgeConfig {
    /** Builder containing the original configuration keys. */
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    /** Whether setup logs the dirt identifier. */
    private static final ForgeConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER.define("logDirtBlock", true);
    /** Example number, constrained to [0, Integer.MAX_VALUE]. */
    private static final ForgeConfigSpec.IntValue MAGIC_NUMBER = BUILDER.defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);
    /** Prefix for the example number. */
    private static final ForgeConfigSpec.ConfigValue<String> INTRODUCTION = BUILDER.define("magicNumberIntroduction", Config.DEFAULT.magicNumberIntroduction());
    /** Validated item registry identifiers. */
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> ITEMS = BUILDER.defineListAllowEmpty(List.of("items"), () -> Config.DEFAULT.items(), SDMAdvancedLibrary::isItem);
    /** Native specification registered as sdmal-common.toml. */
    public static final ForgeConfigSpec SPEC = BUILDER.build();
    /** Most recently loaded common configuration. */
    public static Config current = Config.DEFAULT;

    /** Prevents instantiation of the event subscriber. */
    private ForgeConfig() {}

    /**
     * Updates common values when this mod's configuration is loaded or reloaded.
     * @param event native configuration event
     */
    @SubscribeEvent
    public static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        current = new Config(LOG_DIRT_BLOCK.get(), MAGIC_NUMBER.get(), INTRODUCTION.get(), List.copyOf(ITEMS.get()));
    }
}
