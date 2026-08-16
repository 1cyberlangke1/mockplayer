/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.mockplayer.baritone.api.utils;

import com.mockplayer.baritone.api.utils.accessor.IItemStack;
import com.mockplayer.baritone.api.utils.accessor.ILootItem;
import com.mockplayer.baritone.api.utils.accessor.ILootPool;
import com.mockplayer.baritone.api.utils.accessor.ILootTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import sun.misc.Unsafe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class BlockOptionalMeta {
    // id or id[] or id[properties] where id and properties are any text with at least one character
    private static final Pattern PATTERN = Pattern.compile("^(?<id>.+?)(?:\\[(?<properties>.+?)?\\])?$");

    private final Block block;
    private final String propertiesDescription; // exists so toString() can return something more useful than a list of all blockstates
    private final Set<BlockState> blockstates;
    private final ImmutableSet<Integer> stateHashes;
    private final ImmutableSet<Integer> stackHashes;
    private static Map<Block, List<Item>> drops = new HashMap<>();

    public BlockOptionalMeta(@Nonnull Block block) {
        this.block = block;
        this.propertiesDescription = "{}";
        this.blockstates = getStates(block, Collections.emptyMap());
        this.stateHashes = getStateHashes(blockstates);
        this.stackHashes = getStackHashes(blockstates);
    }

    public BlockOptionalMeta(@Nonnull String selector) {
        Matcher matcher = PATTERN.matcher(selector);

        if (!matcher.find()) {
            throw new IllegalArgumentException("invalid block selector");
        }

        block = BlockUtils.stringToBlockRequired(matcher.group("id"));

        String props = matcher.group("properties");
        Map<Property<?>, ?> properties = props == null || props.equals("") ? Collections.emptyMap() : parseProperties(block, props);

        propertiesDescription = props == null ? "{}" : "{" + props.replace("=", ":") + "}";
        blockstates = getStates(block, properties);
        stateHashes = getStateHashes(blockstates);
        stackHashes = getStackHashes(blockstates);
    }

    private static <C extends Comparable<C>, P extends Property<C>> P castToIProperty(Object value) {
        //noinspection unchecked
        return (P) value;
    }

    private static Map<Property<?>, ?> parseProperties(Block block, String raw) {
        ImmutableMap.Builder<Property<?>, Object> builder = ImmutableMap.builder();
        for (String pair : raw.split(",")) {
            String[] parts = pair.split("=");
            if (parts.length != 2) {
                throw new IllegalArgumentException(String.format("\"%s\" is not a valid property-value pair", pair));
            }
            String rawKey = parts[0];
            String rawValue = parts[1];
            Property<?> key = block.getStateDefinition().getProperty(rawKey);
            Comparable<?> value = castToIProperty(key).getValue(rawValue)
                    .orElseThrow(() -> new IllegalArgumentException(String.format(
                            "\"%s\" is not a valid value for %s on %s",
                            rawValue, key, block
                    )));
            builder.put(key, value);
        }
        return builder.build();
    }

    private static Set<BlockState> getStates(@Nonnull Block block, @Nonnull Map<Property<?>, ?> properties) {
        return block.getStateDefinition().getPossibleStates().stream()
                .filter(blockstate -> properties.entrySet().stream().allMatch(entry ->
                        blockstate.getValue(entry.getKey()) == entry.getValue()
                ))
                .collect(Collectors.toSet());
    }

    private static ImmutableSet<Integer> getStateHashes(Set<BlockState> blockstates) {
        return ImmutableSet.copyOf(
                blockstates.stream()
                        .map(BlockState::hashCode)
                        .toArray(Integer[]::new)
        );
    }

    private static ImmutableSet<Integer> getStackHashes(Set<BlockState> blockstates) {
        Set<Item> items = new HashSet<>();
        for (BlockState state : blockstates) {
            Block block = state.getBlock();
            // loot table 掉落物（单机完整；联网用 vanilla pack 加载，mod 方块/数据包自定义可能为空）
            items.addAll(drops(block));
            // 保底：方块自身物品（BlockItem）——联网拿不到 loot table 时 oak_log/dirt 等也能捡
            Item self = block.asItem();
            if (self != Items.AIR) {
                items.add(self);
            }
        }
        return ImmutableSet.copyOf(
                items.stream()
                        .map(item -> new ItemStack(item, 1))
                        .map(stack -> ((IItemStack) (Object) stack).getBaritoneHash())
                        .toArray(Integer[]::new)
        );
    }

    public Block getBlock() {
        return block;
    }

    public boolean matches(@Nonnull Block block) {
        return block == this.block;
    }

    public boolean matches(@Nonnull BlockState blockstate) {
        Block block = blockstate.getBlock();
        return block == this.block && stateHashes.contains(blockstate.hashCode());
    }

    public boolean matches(ItemStack stack) {
        //noinspection ConstantConditions
        int hash = ((IItemStack) (Object) stack).getBaritoneHash();

        hash -= stack.getDamageValue();

        return stackHashes.contains(hash);
    }

    @Override
    public String toString() {
        return String.format("BlockOptionalMeta{block=%s,properties=%s}", block, propertiesDescription);
    }

    public BlockState getAnyBlockState() {
        if (blockstates.size() > 0) {
            return blockstates.iterator().next();
        }

        return null;
    }

    public Set<BlockState> getAllBlockStates() {
        return blockstates;
    }

    public Set<Integer> stackHashes() {
        return stackHashes;
    }

    private static Method getVanillaServerPack;

    private static VanillaPackResources getVanillaServerPack() {
        if (getVanillaServerPack == null) {
            getVanillaServerPack = Arrays.stream(ServerPacksSource.class.getDeclaredMethods()).filter(field -> field.getReturnType() == VanillaPackResources.class).findFirst().orElseThrow();
            getVanillaServerPack.setAccessible(true);
        }

        try {
            return (VanillaPackResources) getVanillaServerPack.invoke(null);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    private static synchronized List<Item> drops(Block b) {
        return drops.computeIfAbsent(b, block -> {
            Optional<ResourceKey<LootTable>> optionalLootTableKey = block.getLootTable();
            if (optionalLootTableKey.isEmpty()) {
                return Collections.emptyList();
            } else {
                List<Item> items = new ArrayList<>();
                try {
                    ServerLevel lv2 = ServerLevelStub.fastCreate();
                    getDrops(block, lv2).stream().map(ItemStack::getItem).forEach(items::add);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
                return items;
            }
        });
    }

    private static List<ItemStack> getDrops(Block state, ServerLevel serverLevel) {
        Optional<ResourceKey<LootTable>> lv = state.getLootTable();
        if (lv.isEmpty()) {
            return Collections.emptyList();
        } else {
            // loot tables 在服务端的 reloadable 层（不在 registryAccess() 里）：
            // 单机直接用服务端 holder；连服务器用 vanilla pack 完整加载的 holder（首次 join 等待）。
            net.minecraft.server.MinecraftServer singleplayer =
                    net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
            LootTable lv4 = singleplayer != null
                    ? singleplayer.reloadableRegistries().getLootTable(lv.get())
                    : ((ServerLevelStub) serverLevel).holder().getLootTable(lv.get());
            if (lv4 == null) {
                return Collections.emptyList();
            }
            // fabric-loot-api-v3 的 modifyDrops 包装需要真实 MinecraftServer（ServerLevelStub 为
            // Unsafe 分配、server=null），走 getRandomItems 会 NPE → drops() 空 → filter.has(物品)
            // 恒 false → 掉落物不捡。用 mixin accessor 读 pools/entries/item（标准注入，非反射），
            // 数据驱动、绕开包装；LootItemCondition 不影响「可能掉落」的判定（宁多勿缺）。
            List<ItemStack> items = new ArrayList<>();
            List<LootPool> pools = ((ILootTable) lv4).pools();
            if (pools != null) {
                for (LootPool pool : pools) {
                    List<LootPoolEntryContainer> entries = ((ILootPool) pool).entries();
                    if (entries == null) {
                        continue;
                    }
                    for (LootPoolEntryContainer entry : entries) {
                        if (entry instanceof LootItem lootItem) {
                            Holder<Item> holder = ((ILootItem) lootItem).item();
                            if (holder != null && holder.value() != null) {
                                items.add(new ItemStack(holder.value(), 1));
                            }
                        }
                    }
                }
            }
            return items;
        }
    }

    public static class ServerLevelStub extends ServerLevel {
        private static Minecraft client = Minecraft.getInstance();
        private static Unsafe unsafe = getUnsafe();
        private static CompletableFuture<RegistryAccess> registryAccess = load();

        public ServerLevelStub(MinecraftServer $$0, Executor $$1, LevelStorageSource.LevelStorageAccess $$2, ServerLevelData $$3, ResourceKey<Level> $$4, LevelStem $$5, boolean $$6, long $$7, List<CustomSpawner> $$8, boolean $$9) {
            super($$0, $$1, $$2, $$3, $$4, $$5, $$6, $$7, $$8, $$9);
        }

        @Override
        public FeatureFlagSet enabledFeatures() {
            assert client.level != null;
            return client.level.enabledFeatures();
        }

        public static ServerLevelStub fastCreate() {
            try {
                return (ServerLevelStub) unsafe.allocateInstance(ServerLevelStub.class);
            } catch (InstantiationException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public RegistryAccess registryAccess() {
            // 优先用服务端 registry（含 loot tables）：客户端 registry 不含 loot tables，
            // getLootTable 返回 EMPTY → drops() 空 → filter.has(物品) 恒 false → 掉落物不捡。
            // 单机（含 mocktest）：getSingleplayerServer() 可用，零加载开销；
            // 连服务器：回退到 vanilla pack 完整加载（首次 join 等待，结果缓存）。
            net.minecraft.server.MinecraftServer server =
                    net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
                return server.registryAccess();
            }
            return BlockOptionalMeta.ServerLevelStub.registryAccess.join();
        }

        public ReloadableServerRegistries.Holder holder() {
            return new ReloadableServerRegistries.Holder(registryAccess().freeze());
        }

        public static Unsafe getUnsafe() {
            try {
                Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                return (Unsafe) theUnsafe.get(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static CompletableFuture<RegistryAccess> load() {
            // Simplified from {@link net.minecraft.server.WorldLoader#load()}
            CloseableResourceManager closeableResourceManager = new MultiPackResourceManager(
                PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource())
            );
            LayeredRegistryAccess<RegistryLayer> baseLayeredRegistry = RegistryLayer.createRegistryAccess();
            List<Registry.PendingTags<?>> pendingTags = TagLoader.loadTagsForExistingRegistries(
                closeableResourceManager, baseLayeredRegistry.getLayer(RegistryLayer.STATIC)
            );
            List<HolderLookup.RegistryLookup<?>> worldGenRegistryLookupList = TagLoader.buildUpdatedLookups(
                baseLayeredRegistry.getAccessForLoading(RegistryLayer.WORLDGEN),
                pendingTags
            );
            LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess = baseLayeredRegistry.replaceFrom(
                RegistryLayer.WORLDGEN,
                RegistryDataLoader.load(
                    closeableResourceManager,
                    worldGenRegistryLookupList,
                    RegistryDataLoader.WORLDGEN_REGISTRIES,
                    ForkJoinPool.commonPool()
                ).join()
            );
            return ReloadableServerRegistries.reload(
                layeredRegistryAccess,
                pendingTags,
                closeableResourceManager,
                ForkJoinPool.commonPool()
            ).thenApply(r -> r.layers().compositeAccess());
        }
    }
}
