package shadows.apotheosis.adventure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.apache.commons.lang3.tuple.Pair;

import net.minecraft.ResourceLocationException;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraftforge.registries.ForgeRegistries;
import shadows.apotheosis.adventure.boss.BossEvents.BossSpawnRules;
import shadows.apotheosis.adventure.loot.LootCategory;
import shadows.apotheosis.adventure.loot.LootRarity;
import shadows.placebo.config.Configuration;

public class AdventureConfig {

	public static final List<ResourceLocation> DIM_WHITELIST = new ArrayList<>();
	public static final List<ResourceLocation> BIOME_BLACKLIST = new ArrayList<>();
	public static final Map<ResourceLocation, LootCategory> TYPE_OVERRIDES = new HashMap<>();
	public static final Map<ResourceLocation, Pair<Float, BossSpawnRules>> BOSS_SPAWN_RULES = new HashMap<>();

	/**
	 * These lists contain "loot table matchers" for the drop chances for loot tables.
	 * Loot table matchers take the form of domain:pattern and the float chance is 0..1
	 * Omitting the domain causes the pattern to be run for all domains.
	 * The pattern is only run on the loot table's path.
	 */
	public static final List<LootPatternMatcher> AFFIX_ITEM_LOOT_RULES = new ArrayList<>();
	public static final List<LootPatternMatcher> GEM_LOOT_RULES = new ArrayList<>();

	/**
	 * Loot table matchers and dimensional rarities for affix conversion rules.
	 */
	public static final List<LootPatternMatcher> AFFIX_CONVERT_LOOT_RULES = new ArrayList<>();
	public static final Map<ResourceLocation, LootRarity.Clamped> AFFIX_CONVERT_RARITIES = new HashMap<>();

	//Boss Stats
	public static boolean curseBossItems = false;
	public static float bossAnnounceRange = 96;
	public static float bossAnnounceVolume = 0.75F;
	public static boolean bossAnnounceIgnoreY = false;
	public static int bossSpawnCooldown = 400;

	//Generation Chances
	public static int bossDungeonAttempts = 8;
	public static int bossDungeon2Attempts = 8;
	public static int rogueSpawnerAttempts = 4;
	//public static int troveAttempts = 8;
	//public static int tomeTowerChance = 125;
	public static float spawnerValueChance = 0.11F;

	// Affix
	public static float randomAffixItem = 0.24F;
	public static float gemDropChance = 0.045F;
	public static float gemBossBonus = 0.33F;
	public static boolean disableQuarkOnAffixItems = true;
	public static Supplier<Item> torchItem = () -> Items.TORCH;
	public static boolean cleaveHitsPlayers = false;

	public static void load(Configuration c) {
		c.setTitle("Apotheosis Adventure Module Config");
		for (LootRarity r : LootRarity.values()) {
			if (r != LootRarity.ANCIENT) {
				int weight = c.getInt(r.id() + " weight", "rarities", r.defaultWeight(), 0, 10000, "The weight of this rarity.  The chance of this rarity appearing is <weight>/<total weight>.");
				float quality = c.getFloat(r.id() + " quality", "rarities", r.ordinal() * 1.5F, 0, 100, "The quality of this rarity.  Each point of luck increases the weight of this rarity by the quality value.");
				LootRarity.WEIGHTS.put(r, new float[] { weight, quality });
			}
			LootRarity.WEIGHTS.put(LootRarity.ANCIENT, new float[] { 0, 0 });
		}
		if (LootRarity.WEIGHTS.values().stream().collect(Collectors.summarizingInt(arr -> (int) arr[0])).getSum() <= 0) {
			throw new RuntimeException("The total loot rarity weight may not be zero!");
		}

		TYPE_OVERRIDES.clear();
		String[] overrides = c.getStringList("Equipment Type Overrides", "affixes", new String[] { "minecraft:iron_sword|sword", "minecraft:shulker_shell|none" }, "A list of type overrides for the affix loot system.  Format is <itemname>|chance|<type>.\nValid types are: none, sword, trident, shield, heavy_weapon, pickaxe, shovel, crossbow, bow");
		for (String s : overrides) {
			String[] split = s.split("\\|");
			try {
				LootCategory type = LootCategory.byId(split[1].toLowerCase(Locale.ROOT));
				if (type.isArmor()) throw new UnsupportedOperationException("Cannot override an item to an armor type.");
				TYPE_OVERRIDES.put(new ResourceLocation(split[0]), type);
			} catch (Exception e) {
				AdventureModule.LOGGER.error("Invalid type override entry: " + s + " will be ignored!");
				e.printStackTrace();
			}
		}

		randomAffixItem = c.getFloat("Random Affix Chance", "affixes", randomAffixItem, 0, 1, "The chance that a naturally spawned mob will be granted an affix item. 0 = 0%, 1 = 100%");
		gemDropChance = c.getFloat("Gem Drop Chance", "affixes", gemDropChance, 0, 1, "The chance that a mob will drop a gem. 0 = 0%, 1 = 100%");
		gemBossBonus = c.getFloat("Gem Boss Bonus", "affixes", gemBossBonus, 0, 1, "The flat bonus chance that bosses have to drop a gem, added to Gem Drop Chance. 0 = 0%, 1 = 100%");
		cleaveHitsPlayers = c.getBoolean("Cleave Players", "affixes", cleaveHitsPlayers, "If affixes that cleave can hit players (excluding the user).");

		String[] lootRules = c.getStringList("Affix Item Loot Rules", "affixes", new String[] { "minecraft:chests.*|0.5", ".*chests.*|0.35", "twilightforest:structures.*|0.4" },
		//Formatter::off
			"Loot Rules, in the form of Loot Table Matchers, permitting affix items to spawn in loot tables." 
		  + "\nThe format for these is domain:pattern|chance and domain is optional.  Domain is a modid, pattern is a regex string, and chance is a float 0..1 chance for the item to spawn in any matched tables." 
		  + "\nIf you omit the domain, the format is pattern|chance, and the matcher will run for all domains." 
		  + "\nThe pattern MUST be a valid regex string, and should match the paths of desired loot tables under the specified domain.  Note: \"Match Any Character\" is \".*\" (dot star) and not \"*\" (star)." 
		  + "\nIf there is a match, an item has a chance to spawn in that loot table.");
		//Formatter::on
		AFFIX_ITEM_LOOT_RULES.clear();
		for (String s : lootRules) {
			try {
				AFFIX_ITEM_LOOT_RULES.add(LootPatternMatcher.parse(s));
			} catch (Exception e) {
				AdventureModule.LOGGER.error("Invalid affix item loot rule: " + s + " will be ignored");
				e.printStackTrace();
			}
		}

		lootRules = c.getStringList("Gem Loot Rules", "gems", new String[] { "minecraft:chests.*|0.30", ".*chests.*|0.15", "twilightforest:structures.*|0.20" }, "Loot Rules, in the form of Loot Table Matchers, permitting gems to spawn in loot tables.  See comment on \"Affix Item Loot Rules\" for description.");
		GEM_LOOT_RULES.clear();
		for (String s : lootRules) {
			try {
				GEM_LOOT_RULES.add(LootPatternMatcher.parse(s));
			} catch (Exception e) {
				AdventureModule.LOGGER.error("Invalid gem loot rule: " + s + " will be ignored");
				e.printStackTrace();
			}
		}

		lootRules = c.getStringList("Affix Convert Loot Rules", "affixes", new String[] { ".*blocks.*|0", ".*|0.85" }, "Loot Rules, in the form of Loot Table Matchers, permitting affixes to be added to any valid item. Here, the chance refers to the chance an item receives affixes. See comment on \"Affix Item Loot Rules\" for description.");
		AFFIX_CONVERT_LOOT_RULES.clear();
		for (String s : lootRules) {
			try {
				AFFIX_CONVERT_LOOT_RULES.add(LootPatternMatcher.parse(s));
			} catch (Exception e) {
				AdventureModule.LOGGER.error("Invalid affix convert loot rule: " + s + " will be ignored");
				e.printStackTrace();
			}
		}

		String[] convertRarities = c.getStringList("Affix Convert Rarities", "affixes", new String[] { "overworld|common|rare", "the_nether|uncommon|epic", "the_end|rare|mythic", "twilightforest:twilight_forest|uncommon|epic" }, "Dimenaional rarities for affix conversion (see \"Affix Convert Loot Rules\"), in the form of dimension|min|max. A dimension not listed uses all rarities.");
		AFFIX_CONVERT_RARITIES.clear();
		for (String s : convertRarities) {
			try {
				String[] split = s.split("\\|");
				ResourceLocation dim = new ResourceLocation(split[0]);
				LootRarity min = LootRarity.byId(split[1]);
				LootRarity max = LootRarity.byId(split[2]);
				LootRarity.Clamped clamp = new LootRarity.Clamped() {

					@Override
					public LootRarity getMinRarity() {
						return min;
					}

					@Override
					public LootRarity getMaxRarity() {
						return max;
					}

				};
				AFFIX_CONVERT_RARITIES.put(dim, clamp);
			} catch (Exception e) {
				AdventureModule.LOGGER.error("Invalid Affix Convert Rarity: " + s + " will be ignored");
				e.printStackTrace();
			}
		}

		disableQuarkOnAffixItems = c.getBoolean("Disable Quark Tooltips for Affix Items", "affixes", true, "If Quark's Attribute Tooltip handling is disabled for affix items");

		String torch = c.getString("Torch Placement Item", "affixes", "minecraft:torch", "The item that will be used when attempting to place torches with the torch placer affix.  Must be a valid item that places a block on right click.");
		torchItem = () -> {
			try {
				Item i = ForgeRegistries.ITEMS.getValue(new ResourceLocation(torch));
				return i == Items.AIR ? Items.TORCH : i;
			} catch (Exception ex) {
				AdventureModule.LOGGER.error("Invalid torch item {}", torch);
				return Items.TORCH;
			}
		};

		curseBossItems = c.getBoolean("Curse Boss Items", "bosses", false, "If boss items are always cursed.  Enable this if you want bosses to be less overpowered by always giving them a negative effect.");
		bossAnnounceRange = c.getFloat("Boss Announce Range", "bosses", 96, 0, 1024, "The range at which boss spawns will be announced.  If you are closer than this number of blocks (ignoring y-level), you will receive the announcement.");
		bossAnnounceVolume = c.getFloat("Boss Announce Volume", "bosses", bossAnnounceVolume, 0, 1, "The volume of the boss announcement sound. 0 to disable. This control is clientside.");
		bossAnnounceIgnoreY = c.getBoolean("Boss Announce Ignore Y", "bosses", true, "If the boss announcement range ignores y-level.");
		bossSpawnCooldown = c.getInt("Boss Spawn Cooldown", "bosses", 400, 0, 720000, "The time, in ticks, that must pass between any two natural boss spawns in a single dimension.");

		String[] dims = c.getStringList("Boss Spawn Dimensions", "bosses", new String[] { "minecraft:overworld|0.02|NEEDS_SKY", "minecraft:the_nether|0.03|ANY", "minecraft:the_end|0.02|NEEDS_SURFACE", "twilightforest:twilight_forest|0.05|NEEDS_SURFACE" }, "Dimensions where bosses can spawn naturally, spawn chance, and spawn rules.\nFormat is dimname|chance|rule, chance is a float from 0..1.\nValid rules are NEEDS_SKY, NEEDS_SURFACE, and ANY");
		BOSS_SPAWN_RULES.clear();
		for (String s : dims) {
			try {
				String[] split = s.split("\\|");
				BOSS_SPAWN_RULES.put(new ResourceLocation(split[0]), Pair.of(Float.parseFloat(split[1]), BossSpawnRules.valueOf(split[2].toUpperCase(Locale.ROOT))));
			} catch (Exception e) {
				AdventureModule.LOGGER.error("Invalid boss spawn rules: " + s + " will be ignored");
				e.printStackTrace();
			}
		}

		dims = c.getStringList("Generation Dimension Whitelist", "worldgen", new String[] { "overworld" }, "The dimensions that the deadly module will generate in.");
		DIM_WHITELIST.clear();
		for (String s : dims) {
			try {
				DIM_WHITELIST.add(new ResourceLocation(s.trim()));
			} catch (ResourceLocationException e) {
				AdventureModule.LOGGER.error("Invalid dim whitelist entry: " + s + " will be ignored");
			}
		}

		String[] biomes = c.getStringList("Generation Biome Blacklist", "worldgen", new String[] { "minecraft:warm_ocean", "minecraft:lukewarm_ocean", "minecraft:cold_ocean", "minecraft:frozen_ocean", "minecraft:deep_warm_ocean", "minecraft:deep_frozen_ocean", "minecraft:deep_lukewarm_ocean", "minecraft:deep_cold_ocean", "minecraft:ocean", "minecraft:deep_ocean" }, "The biomes that the deadly module will not generate in.");
		BIOME_BLACKLIST.clear();
		for (String s : biomes) {
			try {
				BIOME_BLACKLIST.add(new ResourceLocation(s.trim()));
			} catch (ResourceLocationException e) {
				AdventureModule.LOGGER.error("Invalid biome blacklist entry: " + s + " will be ignored!");
			}
		}

		bossDungeonAttempts = c.getInt("Boss Dungeon Attempts", "worldgen", 8, 0, 256, "The number of boss dungeon generation attempts per-chunk.");
		bossDungeon2Attempts = c.getInt("Boss Dungeon (Variant 2) Attempts", "worldgen", 8, 0, 256, "The number of boss dungeon (variant 2) generation attempts per-chunk.");
		rogueSpawnerAttempts = c.getInt("Rogue Spawner Attempts", "worldgen", 4, 0, 256, "The number of rogue spawner generation attempts per-chunk.");

		spawnerValueChance = c.getFloat("Spawner Value Chance", "spawners", spawnerValueChance, 0, 1, "The chance that a Rogue Spawner has a \"valuable\" chest instead of a standard one. 0 = 0%, 1 = 100%");
	}

	public static boolean canGenerateIn(WorldGenLevel world) {
		ResourceKey<Level> key = world.getLevel().dimension();
		return DIM_WHITELIST.contains(key.location());
	}

	public static record LootPatternMatcher(@Nullable String domain, Pattern pathRegex, float chance) {

		public boolean matches(ResourceLocation id) {
			return (domain == null || domain.equals(id.getNamespace())) && pathRegex.matcher(id.getPath()).matches();
		}

		public static LootPatternMatcher parse(String s) throws Exception {
			int pipe = s.lastIndexOf('|');
			int colon = s.indexOf(':');
			float chance = Float.parseFloat(s.substring(pipe + 1, s.length()));
			String domain = colon == -1 ? null : s.substring(0, colon);
			Pattern pattern = Pattern.compile(s.substring(colon + 1, pipe));
			return new LootPatternMatcher(domain, pattern, chance);
		}
	}

}