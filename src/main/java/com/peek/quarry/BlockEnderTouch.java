package com.peek.quarry;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * 「末影之触」方块。
 *
 * <p>实体方块 + 右键打开区块扫描 GUI。扫描逻辑本身在 {@link TileEntityEnderTouch} 里。</p>
 *
 * <p>贴图：{@code src/main/resources/assets/peek_quarry/textures/blocks/ender_touch.png}
 * —— 现在是<strong>占位贴图</strong>：由原版命令方块贴图染色成绿色，可用
 * {@code tools/recolor_command_block.py} 重新生成。</p>
 */
public class BlockEnderTouch extends Block {

    /** 注册名，同时也是 {@code GameRegistry.registerBlock} 用的名字。 */
    public static final String NAME = "ender_touch";

    public BlockEnderTouch() {
        super(Material.rock);

        // 语言文件键：tile.peek_quarry.ender_touch.name
        setBlockName("peek_quarry." + NAME);
        // 贴图：assets/peek_quarry/textures/blocks/ender_touch.png
        setBlockTextureName("peek_quarry:" + NAME);

        setCreativeTab(CreativeTabs.tabBlock);
        setStepSound(Block.soundTypeStone);
        setHardness(3.0F);
        setResistance(15.0F);
    }

    @Override
    public boolean hasTileEntity(int metadata) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, int metadata) {
        return new TileEntityEnderTouch();
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player,
                                    int side, float subX, float subY, float subZ) {
        // 只在服务端开 GUI：EntityPlayer.openGui 在服务端会给客户端发 OpenGui 消息，
        // 两端都调的话客户端会先自己开一遍、再被服务端开一遍。
        if (!world.isRemote) {
            player.openGui(PeekQuarry.instance, GuiHandler.GUI_ENDER_TOUCH, world, x, y, z);
        }
        return true;
    }
}

