package com.peek.quarry;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import org.apache.logging.log4j.Logger;

/**
 * PEEK Quarry 的主类。
 *
 * <p>注册{@link BlockEnderTouch 末影之触}方块及其 TileEntity / GUI / 网络通道，以及合成配方。</p>
 */
@Mod(
    modid = PeekQuarry.MODID,
    name = PeekQuarry.NAME,
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]"
)
public class PeekQuarry {

    public static final String MODID = "peek_quarry";
    public static final String NAME = "PEEK Quarry";

    /** FML 会把主类实例注入到这个字段。 */
    @Mod.Instance(MODID)
    public static PeekQuarry instance;

    public static Logger logger;

    /** 「末影之触」方块。 */
    public static BlockEnderTouch enderTouch;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        logger.info("[{}] preInit 开始", NAME);

        enderTouch = new BlockEnderTouch();
        GameRegistry.registerBlock(enderTouch, BlockEnderTouch.NAME);

        GameRegistry.registerTileEntity(TileEntityEnderTouch.class, "peek_quarry.ender_touch");

        NetworkRegistry.INSTANCE.registerGuiHandler(instance, new GuiHandler());
        PacketHandler.init();

        logger.info("[{}] preInit 完成", NAME);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        addRecipes();
        logger.info("[{}] init 完成", NAME);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        verifyRecipe();
        logger.info("[{}] postInit 完成，modid={} version={}", NAME, MODID, Tags.VERSION);
    }

    // ==================================================================
    // 合成配方
    // ==================================================================

    /**
     * 末影之触的合成表：
     *
     * <pre>
     *   铁块    末影之眼   铁块
     *   末影之眼  钻石镐   末影之眼
     *   铁块    末影之眼   铁块
     * </pre>
     */
    private void addRecipes() {
        GameRegistry.addRecipe(new ItemStack(enderTouch), new Object[] {
                "IXI",
                "XPX",
                "IXI",
                'I', Blocks.iron_block,
                'X', Items.ender_eye,
                'P', Items.diamond_pickaxe
        });
        logger.info("[{}] 末影之触配方已登记", NAME);
    }

    /**
     * 启动自检：拿一个 3×3 的合成格按配方摆一遍，问 {@link CraftingManager} 能不能配出这个方块。
     *
     * <p>配方注册是最容易「静默失效」的一类问题 —— 图案字符写错、材料用错，
     * 注册调用本身照样成功、日志一行不报，只是玩家永远合不出来。
     * 所以这里主动验证一次，结果打进日志。</p>
     */
    private void verifyRecipe() {
        try {
            InventoryCrafting grid = new InventoryCrafting(new Container() {
                @Override
                public boolean canInteractWith(EntityPlayer player) {
                    return false;
                }
            }, 3, 3);

            ItemStack iron = new ItemStack(Blocks.iron_block);
            ItemStack eye = new ItemStack(Items.ender_eye);
            ItemStack pick = new ItemStack(Items.diamond_pickaxe);
            ItemStack[] cells = {
                    iron, eye, iron,
                    eye, pick, eye,
                    iron, eye, iron
            };
            for (int i = 0; i < cells.length; i++) {
                grid.setInventorySlotContents(i, cells[i]);
            }

            ItemStack result = CraftingManager.getInstance().findMatchingRecipe(grid, null);
            boolean ok = result != null && result.getItem() == Item.getItemFromBlock(enderTouch);
            if (ok) {
                logger.info("[{}] 配方自检通过：按合成表摆出来的是 {}", NAME, result.getDisplayName());
            } else {
                logger.error("[{}] 配方自检失败：按合成表摆出来的结果是 {}",
                        NAME, result == null ? "无" : result.getDisplayName());
            }
        } catch (Throwable t) {
            logger.error("[{}] 配方自检时出错", NAME, t);
        }
    }
}
