package com.peek.quarry;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/**
 * 扫描结果里的一条：某种方块（{@link Block} + metadata）在扫描范围内共有多少个。
 *
 * <p>显示名和图标是<strong>惰性计算并缓存</strong>的 —— GUI 每帧都要为每一行取名字，
 * 不缓存的话每帧都会新建 ItemStack。</p>
 */
public class BlockCount {

    public final Block block;
    public final int meta;
    public int count;

    private boolean stackResolved;
    private ItemStack cachedStack;
    private String cachedName;
    private String cachedSearch;

    public BlockCount(Block block, int meta, int count) {
        this.block = block;
        this.meta = meta;
        this.count = count;
    }

    // ------------------------------------------------------------------
    // 扫描累加用的 key 编解码：blockId 占高位，metadata 占低 4 位
    // ------------------------------------------------------------------
    public static int makeKey(Block block, int meta) {
        return (Block.getIdFromBlock(block) << 4) | (meta & 15);
    }

    public static Block blockFromKey(int key) {
        return Block.getBlockById(key >> 4);
    }

    public static int metaFromKey(int key) {
        return key & 15;
    }

    // ------------------------------------------------------------------
    // 显示
    // ------------------------------------------------------------------

    /** GUI 里用的物品形态；这个方块没有对应物品时返回 {@code null}。 */
    public ItemStack getStack() {
        if (!stackResolved) {
            stackResolved = true;
            Item item = Item.getItemFromBlock(block);
            // Item.getItemFromBlock 是按「方块 ID → 物品 ID」反查的（见 1.7.10 源码），
            // 所以必须确认拿到的 ItemBlock 真的属于这个方块 —— 否则会显示成别的方块。
            // 没有 ItemBlock 的方块（甘蔗、农作物、水/岩浆这类）会走到调用方画方块图标的分支。
            if (!(item instanceof ItemBlock) || ((ItemBlock) item).field_150939_a != block) {
                return null;
            }
            try {
                cachedStack = new ItemStack(item, 1, meta);
            } catch (Throwable ignored) {
                cachedStack = null;
            }
        }
        return cachedStack;
    }

    /**
     * 造一个指定数量的 ItemStack，用于把物品输出到相邻容器。
     * 没有对应物品（{@link #getStack()} 为 null）时返回 null。
     */
    public ItemStack createStack(int size) {
        ItemStack proto = getStack();
        if (proto == null || size <= 0) {
            return null;
        }
        ItemStack copy = proto.copy();
        copy.stackSize = Math.min(size, copy.getMaxStackSize());
        return copy;
    }

    public String getDisplayName() {
        if (cachedName == null) {
            String name = null;
            ItemStack stack = getStack();
            if (stack != null) {
                try {
                    name = stack.getDisplayName();
                } catch (Throwable ignored) {
                    name = null;
                }
            }
            if (name == null || name.isEmpty()) {
                name = block.getLocalizedName();
            }
            cachedName = (name == null || name.isEmpty()) ? "?" : name;
        }
        return cachedName;
    }

    /**
     * 搜索索引：显示名原文 + 全部拼音组合 + 全部首字母组合，换行分隔。
     * 由 {@link PinyinHelper} 生成并在这里缓存 —— GUI 每帧都会取，不能每次重算。
     */
    public String getSearchText() {
        if (cachedSearch == null) {
            cachedSearch = PinyinHelper.buildSearchText(getDisplayName());
        }
        return cachedSearch;
    }
}
