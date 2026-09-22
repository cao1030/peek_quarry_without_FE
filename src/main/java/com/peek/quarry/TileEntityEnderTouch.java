package com.peek.quarry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.IFluidBlock;

/**
 * 「末影之触」的 TileEntity：持有扫描配置、扫描进度和统计结果。
 *
 * <p>扫描<strong>只在服务端</strong>跑（{@link #updateEntity()} 里靠 {@code worldObj.isRemote} 挡掉客户端），
 * 而且**分摊到多个 tick**：每个 tick 最多花 {@link #TICK_BUDGET_NANOS} 做扫描，
 * 免得一次性遍历上千万个方块把服务端卡死。</p>
 *
 * <p>范围内的区块会被 {@code provideChunk} 强制加载（未生成过的会现场生成地形），
 * 这也是为什么进度条和「取消」是必需的。</p>
 */
public class TileEntityEnderTouch extends TileEntity {

    public static final int MAX_RADIUS = 8;

    public static final int STATE_IDLE = 0;
    public static final int STATE_SCANNING = 1;
    public static final int STATE_DONE = 2;
    public static final int STATE_CANCELLED = 3;
    /** 正在把扫描范围内的方块挖进容器。 */
    public static final int STATE_EXTRACTING = 4;

    /** Container 窗口属性 id（服务端 → 客户端同步进度用）。 */
    public static final int PROP_RADIUS = 0;
    public static final int PROP_STATE = 1;
    public static final int PROP_DONE = 2;
    public static final int PROP_TOTAL = 3;

    /** 每个 tick 的扫描时间预算（纳秒）。 */
    private static final long TICK_BUDGET_NANOS = 12000000L;

    /**
     * 提取时每个 tick 的时间预算。比扫描小得多 —— {@code setBlockToAir} 每个方块
     * 都要走一次 {@code World.setBlock} 里的 checkLight，比单纯读方块贵好几个数量级。
     */
    private static final long EXTRACT_BUDGET_NANOS = 8000000L;

    /** 提取时每个 tick 最多挖多少个，给时间预算兜底。 */
    private static final int MAX_REMOVALS_PER_TICK = 512;

    /** 写进 NBT 的结果条数上限，避免存档无限膨胀。 */
    private static final int MAX_SAVED_ENTRIES = 2048;

    /** 每隔多少 tick 尝试往相邻容器输出一次物品。 */
    private static final int EJECT_INTERVAL_TICKS = 8;

    /** 每个面每次输出最多多少个。 */
    private static final int EJECT_PER_SIDE = 64;

    // ------------------------------------------------------------------
    // 持久化状态
    // ------------------------------------------------------------------
    private int radius = 1;
    private int state = STATE_IDLE;
    private int chunksDone;
    private int chunksTotal;

    /** key -> int[1] 计数器。用 int[] 是为了避免每次 ++ 都装箱。 */
    private final Map<Integer, int[]> tally = new HashMap<Integer, int[]>();

    private List<BlockCount> results = new ArrayList<BlockCount>();
    private int resultVersion;
    private int totalBlocks;

    /**
     * 容器内容。这是<b>虚拟存储</b>：只记「种类 + 数量」，不是格子里的 ItemStack。
     * 扫描一次能挖出几十万个方块，按 64 一摞需要几千个槽位，实体物品栏放不下。
     */
    private List<BlockCount> storage = new ArrayList<BlockCount>();
    private int storageVersion;

    // ------------------------------------------------------------------
    // 扫描游标：仅服务端扫描期间有效，不写 NBT
    // ------------------------------------------------------------------
    private int cursor;
    private String ownerName;

    // ------------------------------------------------------------------
    // 提取任务游标：同样只在服务端、只在任务期间有效
    // ------------------------------------------------------------------
    private transient Block exBlock;
    private transient int exMeta;
    private transient int exRemaining;
    private transient int exRemoved;
    private transient int exChunk;
    private transient int exSection;
    private transient int exX;
    private transient int exY;
    private transient int exZ;
    private transient String exOwner;
    /** 区块处理顺序：按到中心区块的距离由近到远，这样挖掘是从方块身边向外扩散的。 */
    private transient int[] exOrder;

    /** 主动输出物品的节流计时器。 */
    private int ejectTimer;
    /** 输出成功时的日志冷却（次，每次 = EJECT_INTERVAL_TICKS）。 */
    private transient int ejectLogCooldown;
    /** 送不出去时的诊断日志冷却（次）。 */
    private transient int ejectDiagCooldown;

    /** 主动输出的日志节流：约 60 秒最多一条。 */
    private static final int EJECT_LOG_INTERVAL = 150;

    // ==================================================================
    // 读取
    // ==================================================================

    public int getRadius() {
        return radius;
    }

    public int getState() {
        return state;
    }

    public int getChunksDone() {
        return chunksDone;
    }

    public int getChunksTotal() {
        return chunksTotal;
    }

    public List<BlockCount> getResults() {
        return results;
    }

    public int getResultVersion() {
        return resultVersion;
    }

    public int getTotalBlocks() {
        return totalBlocks;
    }

    public boolean isScanning() {
        return state == STATE_SCANNING;
    }

    /** 半径 → 覆盖的区块边长。 */
    public static int sideFor(int radius) {
        return radius * 2 + 1;
    }

    public static int chunksFor(int radius) {
        int side = sideFor(radius);
        return side * side;
    }

    public static int clampRadius(int r) {
        if (r < 0) {
            return 0;
        }
        return r > MAX_RADIUS ? MAX_RADIUS : r;
    }

    // ==================================================================
    // 服务端控制
    // ==================================================================

    /** 开始扫描。必须在服务端线程调用。 */
    public void startScan(int requestedRadius, String owner) {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }
        radius = clampRadius(requestedRadius);
        chunksTotal = chunksFor(radius);
        chunksDone = 0;
        cursor = 0;
        ownerName = owner;
        state = STATE_SCANNING;

        tally.clear();
        results = new ArrayList<BlockCount>();
        totalBlocks = 0;
        resultVersion++;

        markDirty();
    }

    /** 取消扫描，保留已经扫到的部分结果。 */
    public void cancelScan() {
        if (state != STATE_SCANNING) {
            return;
        }
        state = STATE_CANCELLED;
        rebuildResults();
        ownerName = null;
        markDirty();
    }

    // ==================================================================
    // 容器（虚拟存储）
    // ==================================================================

    public List<BlockCount> getStorage() {
        return storage;
    }

    public int getStorageVersion() {
        return storageVersion;
    }

    public int getTotalStored() {
        int total = 0;
        for (int i = 0; i < storage.size(); i++) {
            total += storage.get(i).count;
        }
        return total;
    }

    public boolean isExtracting() {
        return state == STATE_EXTRACTING;
    }

    /** 扫描结果里这个种类还剩多少（提取上限）。没有则返回 0。 */
    public int countAvailable(Block block, int meta) {
        for (int i = 0; i < results.size(); i++) {
            BlockCount bc = results.get(i);
            if (bc.block == block && bc.meta == meta) {
                return bc.count;
            }
        }
        return 0;
    }

    private void addToStorage(Block block, int meta, int amount) {
        for (int i = 0; i < storage.size(); i++) {
            BlockCount bc = storage.get(i);
            if (bc.block == block && bc.meta == meta) {
                bc.count += amount;
                storageVersion++;
                return;
            }
        }
        storage.add(new BlockCount(block, meta, amount));
        storageVersion++;
    }

    /** 提取成功后，把统计里对应条目扣掉；扣到 0 就把这一条移除。 */
    private void removeFromResults(Block block, int meta, int amount) {
        for (int i = 0; i < results.size(); i++) {
            BlockCount bc = results.get(i);
            if (bc.block == block && bc.meta == meta) {
                bc.count -= amount;
                totalBlocks -= amount;
                if (bc.count <= 0) {
                    results.remove(i);
                }
                resultVersion++;
                return;
            }
        }
    }

    // ==================================================================
    // 提取（真实挖掉世界里的方块）
    // ==================================================================

    /**
     * 请求把扫描范围内 {@code amount} 个 (block, meta) 挖进容器。
     * 数量会被夹到「扫描结果里还剩多少」，不会凭空生成。
     */
    public void requestExtract(Block block, int meta, int amount, String owner) {
        if (worldObj == null || worldObj.isRemote || block == null) {
            return;
        }
        if (state == STATE_SCANNING || state == STATE_EXTRACTING) {
            return;   // 一次只干一件事
        }
        int available = countAvailable(block, meta);
        if (available <= 0) {
            return;
        }
        if (amount <= 0) {
            return;
        }
        if (amount > available) {
            amount = available;
        }

        exBlock = block;
        exMeta = meta;
        exRemaining = amount;
        exRemoved = 0;
        exChunk = 0;
        exSection = 0;
        exX = 0;
        exY = 0;
        exZ = 0;
        exOwner = owner;

        chunksTotal = chunksFor(radius);
        chunksDone = 0;
        exOrder = buildCenterOutOrder(radius);
        state = STATE_EXTRACTING;
        markDirty();

        PeekQuarry.logger.info("[{}] 开始提取 {} x{}（范围内还有 {} 个）",
                PeekQuarry.NAME, describe(block), amount, available);
    }

    /** 取消提取：已经挖掉的不会还原，但会照常进容器。 */
    public void cancelExtract() {
        if (state != STATE_EXTRACTING) {
            return;
        }
        finishExtract(true);
    }

    /**
     * GUI 上那个「取消」按钮：扫描和提取共用同一个按钮，
     * 所以这里按当前状态分派 —— 只判断扫描的话，提取中点取消会毫无反应。
     */
    public void cancelActiveTask() {
        if (state == STATE_SCANNING) {
            cancelScan();
        } else if (state == STATE_EXTRACTING) {
            cancelExtract();
        }
    }

    private void finishExtract(boolean cancelled) {
        if (exRemoved > 0) {
            addToStorage(exBlock, exMeta, exRemoved);
            removeFromResults(exBlock, exMeta, exRemoved);
        }
        state = cancelled ? STATE_CANCELLED : STATE_DONE;

        Block done = exBlock;
        int removed = exRemoved;
        String owner = exOwner;
        exBlock = null;
        exRemaining = 0;
        exRemoved = 0;
        exOwner = null;

        markDirty();

        PeekQuarry.logger.info("[{}] 提取{}：{} x{}", PeekQuarry.NAME,
                cancelled ? "已取消" : "完成", describe(done), removed);

        if (owner != null) {
            PacketHandler.sendResultsTo(this, owner);
            PacketHandler.sendStorageTo(this, owner);
        }
    }

    /**
     * 日志里怎么称呼一个方块。
     * 用注册名而不是 {@code getLocalizedName()} —— 服务端没有语言文件，
     * 本地化名会退化成 {@code tile.dirt.name} 这种原始键。
     */
    private static String describe(Block block) {
        if (block == null) {
            return "?";
        }
        String name = Block.blockRegistry.getNameForObject(block);
        return name == null ? String.valueOf(block) : name;
    }

    private void tickExtract() {
        long deadline = System.nanoTime() + EXTRACT_BUDGET_NANOS;
        int removals = 0;
        int side = sideFor(radius);
        int baseX = (xCoord >> 4) - radius;
        int baseZ = (zCoord >> 4) - radius;

        while (exRemaining > 0 && exChunk < chunksTotal) {
            if (System.nanoTime() > deadline || removals >= MAX_REMOVALS_PER_TICK) {
                break;
            }
            if (exSection > 15) {
                exSection = 0;
                exChunk++;
                chunksDone = exChunk;
                continue;
            }

            int index = exOrder == null ? exChunk : exOrder[exChunk];
            int cx = baseX + (index % side);
            int cz = baseZ + (index / side);

            ExtendedBlockStorage section = sectionAt(cx, cz, exSection);
            if (section == null) {
                exSection++;
                exX = 0;
                exY = 0;
                exZ = 0;
                continue;
            }

            Block here = section.getBlockByExtId(exX, exY, exZ);
            if (here == exBlock && section.getExtBlockMetadata(exX, exY, exZ) == exMeta) {
                worldObj.setBlockToAir((cx << 4) | exX, (exSection << 4) | exY, (cz << 4) | exZ);
                exRemaining--;
                exRemoved++;
                removals++;
            }

            if (++exX >= 16) {
                exX = 0;
                if (++exZ >= 16) {
                    exZ = 0;
                    if (++exY >= 16) {
                        exY = 0;
                        exSection++;
                    }
                }
            }
        }

        chunksDone = exChunk;
        if (exRemaining <= 0 || exChunk >= chunksTotal) {
            finishExtract(false);
        }
    }

    /** 把区块下标按「到中心区块的切比雪夫距离」由近到远排序。 */
    private static int[] buildCenterOutOrder(int radius) {
        int side = sideFor(radius);
        int total = side * side;
        final int[] order = new int[total];
        for (int i = 0; i < total; i++) {
            order[i] = i;
        }
        // 只有 <= 289 个元素，插入排序足够了，也避免为了排序引入装箱
        for (int i = 1; i < total; i++) {
            int key = order[i];
            int keyDist = ringDistance(key, side, radius);
            int j = i - 1;
            while (j >= 0 && ringDistance(order[j], side, radius) > keyDist) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = key;
        }
        return order;
    }

    private static int ringDistance(int index, int side, int radius) {
        int dx = Math.abs((index % side) - radius);
        int dz = Math.abs((index / side) - radius);
        return Math.max(dx, dz);
    }

    /** 取某个区块的某一段；区块不在（加载失败）时返回 null。 */
    private ExtendedBlockStorage sectionAt(int cx, int cz, int sectionIndex) {
        Chunk chunk;
        try {
            chunk = worldObj.getChunkProvider().provideChunk(cx, cz);
        } catch (Throwable t) {
            return null;
        }
        if (chunk == null) {
            return null;
        }
        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        if (sectionIndex >= sections.length) {
            return null;
        }
        ExtendedBlockStorage section = sections[sectionIndex];
        return (section == null || section.isEmpty()) ? null : section;
    }

    /** Container 的 updateProgressBar 在客户端调用。 */
    public void applyClientProperty(int id, int value) {
        switch (id) {
            case PROP_RADIUS:
                radius = clampRadius(value);
                break;
            case PROP_STATE:
                state = value;
                break;
            case PROP_DONE:
                chunksDone = value;
                break;
            case PROP_TOTAL:
                chunksTotal = value;
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // 客户端接收结果分片：begin → append* → end
    // 只在 end 时递增 resultVersion，这样 GUI 不会在收到一半时就重排列表
    // ------------------------------------------------------------------

    public void beginNetworkResults() {
        results = new ArrayList<BlockCount>();
        totalBlocks = 0;
    }

    public void appendNetworkResult(BlockCount entry) {
        results.add(entry);
        totalBlocks += entry.count;
    }

    public void endNetworkResults() {
        resultVersion++;
    }

    // ------------------------------------------------------------------
    // 客户端接收容器内容分片
    // ------------------------------------------------------------------

    public void beginNetworkStorage() {
        storage = new ArrayList<BlockCount>();
    }

    public void appendNetworkStorage(BlockCount entry) {
        storage.add(entry);
    }

    public void endNetworkStorage() {
        storageVersion++;
    }

    // ==================================================================
    // 主循环：扫描 / 提取是分摊到多个 tick 的增量任务，空闲时主动输出物品
    // ==================================================================

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }

        if (state == STATE_EXTRACTING) {
            tickExtract();
            return;
        }
        if (state == STATE_SCANNING) {
            tickScan();
            return;
        }

        // 只有在没有扫描/提取时才往外送，免得两个任务抢同一份存储
        tickEject();
    }

    private void tickScan() {
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        while (state == STATE_SCANNING && cursor < chunksTotal && System.nanoTime() < deadline) {
            scanChunk(cursor);
            cursor++;
        }

        chunksDone = cursor;

        if (cursor >= chunksTotal) {
            finishScan();
        }
    }

    // ==================================================================
    // 主动输出：把容器里的物品推进相邻的容器
    // ==================================================================

    /**
     * 每隔 {@link #EJECT_INTERVAL_TICKS} 个 tick，往六个方向各尝试送出一批物品。
     *
     * <p>只要相邻方块实现了 {@link IInventory}（箱子、漏斗、熔炉、模组机器…）就会送，
     * 会遵守 {@link ISidedInventory} 的槽位访问与 {@code canInsertItem} 规则。</p>
     *
     * <p>送出后直接扣减虚拟存储，不再回滚 —— 物品这时候已经真的在隔壁容器里了。</p>
     */
    private void tickEject() {
        if (storage.isEmpty()) {
            ejectTimer = 0;
            return;
        }
        if (++ejectTimer < EJECT_INTERVAL_TICKS) {
            return;
        }
        ejectTimer = 0;
        if (ejectLogCooldown > 0) {
            ejectLogCooldown--;
        }

        boolean changed = false;
        int found = 0;
        for (int side = 0; side < 6; side++) {
            ForgeDirection dir = ForgeDirection.getOrientation(side);
            int nx = xCoord + dir.offsetX;
            int ny = yCoord + dir.offsetY;
            int nz = zCoord + dir.offsetZ;
            TileEntity neighbour = worldObj.getTileEntity(nx, ny, nz);
            if (!(neighbour instanceof IInventory)) {
                continue;
            }
            found++;
            if (ejectInto((IInventory) neighbour, side, nx, ny, nz)) {
                changed = true;
            }
        }

        if (changed) {
            storageVersion++;
            markDirty();
        } else if (--ejectDiagCooldown <= 0) {
            // 限流诊断：区分「旁边没有容器」和「有容器但塞不进去」
            ejectDiagCooldown = EJECT_LOG_INTERVAL;
            PeekQuarry.logger.info("[{}] ({}, {}, {}) 待输出 {} 种，相邻容器 {} 个，本轮没送出去任何东西",
                    PeekQuarry.NAME, xCoord, yCoord, zCoord, storage.size(), found);
        }
    }

    /** 往一个容器里送出一批物品。送出去了返回 true。 */
    private boolean ejectInto(IInventory inventory, int side, int nx, int ny, int nz) {
        int[] slots = accessibleSlots(inventory, side);
        if (slots == null || slots.length == 0) {
            return false;
        }
        ISidedInventory sided = inventory instanceof ISidedInventory ? (ISidedInventory) inventory : null;

        for (int i = 0; i < storage.size(); i++) {
            BlockCount bc = storage.get(i);
            ItemStack proto = bc.getStack();
            if (proto == null) {
                continue;   // 没有对应物品的方块（甘蔗、农作物…）没法弹出，只能留在容器里
            }

            int remaining = Math.min(bc.count, EJECT_PER_SIDE);
            int moved = 0;

            for (int k = 0; k < slots.length && remaining > 0; k++) {
                int slot = slots[k];
                if (!inventory.isItemValidForSlot(slot, proto)) {
                    continue;
                }
                ItemStack existing = inventory.getStackInSlot(slot);
                int put;

                if (existing == null) {
                    put = Math.min(remaining,
                            Math.min(proto.getMaxStackSize(), inventory.getInventoryStackLimit()));
                    if (put <= 0) {
                        continue;
                    }
                    ItemStack stack = proto.copy();
                    stack.stackSize = put;
                    if (sided != null && !sided.canInsertItem(slot, stack, side)) {
                        continue;
                    }
                    inventory.setInventorySlotContents(slot, stack);
                } else {
                    if (!existing.isItemEqual(proto) || !ItemStack.areItemStackTagsEqual(existing, proto)) {
                        continue;
                    }
                    int limit = Math.min(existing.getMaxStackSize(), inventory.getInventoryStackLimit());
                    put = Math.min(remaining, limit - existing.stackSize);
                    if (put <= 0) {
                        continue;
                    }
                    ItemStack merged = existing.copy();
                    merged.stackSize += put;
                    if (sided != null && !sided.canInsertItem(slot, merged, side)) {
                        continue;
                    }
                    inventory.setInventorySlotContents(slot, merged);
                }

                remaining -= put;
                moved += put;
            }

            if (moved > 0) {
                bc.count -= moved;
                if (bc.count <= 0) {
                    storage.remove(i);
                }
                inventory.markDirty();
                if (ejectLogCooldown <= 0) {
                    // 限流：每 8 tick 就可能送一次，不限流会把日志刷爆
                    ejectLogCooldown = EJECT_LOG_INTERVAL;
                    PeekQuarry.logger.info("[{}] 输出 {} x{} -> ({}, {}, {})",
                            PeekQuarry.NAME, describe(bc.block), moved, nx, ny, nz);
                }
                return true;
            }
        }
        return false;
    }

    private static int[] accessibleSlots(IInventory inventory, int side) {
        if (inventory instanceof ISidedInventory) {
            return ((ISidedInventory) inventory).getAccessibleSlotsFromSide(side);
        }
        int size = inventory.getSizeInventory();
        int[] slots = new int[size];
        for (int i = 0; i < size; i++) {
            slots[i] = i;
        }
        return slots;
    }

    /** 扫描第 {@code index} 个区块（按行优先顺序铺开）。 */
    private void scanChunk(int index) {
        int side = sideFor(radius);
        int baseX = (xCoord >> 4) - radius;
        int baseZ = (zCoord >> 4) - radius;
        int cx = baseX + (index % side);
        int cz = baseZ + (index / side);

        // 强制加载（未生成过的会现场生成地形）——这是玩家在 GUI 里明确选的代价
        Chunk chunk;
        try {
            chunk = worldObj.getChunkProvider().provideChunk(cx, cz);
        } catch (Throwable t) {
            PeekQuarry.logger.warn("[{}] 加载区块 ({}, {}) 失败，跳过", PeekQuarry.NAME, cx, cz, t);
            return;
        }
        if (chunk == null) {
            return;
        }

        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        for (int s = 0; s < sections.length; s++) {
            ExtendedBlockStorage section = sections[s];
            if (section == null || section.isEmpty()) {
                continue;   // 整段是空气，直接跳过（大多数 section 都走这条路）
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Block block = section.getBlockByExtId(x, y, z);
                        if (block == null || block.getMaterial() == Material.air) {
                            continue;
                        }
                        if (isExcluded(block)) {
                            continue;
                        }
                        int key = BlockCount.makeKey(block, section.getExtBlockMetadata(x, y, z));
                        int[] counter = tally.get(key);
                        if (counter == null) {
                            tally.put(key, new int[] { 1 });
                        } else {
                            counter[0]++;
                        }
                    }
                }
            }
        }
    }

    /**
     * 不计入统计的方块：<b>流体</b>和<b>基岩</b>。
     *
     * <p>流体判断走两条路 —— 原版的水/岩浆材质是液体材质，
     * 而模组流体（比如 HBM 那一大堆）通常实现 {@link IFluidBlock}，
     * 只判断材质会漏掉后者。</p>
     *
     * <p>要加别的排除项（比如刷怪笼、命令方块），往这里加一条就行。</p>
     */
    private static boolean isExcluded(Block block) {
        if (block == Blocks.bedrock) {
            return true;
        }
        Material material = block.getMaterial();
        if (material != null && material.isLiquid()) {
            return true;
        }
        return block instanceof IFluidBlock;
    }

    private void finishScan() {
        state = STATE_DONE;
        chunksDone = chunksTotal;
        rebuildResults();
        markDirty();

        String owner = ownerName;
        ownerName = null;
        if (owner != null) {
            PacketHandler.sendResultsTo(this, owner);
        }
    }

    /** 把 tally 转成展示用的有序列表。 */
    private void rebuildResults() {
        List<BlockCount> list = new ArrayList<BlockCount>(tally.size());
        int total = 0;
        for (Map.Entry<Integer, int[]> entry : tally.entrySet()) {
            Block block = BlockCount.blockFromKey(entry.getKey());
            if (block == null) {
                continue;
            }
            int count = entry.getValue()[0];
            list.add(new BlockCount(block, BlockCount.metaFromKey(entry.getKey()), count));
            total += count;
        }
        results = list;
        totalBlocks = total;
        resultVersion++;
    }

    /** 供网络包使用：按数量降序，方便分片发送时大项优先。 */
    public List<BlockCount> sortedResults() {
        List<BlockCount> copy = new ArrayList<BlockCount>(results);
        Collections.sort(copy, new Comparator<BlockCount>() {
            @Override
            public int compare(BlockCount a, BlockCount b) {
                return Integer.compare(b.count, a.count);
            }
        });
        return copy;
    }

    // ==================================================================
    // NBT
    // ==================================================================

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setInteger("Radius", radius);
        // 存档时不保留进行中的任务：重开档不会自动接着扫/接着挖
        tag.setInteger("State",
                (state == STATE_SCANNING || state == STATE_EXTRACTING) ? STATE_CANCELLED : state);
        tag.setInteger("ChunksDone", chunksDone);
        tag.setInteger("ChunksTotal", chunksTotal);
        tag.setTag("Results", writeEntries(results, "统计结果"));
        tag.setInteger("TotalBlocks", totalBlocks);
        tag.setTag("Storage", writeEntries(storage, "容器内容"));
    }

    /** 统计结果和容器内容是同一套「种类 + 数量」结构，共用读写。 */
    private NBTTagList writeEntries(List<BlockCount> source, String what) {
        NBTTagList list = new NBTTagList();
        int written = 0;
        for (int i = 0; i < source.size() && written < MAX_SAVED_ENTRIES; i++) {
            BlockCount bc = source.get(i);
            String name = Block.blockRegistry.getNameForObject(bc.block);
            if (name == null) {
                // 反查不到注册名就会丢行 —— 不能静默吞掉，否则存档回来会莫名少几条
                PeekQuarry.logger.warn("[{}] 方块 {} 取不到注册名，{} 这一条不会被保存",
                        PeekQuarry.NAME, bc.block, what);
                continue;
            }
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("Block", name);
            entry.setByte("Meta", (byte) bc.meta);
            entry.setInteger("Count", bc.count);
            list.appendTag(entry);
            written++;
        }
        if (written < source.size()) {
            PeekQuarry.logger.warn("[{}] {} 只保存了 {}/{} 条", PeekQuarry.NAME, what, written, source.size());
        }
        return list;
    }

    private List<BlockCount> readEntries(NBTTagCompound tag, String key, String what, int[] totalOut) {
        List<BlockCount> list = new ArrayList<BlockCount>();
        int total = 0;
        NBTTagList tagList = tag.getTagList(key, 10);
        for (int i = 0; i < tagList.tagCount(); i++) {
            NBTTagCompound entry = tagList.getCompoundTagAt(i);
            String name = entry.getString("Block");
            Object obj = Block.blockRegistry.getObject(name);
            if (!(obj instanceof Block)) {
                PeekQuarry.logger.warn("[{}] 存档里的方块名 '{}' 解析不回方块，{} 这条被丢弃",
                        PeekQuarry.NAME, name, what);
                continue;
            }
            int count = entry.getInteger("Count");
            list.add(new BlockCount((Block) obj, entry.getByte("Meta") & 15, count));
            total += count;
        }
        if (totalOut != null) {
            totalOut[0] = total;
        }
        return list;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        radius = clampRadius(tag.getInteger("Radius"));
        state = tag.getInteger("State");
        chunksDone = tag.getInteger("ChunksDone");
        chunksTotal = tag.getInteger("ChunksTotal");

        int[] total = new int[1];
        results = readEntries(tag, "Results", "统计结果", total);
        // 以解析出来的条目为准重算总数。存档里的 TotalBlocks 只在列表为空时兜底 ——
        // 否则一旦有行被丢弃，状态栏的总数就和列表对不上了（曾经真的发生过）。
        totalBlocks = results.isEmpty()
                ? (tag.hasKey("TotalBlocks") ? tag.getInteger("TotalBlocks") : 0)
                : total[0];
        resultVersion++;

        storage = readEntries(tag, "Storage", "容器内容", null);
        storageVersion++;
    }
}
