package com.peek.quarry;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;

/**
 * 「末影之触」GUI 的 Container。
 *
 * <p>没有任何 Slot —— 这个 GUI 不是物品栏，纯粹是配置 + 结果展示。
 * 它唯一的职责是把服务端的扫描进度用 <strong>窗口属性</strong>同步给客户端
 * （就是原版熔炉同步燃烧进度的那套机制，比自己发进度包省事也不占带宽）。</p>
 */
public class ContainerEnderTouch extends Container {

    private final TileEntityEnderTouch te;

    // 上次发过的值，只有变化时才发
    private int lastRadius = Integer.MIN_VALUE;
    private int lastState = Integer.MIN_VALUE;
    private int lastDone = Integer.MIN_VALUE;
    private int lastTotal = Integer.MIN_VALUE;

    public ContainerEnderTouch(InventoryPlayer playerInventory, TileEntityEnderTouch te) {
        this.te = te;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return te.getDistanceFrom(player.posX, player.posY, player.posZ) <= 64.0D;
    }

    @Override
    public void addCraftingToCrafters(ICrafting crafter) {
        super.addCraftingToCrafters(crafter);
        crafter.sendProgressBarUpdate(this, TileEntityEnderTouch.PROP_RADIUS, te.getRadius());
        crafter.sendProgressBarUpdate(this, TileEntityEnderTouch.PROP_STATE, te.getState());
        crafter.sendProgressBarUpdate(this, TileEntityEnderTouch.PROP_DONE, te.getChunksDone());
        crafter.sendProgressBarUpdate(this, TileEntityEnderTouch.PROP_TOTAL, te.getChunksTotal());
        lastRadius = te.getRadius();
        lastState = te.getState();
        lastDone = te.getChunksDone();
        lastTotal = te.getChunksTotal();
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();

        for (int i = 0; i < this.crafters.size(); i++) {
            ICrafting crafter = (ICrafting) this.crafters.get(i);

            if (lastRadius != te.getRadius()) {
                crafter.sendProgressBarUpdate(this, TileEntityEnderTouch.PROP_RADIUS, te.getRadius());
                lastRadius = te.getRadius();
            }
            if (lastState != te.getState()) {
                crafter.sendProgressBarUpdate(this, TileEntityEnderTouch.PROP_STATE, te.getState());
                lastState = te.getState();
            }
            if (lastDone != te.getChunksDone()) {
                crafter.sendProgressBarUpdate(this, TileEntityEnderTouch.PROP_DONE, te.getChunksDone());
                lastDone = te.getChunksDone();
            }
            if (lastTotal != te.getChunksTotal()) {
                crafter.sendProgressBarUpdate(this, TileEntityEnderTouch.PROP_TOTAL, te.getChunksTotal());
                lastTotal = te.getChunksTotal();
            }
        }
    }

    @Override
    public void updateProgressBar(int id, int value) {
        te.applyClientProperty(id, value);
    }
}
