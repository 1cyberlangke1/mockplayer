package com.mockplayer.memory;

import com.mockplayer.session.accessor.MockplayerCrudeBiMapAccessor;
import com.mockplayer.session.accessor.MockplayerAttributeTrackSamplerAccessor;
import com.mockplayer.session.accessor.MockplayerBlockTintCacheAccessor;
import com.mockplayer.session.accessor.MockplayerChunkAccessAccessor;
import com.mockplayer.session.accessor.MockplayerChunkSkyLightSourcesAccessor;
import com.mockplayer.session.accessor.MockplayerClientChunkCacheStorageAccessor;
import com.mockplayer.session.accessor.MockplayerClientLevelAccessor;
import com.mockplayer.session.accessor.MockplayerClientLevelMiscAccessor;
import com.mockplayer.session.accessor.MockplayerDataLayerAccessor;
import com.mockplayer.session.accessor.MockplayerDataLayerStorageMapAccessor;
import com.mockplayer.session.accessor.MockplayerEntityTickListAccessor;
import com.mockplayer.session.accessor.MockplayerEnvironmentAttributeSystemAccessor;
import com.mockplayer.session.accessor.MockplayerEntitySectionStorageAccessor;
import com.mockplayer.session.accessor.MockplayerHashMapPaletteAccessor;
import com.mockplayer.session.accessor.MockplayerHeightmapAccessor;
import com.mockplayer.session.accessor.MockplayerKeyframeTrackSamplerAccessor;
import com.mockplayer.session.accessor.MockplayerLayerLightSectionStorageAccessor;
import com.mockplayer.session.accessor.MockplayerLevelChunkTicksAccessor;
import com.mockplayer.session.accessor.MockplayerLightEngineAccessor;
import com.mockplayer.session.accessor.MockplayerLightEngineQueueAccessor;
import com.mockplayer.session.accessor.MockplayerLightEngineStorageAccessor;
import com.mockplayer.session.accessor.MockplayerLongArrayFIFOQueueAccessor;
import com.mockplayer.session.accessor.MockplayerLevelChunkAccessor;
import com.mockplayer.session.accessor.MockplayerLevelMiscAccessor;
import com.mockplayer.session.accessor.MockplayerPalettedContainerDataAccessor;
import com.mockplayer.session.accessor.MockplayerSkyLightSectionStorageAccessor;
import com.mockplayer.session.accessor.MockplayerSynchedEntityDataAccessor;
import com.mockplayer.session.accessor.MockplayerTransientEntitySectionManagerAccessor;
import com.mockplayer.session.accessor.MockplayerValueSamplerAccessor;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.BitStorage;
import net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.Map;

/**
 * 世界内存估算器（结构级口径，纯公式 O(1)，不依赖 agent、不遍历世界）。
 *
 * 口径：对象浅尺寸用运行时探测布局公式（跨 JVM 正确）；调色板/BitStorage/高度图
 * 按实际结构公式（accessor 读数组长度，O(1)）；方块实体/实体 = 浅尺寸 + NBT 数据字节。
 * 运行时变化（调色板扩容/方块实体数据/光照）由事件差值维护，见 {@link WorldMemoryAccountant}。
 */
public final class MemoryEstimator {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MemoryEstimator.class);
    private static boolean warnedAccessor;
    /**
     * PalettedContainer.data 是私有 record（外部无法命名类型，accessor 签名匹配不上），
     * 用缓存反射读这一个字段；record 的 palette()/storage() 是公开方法，
     * 经 MockplayerPalettedContainerDataAccessor（mixin 注入接口）读取，O(1)。
     */
    private static final java.lang.reflect.Field CONTAINER_DATA_FIELD = containerDataField();
    /** ClientChunkCache.storage 是私有内部类（accessor 签名匹配不上），缓存反射读。 */
    private static final java.lang.reflect.Field CHUNK_CACHE_STORAGE_FIELD = chunkCacheStorageField();

    private MemoryEstimator() {
    }

    private static java.lang.reflect.Field containerDataField() {
        try {
            java.lang.reflect.Field field = PalettedContainer.class.getDeclaredField("data");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PalettedContainer.data 字段不可达", e);
        }
    }

    private static java.lang.reflect.Field chunkCacheStorageField() {
        try {
            java.lang.reflect.Field field =
                    net.minecraft.client.multiplayer.ClientChunkCache.class.getDeclaredField("storage");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ClientChunkCache.storage 字段不可达", e);
        }
    }

    /**
     * 区块估算：区块浅尺寸 + 高度图 + 每个 section（浅尺寸 + 方块/生物群系容器堆字节）。
     * 空气 section 也保留完整的 PalettedContainer/SingleValuePalette/ZeroBitStorage/锁图，
     * 不能跳过——空容器对象本身占堆（实测 2400 个容器 ≈ 433KB）。
     */
    public static long estimateChunk(LevelChunk chunk) {
        long total = LayoutSizes.shallowSize(chunk.getClass());
        // 区块的 LevelChunkSection[]（24 个引用，浅尺寸只含该字段的引用本身）
        total += LayoutSizes.arraySize(chunk.getSections().length, LayoutSizes.REFERENCE);
        // 方块实体 Map 结构（JDK HashMap 表 + 节点 + 每个 BlockPos 键对象；值单独记账）
        var blockEntities = chunk.getBlockEntities();
        total += StructureHeap.hashMapHeap(blockEntities.size())
                + (long) blockEntities.size() * LayoutSizes.shallowSize(net.minecraft.core.BlockPos.class);
        total += heightmapBytes(chunk);
        total += postProcessingBytes(chunk);
        total += skyLightSourcesBytes(chunk);
        total += chunkTicksBytes(chunk);
        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null) {
                continue;
            }
            total += LayoutSizes.shallowSize(section.getClass());
            total += containerBytes(section.getStates());
            total += containerBytes(section.getBiomes());
        }
        return total;
    }

    /** 区块天空光源：对象 + 高度图 BitStorage（含 long[]）+ 两个 MutableBlockPos。 */
    private static long skyLightSourcesBytes(LevelChunk chunk) {
        try {
            net.minecraft.world.level.lighting.ChunkSkyLightSources sources =
                    ((MockplayerChunkAccessAccessor) chunk).mockplayer$getSkyLightSources();
            if (sources == null) {
                return 0;
            }
            BitStorage heightmap =
                    ((MockplayerChunkSkyLightSourcesAccessor) sources).mockplayer$getHeightmap();
            return LayoutSizes.shallowSize(
                    net.minecraft.world.level.lighting.ChunkSkyLightSources.class)
                    + LayoutSizes.shallowSize(heightmap.getClass())
                    + storageArrayBytes(heightmap)
                    + 2L * LayoutSizes.shallowSize(net.minecraft.core.BlockPos.MutableBlockPos.class);
        } catch (RuntimeException e) {
            warnAccessor("skyLight");
            return 0;
        }
    }

    /** 区块 block/fluid tick 容器：队列 + 待处理列表 + 去重集合。 */
    private static long chunkTicksBytes(LevelChunk chunk) {
        return ticksBytes(chunk.getBlockTicks()) + ticksBytes(chunk.getFluidTicks());
    }

    private static long ticksBytes(Object ticks) {
        // 只认注入过 accessor 的 LevelChunkTicks；c2me 等 mod 重写 chunk 系统后会换成
        // 自己的 scheduler（兼容 LevelChunkTicks 接口但无我们的 accessor），此时降级为
        // 结构下界并提示一次（不静默：记账精度下降必须可见）。
        if (!(ticks instanceof MockplayerLevelChunkTicksAccessor access)) {
            if (ticks != null) {
                warnAccessor("ticks");
            }
            return 0;
        }
        long total = LayoutSizes.shallowSize(ticks.getClass());
        java.util.Queue<?> tickQueue = access.mockplayer$getTickQueue();
        if (tickQueue == null) {
            // c2me 等 mod 的 LevelChunkTicks 实现字段为 null（接口通过但数据不在原版字段），
            // 降级为结构下界并提示一次（不静默：记账精度下降必须可见）。
            warnAccessor("ticks");
            return 0;
        }
        total += StructureHeap.priorityQueueHeap(tickQueue.size());
        java.util.List<?> pending = access.mockplayer$getPendingTicks();
        if (pending != null && !pending.isEmpty()) {
            total += LayoutSizes.shallowSize(pending.getClass())
                    + LayoutSizes.arraySize(pending.size(), LayoutSizes.REFERENCE)
                    + (long) pending.size() * LayoutSizes.shallowSize(pending.getFirst().getClass());
        }
        total += StructureHeap.objectKeySetHeapByCapacity(
                FastutilKeys.objectKey(access.mockplayer$getTicksPerPosition()));
        return total;
    }

    /** 区块 postProcessing 短列表：数组 + 每个非空 ShortList（结构/红石待处理数据）。 */
    private static long postProcessingBytes(LevelChunk chunk) {
        try {
            it.unimi.dsi.fastutil.shorts.ShortList[] post =
                    ((MockplayerChunkAccessAccessor) chunk).mockplayer$getPostProcessing();
            if (post == null) {
                return 0;
            }
            long bytes = LayoutSizes.arraySize(post.length, LayoutSizes.REFERENCE);
            for (it.unimi.dsi.fastutil.shorts.ShortList list : post) {
                if (list != null) {
                    bytes += StructureHeap.shortArrayListHeap(list.size());
                }
            }
            return bytes;
        } catch (RuntimeException e) {
            warnAccessor("postProcessing");
            return 0;
        }
    }

    /**
     * level 层结构堆字节（区块缓存存储、光照引擎表、实体分区表）：
     * 与区块/实体个数相关的容器结构，按当前计数器 O(1) 公式，事件时重算。
     */
    public static long levelStructuresBytes(net.minecraft.client.multiplayer.ClientLevel level) {
        long total = 0;
        try {
            // 区块缓存存储：AtomicReferenceArray + 空 section 跟踪 LongOpenHashSet
            // （26.1.2 单个集合；26.2 拆成 added/removed 四数组）
            Object storage = CHUNK_CACHE_STORAGE_FIELD.get(level.getChunkSource());
            MockplayerClientChunkCacheStorageAccessor storageAccess =
                    (MockplayerClientChunkCacheStorageAccessor) storage;
            long side = 2L * storageAccess.mockplayer$getChunkRadius() + 1;
            total += StructureHeap.atomicReferenceArrayHeap((int) (side * side));
            // 空 section 跟踪集合预分配大容量（视距范围），按实际数组长度计
            LongOpenHashSet empty = storageAccess.mockplayer$getLoadedEmptySections();
            total += StructureHeap.longOpenHashSetHeapByCapacity(
                    FastutilKeys.longKey(empty).length);

            // 光照引擎：sky/block 各一张 sectionStates + columnsWithSources + 数据层表
            MockplayerLightEngineAccessor light =
                    (MockplayerLightEngineAccessor) level.getChunkSource().getLightEngine();
            total += lightStorageBytes(light.mockplayer$getSkyEngine());
            total += lightStorageBytes(light.mockplayer$getBlockEngine());

            // 实体分区：tickingChunks + sections 开放寻址表 + sectionIds 树
            MockplayerTransientEntitySectionManagerAccessor entities =
                    (MockplayerTransientEntitySectionManagerAccessor)
                            ((MockplayerClientLevelAccessor) level).mockplayer$getEntityStorage();
            if (entities.mockplayer$getTickingChunks()
                    instanceof it.unimi.dsi.fastutil.longs.LongOpenHashSet) {
                total += StructureHeap.longOpenHashSetHeapByCapacity(
                    FastutilKeys.longKey(entities.mockplayer$getTickingChunks()).length);
            } else {
                total += StructureHeap.longOpenHashSetHeap(
                        entities.mockplayer$getTickingChunks().size());
            }
            MockplayerEntitySectionStorageAccessor sections =
                    (MockplayerEntitySectionStorageAccessor) entities.mockplayer$getSectionStorage();
            if (sections.mockplayer$getSections()
                    instanceof it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<?>) {
                total += StructureHeap.longKeyObjectValueMapHeapByCapacity(
                        FastutilKeys.longKey(sections.mockplayer$getSections()));
            } else {
                total += StructureHeap.long2ObjectMapHeap(
                        sections.mockplayer$getSections().size());
            }
            total += StructureHeap.longAVLTreeSetHeap(sections.mockplayer$getSectionIds().size());

            // 环境属性采样器 + 实体 tick 表 + 光照更新队列结构
            total += environmentAttributeBytes(
                    ((MockplayerClientLevelAccessor) level).mockplayer$getEnvironmentAttributes());
            MockplayerEntityTickListAccessor tickList =
                    (MockplayerEntityTickListAccessor)
                            ((MockplayerClientLevelAccessor) level).mockplayer$getTickingEntities();
            total += intLinkedMapHeapByCapacity(tickList.mockplayer$getActive());
            total += intLinkedMapHeapByCapacity(tickList.mockplayer$getPassive());
            java.util.Deque<Runnable> queue =
                    ((MockplayerClientLevelAccessor) level).mockplayer$getLightUpdateQueue();
            if (queue instanceof java.util.ArrayDeque<?> deque) {
                int capacity = 16;
                while (capacity < deque.size() * 2L) {
                    capacity <<= 1;
                }
                total += LayoutSizes.shallowSize(java.util.ArrayDeque.class)
                        + LayoutSizes.arraySize(capacity, LayoutSizes.REFERENCE);
            }
            // 队列每条 Runnable 捕获光照包数据（runnable + lightData + 2 个 List + 4 个 BitSet，
            // 实测单条 ≈ 335B，随队列积压计）
            total += queue.size() * 335L;

            // level 直接小字段：tint 缓存、玩家/龙部件/全局渲染 BE 列表、邻居/生物群系
            MockplayerClientLevelMiscAccessor misc =
                    (MockplayerClientLevelMiscAccessor) level;
            total += StructureHeap.object2ObjectArrayMapHeap(misc.mockplayer$getTintCaches().size());
            for (Object tintCache : misc.mockplayer$getTintCaches().values()) {
                net.minecraft.client.color.block.BlockTintCache cache =
                        (net.minecraft.client.color.block.BlockTintCache) tintCache;
                MockplayerBlockTintCacheAccessor cacheAccess =
                        (MockplayerBlockTintCacheAccessor) cache;
                total += LayoutSizes.shallowSize(cache.getClass())
                        + StructureHeap.longKeyObjectValueLinkedMapHeapByCapacity(
                        FastutilKeys.longKey(cacheAccess.mockplayer$getCache()))
                        + LayoutSizes.shallowSize(cacheAccess.mockplayer$getLock().getClass());
            }
            // 破坏进度（26.1.2 挂在 LevelRenderer 上，经假人 level 的 levelRenderer 取）
            com.mockplayer.session.accessor.MockplayerLevelRendererAccessor renderer =
                    (com.mockplayer.session.accessor.MockplayerLevelRendererAccessor)
                            ((com.mockplayer.session.accessor.MockplayerClientLevelAccessor) level)
                                    .mockplayer$getLevelRenderer();
            total += StructureHeap.int2ObjectMapHeap(renderer.mockplayer$getDestroyingBlocks().size());
            total += StructureHeap.long2ObjectMapHeap(renderer.mockplayer$getDestructionProgress().size());
            total += listHeap(misc.mockplayer$getPlayers());
            total += listHeap(misc.mockplayer$getDragonParts());
            total += StructureHeap.objectOpenHashSetHeap(
                    misc.mockplayer$getGloballyRenderedBlockEntities().size());
            MockplayerLevelMiscAccessor levelMisc = (MockplayerLevelMiscAccessor) level;
            total += LayoutSizes.shallowSize(
                    net.minecraft.world.level.redstone.CollectingNeighborUpdater.class);
            total += LayoutSizes.shallowSize(
                    net.minecraft.world.level.biome.BiomeManager.class);
            total += StructureHeap.long2ObjectMapHeap(
                    ((com.mockplayer.session.accessor.MockplayerClientLevelAccessor) level)
                            .mockplayer$getAllMapData().size());
        } catch (Exception e) {
            warnAccessor("level");
        }
        return total;
    }

    /** 普通 List 的浅尺寸 + 底层数组（元素对象由各自结构公式覆盖）。 */
    private static long listHeap(java.util.List<?> list) {
        return LayoutSizes.shallowSize(list.getClass())
                + LayoutSizes.arraySize(list.size(), LayoutSizes.REFERENCE);
    }

    /** Int2ObjectLinkedOpenHashMap 按实际 key 数组计（含链接数组），包装类回退 size 公式。 */
    private static long intLinkedMapHeapByCapacity(it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> map) {
        if (map instanceof it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap<?>) {
            int[] key = FastutilKeys.intKey(map);
            return StructureHeap.intKeyObjectValueMapHeapByCapacity(key)
                    + 2L * LayoutSizes.arraySize(key.length, Integer.BYTES);
        }
        return StructureHeap.int2ObjectLinkedMapHeap(map.size());
    }

    /** 环境属性系统：采样器表 + 每个 ValueSampler 的层列表 + KeyframeTrackSampler 段。 */
    private static long environmentAttributeBytes(Object system) {
        if (system == null) {
            return 0;
        }
        MockplayerEnvironmentAttributeSystemAccessor access =
                (MockplayerEnvironmentAttributeSystemAccessor) system;
        long total = StructureHeap.objectKeyObjectValueMapHeapByCapacity(
                FastutilKeys.objectKey(access.mockplayer$getAttributeSamplers()));
        for (Object sampler : access.mockplayer$getAttributeSamplers().values()) {
            total += LayoutSizes.shallowSize(sampler.getClass());
            java.util.List<?> layers = ((MockplayerValueSamplerAccessor) sampler).mockplayer$getLayers();
            total += LayoutSizes.shallowSize(layers.getClass())
                    + LayoutSizes.arraySize(layers.size(), LayoutSizes.REFERENCE);
            for (Object layer : layers) {
                total += LayoutSizes.shallowSize(layer.getClass());
                if (layer instanceof net.minecraft.world.timeline.AttributeTrackSampler<?, ?> ats) {
                    net.minecraft.util.KeyframeTrackSampler<?> kts =
                            ((MockplayerAttributeTrackSamplerAccessor) ats)
                                    .mockplayer$getArgumentSampler();
                    java.util.List<?> segments =
                            ((MockplayerKeyframeTrackSamplerAccessor) kts).mockplayer$getSegments();
                    total += LayoutSizes.shallowSize(kts.getClass())
                            + LayoutSizes.arraySize(segments.size(), LayoutSizes.REFERENCE);
                    if (!segments.isEmpty()) {
                        total += (long) segments.size()
                                * LayoutSizes.shallowSize(segments.getFirst().getClass());
                    }
                }
            }
        }
        return total;
    }

    private static long lightStorageBytes(Object engine) {
        if (engine == null) {
            return 0;
        }
        // 光照传播队列：blockNodesToCheck（0.5 负载）+ 增减队列（积压位置数组）
        MockplayerLightEngineQueueAccessor queues =
                (MockplayerLightEngineQueueAccessor) engine;
        long total;
        if (queues.mockplayer$getBlockNodesToCheck()
                instanceof it.unimi.dsi.fastutil.longs.LongOpenHashSet) {
            total = StructureHeap.longOpenHashSetHeapByCapacity(
                    FastutilKeys.longKey(queues.mockplayer$getBlockNodesToCheck()).length);
        } else {
            total = StructureHeap.longOpenHashSetHeap(
                    queues.mockplayer$getBlockNodesToCheck().size(), 0.5F);
        }
        total += StructureHeap.longArrayFIFOQueueHeap(
                ((MockplayerLongArrayFIFOQueueAccessor) queues.mockplayer$getDecreaseQueue())
                        .mockplayer$getArray());
        total += StructureHeap.longArrayFIFOQueueHeap(
                ((MockplayerLongArrayFIFOQueueAccessor) queues.mockplayer$getIncreaseQueue())
                        .mockplayer$getArray());
        Object storage = ((MockplayerLightEngineStorageAccessor) engine).mockplayer$getStorage();
        if (storage == null) {
            return total;
        }
        MockplayerLayerLightSectionStorageAccessor access =
                (MockplayerLayerLightSectionStorageAccessor) storage;
        if (access.mockplayer$getSectionStates()
                instanceof it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap) {
                total += StructureHeap.longKeyByteValueMapHeapByCapacity(
                        FastutilKeys.longKey(access.mockplayer$getSectionStates()));
        } else {
            total += StructureHeap.long2ByteMapHeap(access.mockplayer$getSectionStates().size());
        }
        if (access.mockplayer$getColumnsWithSources()
                instanceof it.unimi.dsi.fastutil.longs.LongOpenHashSet) {
            total += StructureHeap.longOpenHashSetHeapByCapacity(
                    FastutilKeys.longKey(access.mockplayer$getColumnsWithSources()).length);
        } else {
            total += StructureHeap.longOpenHashSetHeap(
                    access.mockplayer$getColumnsWithSources().size());
        }
        // 光照引擎其余预分配 LongOpenHashSet（变更/影响/保留/待删 section 表）
        total += lightLongSetBytes(access.mockplayer$getChangedSections());
        total += lightLongSetBytes(access.mockplayer$getSectionsAffectedByLightUpdates());
        total += lightLongSetBytes(access.mockplayer$getColumnsToRetainQueuedDataFor());
        total += lightLongSetBytes(access.mockplayer$getToRemove());
        // 光照数据层对象：visible/updating/queued 三处都可能持有 DataLayer，
        // 有 byte[2048] 的计数组，只有对象头的计浅尺寸
        total += lightDataLayerBytes(((MockplayerDataLayerStorageMapAccessor)
                access.mockplayer$getUpdatingSectionData()).mockplayer$getMap());
        total += lightDataLayerBytes(((MockplayerDataLayerStorageMapAccessor)
                access.mockplayer$getVisibleSectionData()).mockplayer$getMap());
        total += lightDataLayerBytes(access.mockplayer$getQueuedSections());
        if (storage instanceof net.minecraft.world.level.lighting.SkyLightSectionStorage sky) {
            total += StructureHeap.longKeyIntValueMapHeapByCapacity(
                    FastutilKeys.longKey(
                            ((MockplayerSkyLightSectionStorageAccessor)
                                    access.mockplayer$getUpdatingSectionData())
                                    .mockplayer$getTopSections()));
        }
        return total;
    }

    /** LongSet 按实际预分配容量计（LongOpenHashSet 初始 512 等）。 */
    private static long lightLongSetBytes(it.unimi.dsi.fastutil.longs.LongSet set) {
        if (set instanceof it.unimi.dsi.fastutil.longs.LongOpenHashSet) {
            return StructureHeap.longOpenHashSetHeapByCapacity(
                FastutilKeys.longKey(set).length);
        }
        return StructureHeap.longOpenHashSetHeap(set.size());
    }

    /** DataLayer 集合的堆字节：Long2ObjectMap 结构 + 每层对象（有数组的加 2048）。 */
    private static long lightDataLayerBytes(it.unimi.dsi.fastutil.longs.Long2ObjectMap<
            net.minecraft.world.level.chunk.DataLayer> layers) {
        long total;
        if (layers instanceof it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<?>) {
            total = StructureHeap.longKeyObjectValueMapHeapByCapacity(
                    FastutilKeys.longKey(layers));
        } else {
            // 同步包装类（queuedSections）：按 size 公式近似
            total = StructureHeap.long2ObjectMapHeap(layers.size());
        }
        long dataLayerShallow = LayoutSizes.shallowSize(net.minecraft.world.level.chunk.DataLayer.class);
        long arrayBytes = LayoutSizes.arraySize(
                net.minecraft.world.level.chunk.DataLayer.SIZE, 1);
        for (net.minecraft.world.level.chunk.DataLayer layer : layers.values()) {
            total += dataLayerShallow;
            if (((MockplayerDataLayerAccessor) layer).mockplayer$getData() != null) {
                total += arrayBytes;
            }
        }
        return total;
    }

    /** 高度图堆字节：每张高度图 = 对象浅尺寸 + BitStorage 浅尺寸 + long[] 数组。 */
    private static long heightmapBytes(LevelChunk chunk) {
        try {
            Map<Heightmap.Types, Heightmap> maps =
                    ((MockplayerLevelChunkAccessor) chunk).mockplayer$getHeightmaps();
            long bytes = 0;
            // EnumMap 结构：keyUniverse/values 两个数组（长度 = Types 常量数，枚举常量本身共享）
            bytes += LayoutSizes.shallowSize(java.util.EnumMap.class)
                    + 2L * LayoutSizes.arraySize(Heightmap.Types.values().length, LayoutSizes.REFERENCE);
            for (Heightmap heightmap : maps.values()) {
                BitStorage data = ((MockplayerHeightmapAccessor) heightmap).mockplayer$getData();
                bytes += LayoutSizes.shallowSize(Heightmap.class);
                bytes += LayoutSizes.shallowSize(data.getClass());
                bytes += storageArrayBytes(data);
            }
            return bytes;
        } catch (RuntimeException e) {
            warnAccessor("heightmaps");
            return 0;
        }
    }

    /**
     * 调色板容器堆字节：容器/Data/palette/storage 浅尺寸 + 存储 long[] + palette 内部数组。
     * accessor 未注入时降级为序列化字节（结构下界），不崩。
     */
    private static long containerBytes(PalettedContainerRO<?> ro) {
        try {
            PalettedContainer<?> container = (PalettedContainer<?>) ro;
            Object data = CONTAINER_DATA_FIELD.get(container);
            Palette<?> palette = ((MockplayerPalettedContainerDataAccessor) data).mockplayer$palette();
            BitStorage storage = ((MockplayerPalettedContainerDataAccessor) data).mockplayer$storage();
            return LayoutSizes.shallowSize(container.getClass())
                    + LayoutSizes.shallowSize(data.getClass())
                    + LayoutSizes.shallowSize(storage.getClass())
                    + storageArrayBytes(storage)
                    + paletteBytes(palette, storage.getBits())
                    + THREADING_HEAP;
        } catch (Exception e) {
            warnAccessor("palette");
            return ro.getSerializedSize();
        }
    }

    /** BitStorage 私有数组：SimpleBitStorage 记 long[]；ZeroBitStorage 的 RAW 是共享静态常量，记 0。 */
    private static long storageArrayBytes(BitStorage storage) {
        return storage instanceof SimpleBitStorage simple
                ? LayoutSizes.arraySize(simple.getRaw().length, Long.BYTES)
                : 0;
    }

    /**
     * 每个 PalettedContainer 自带的并发检测图（ThreadingDetector → Semaphore/ReentrantLock
     * + 各自的 NonfairSync），与容器数量成正比，常量公式即可，不需要 accessor。
     * NonfairSync 是包私有类，用 Class.forName 按二进制名取布局（只读字段列表，无访问）。
     */
    private static final long THREADING_HEAP = threadingHeap();

    private static long threadingHeap() {
        long total = LayoutSizes.shallowSize(net.minecraft.util.ThreadingDetector.class)
                + LayoutSizes.shallowSize(java.util.concurrent.Semaphore.class)
                + LayoutSizes.shallowSize(java.util.concurrent.locks.ReentrantLock.class);
        total += shallowByClassName("java.util.concurrent.Semaphore$NonfairSync");
        total += shallowByClassName("java.util.concurrent.locks.ReentrantLock$NonfairSync");
        return total;
    }

    private static long shallowByClassName(String binaryName) {
        try {
            return LayoutSizes.shallowSize(Class.forName(binaryName));
        } catch (ClassNotFoundException e) {
            return 0;
        }
    }

    /**
     * 调色板堆字节（按实际实现结构公式，值/注册表引用均为共享单例不计）：
     * - HashMapPalette：内部 bimap 的三个数组（keys/values/byId）按当前容量；
     * - LinearPalette：Object[1<<bits] 数组；
     * - SingleValue/Global/未知：只记浅尺寸。
     */
    private static long paletteBytes(Palette<?> palette, int bits) {
        if (palette instanceof HashMapPalette<?> map) {
            CrudeIncrementalIntIdentityHashBiMap<?> bi =
                    ((MockplayerHashMapPaletteAccessor) map).mockplayer$getValues();
            MockplayerCrudeBiMapAccessor acc = (MockplayerCrudeBiMapAccessor) bi;
            return LayoutSizes.shallowSize(HashMapPalette.class)
                    + LayoutSizes.shallowSize(bi.getClass())
                    + LayoutSizes.arraySize(acc.mockplayer$getKeys().length, LayoutSizes.REFERENCE)
                    + LayoutSizes.arraySize(acc.mockplayer$getValues().length, Integer.BYTES)
                    + LayoutSizes.arraySize(acc.mockplayer$getById().length, LayoutSizes.REFERENCE);
        }
        if (palette instanceof LinearPalette<?>) {
            return LayoutSizes.shallowSize(palette.getClass())
                    + LayoutSizes.arraySize(1 << bits, LayoutSizes.REFERENCE);
        }
        return LayoutSizes.shallowSize(palette.getClass());
    }

    /** accessor 缺失只告警一次（测试会锁死注入，线上降级不崩）。 */
    private static void warnAccessor(String what) {
        if (!warnedAccessor) {
            warnedAccessor = true;
            LOG.warn("内存记账 accessor 未注入（{}），区块结构降级为序列化字节", what);
        }
    }

    /** 方块实体估算：对象浅尺寸 + NBT 对象图堆字节（结构公式）。 */
    public static long estimateBlockEntity(BlockEntity blockEntity, HolderLookup.Provider registry) {
        return LayoutSizes.shallowSize(blockEntity.getClass())
                + nbtBytes(registry, output -> blockEntity.saveWithId(output));
    }

    /** 实体估算：对象浅尺寸 + NBT 对象图堆字节（结构公式）。 */
    public static long estimateEntity(Entity entity, HolderLookup.Provider registry) {
        return LayoutSizes.shallowSize(entity.getClass())
                + nbtBytes(registry, output -> entity.saveWithoutId(output))
                + entityRuntimeBytes(entity);
    }

    /**
     * 实体运行时结构（NBT 不含的部分）：SynchedEntityData 数组 + DataItem 对象、
     * 玩家物品栏/装备 NonNullList 数组 + 非空 ItemStack 对象、物品栏菜单槽位。
     * 值对象只记浅尺寸（内容由 NBT 堆公式覆盖），共享单例（ItemStack.EMPTY）不计。
     */
    private static long entityRuntimeBytes(Entity entity) {
        long total = 0;
        try {
            net.minecraft.network.syncher.SynchedEntityData data = entity.getEntityData();
            total += LayoutSizes.shallowSize(net.minecraft.network.syncher.SynchedEntityData.class);
            var items = ((MockplayerSynchedEntityDataAccessor) data).mockplayer$getItemsById();
            if (items != null) {
                total += LayoutSizes.arraySize(items.length, LayoutSizes.REFERENCE);
                for (var item : items) {
                    if (item != null) {
                        total += LayoutSizes.shallowSize(item.getClass());
                        Object value = item.getValue();
                        if (value != null) {
                            total += LayoutSizes.shallowSize(value.getClass());
                        }
                    }
                }
            }
            if (entity instanceof net.minecraft.world.entity.player.Player player) {
                var inventory = player.getInventory();
                total += nonNullListHeap(inventory.getNonEquipmentItems());
                var slots = player.inventoryMenu.slots;
                total += LayoutSizes.shallowSize(slots.getClass())
                        + LayoutSizes.arraySize(slots.size(), LayoutSizes.REFERENCE)
                        + (long) slots.size()
                        * LayoutSizes.shallowSize(net.minecraft.world.inventory.Slot.class);
            }
        } catch (RuntimeException e) {
            warnAccessor("entity");
        }
        return total;
    }

    /** NonNullList：对象浅尺寸 + 底层数组 + 非空 ItemStack 对象（EMPTY 是共享单例不计）。 */
    private static long nonNullListHeap(java.util.List<?> list) {
        long total = LayoutSizes.shallowSize(list.getClass())
                + LayoutSizes.arraySize(list.size(), LayoutSizes.REFERENCE);
        for (Object o : list) {
            if (o instanceof net.minecraft.world.item.ItemStack stack && !stack.isEmpty()) {
                total += LayoutSizes.shallowSize(net.minecraft.world.item.ItemStack.class);
            }
        }
        return total;
    }

    /** 把对象序列化进 TagValueOutput，并返回 NBT 对象图的堆字节（O(载荷) 公式，不走图）。 */
    private static long nbtBytes(HolderLookup.Provider registry,
                                 java.util.function.Consumer<TagValueOutput> save) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registry);
        save.accept(output);
        CompoundTag tag = output.buildResult();
        return tag == null ? 0 : StructureHeap.nbtTag(tag);
    }
}
