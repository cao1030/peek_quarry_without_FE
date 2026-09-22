package com.peek.quarry;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import cpw.mods.fml.common.network.IGuiHandler;

/**
 * 把 GUI id 映射到 Container / Gui。
 *
 * <p>注意：{@code getClientGuiElement} 里引用了客户端专属的 {@link GuiEnderTouch}，
 * 但只在<strong>方法体内</strong>引用 —— 类加载时不会解析它，
 * 所以这个类在 dedicated server 上是安全的。</p>
 */
public class GuiHandler implements IGuiHandler {

    public static final int GUI_ENDER_TOUCH = 0;

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id != GUI_ENDER_TOUCH) {
            return null;
        }
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileEntityEnderTouch) {
            return new ContainerEnderTouch(player.inventory, (TileEntityEnderTouch) te);
        }
        return null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id != GUI_ENDER_TOUCH) {
            return null;
        }
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileEntityEnderTouch) {
            return new GuiEnderTouch(player.inventory, (TileEntityEnderTouch) te);
        }
        return null;
    }
}
