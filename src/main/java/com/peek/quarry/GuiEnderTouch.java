package com.peek.quarry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * 「末影之触」的扫描 GUI：配置区块半径 → 确认 → 展示统计结果。
 *
 * <p>布局参考 AE2 的存储终端：一个可滚动的「图标 + 名称 + 数量」列表，
 * 上面带搜索框和按数量排序的开关。</p>
 *
 * <p>注意坐标系：{@code drawGuiContainerBackgroundLayer} 是<strong>绝对</strong>坐标，
 * {@code drawGuiContainerForegroundLayer} 已经被平移过、是<strong>相对于 guiLeft/guiTop</strong> 的坐标。
 * 混了就会画歪。</p>
 */
public class GuiEnderTouch extends GuiContainer {

    private static final int PANEL_BG = 0xFF1A1A20;
    private static final int PANEL_BORDER = 0xFF55555F;
    private static final int LIST_BG = 0xFF0D0D11;
    private static final int LIST_BORDER = 0xFF33333D;
    private static final int ROW_HOVER = 0xFF2C2C3A;
    private static final int BAR_BG = 0xFF26262E;
    private static final int BAR_FILL = 0xFF3FA34D;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int TEXT_DIM = 0xFF9098A0;
    private static final int TEXT_COUNT = 0xFFFFD24A;

    private static final int ROW_H = 18;
    private static final int LIST_TOP = 90;
    private static final int LIST_X = 6;

    /** 左侧「扫描 + 统计列表」那一栏的宽度（原来的整个 GUI 就是这么大）。 */
    private static final int BASE_W = 246;
    /** 右侧容器侧边栏宽度。 */
    private static final int SIDEBAR_W = 118;
    private static final int SIDEBAR_GAP = 8;
    private static final int SIDEBAR_TOP = 20;

    private final TileEntityEnderTouch te;

    // ---- UI 状态 ----
    private int radius = 1;
    /** 玩家手动调过半径之后就不再被服务端回传的值覆盖。 */
    private boolean radiusDirty;
    private boolean sortDescending = true;
    private String searchText = "";
    private int scroll;
    /** 已经向服务端要过已有结果了（每个 GUI 实例只请求一次）。 */
    private boolean requestedResults;

    // ---- 控件 ----
    private GuiTextField searchField;
    private GuiButton btnMinus;
    private GuiButton btnPlus;
    private GuiButton btnConfirm;
    private GuiButton btnCancel;
    private GuiButton btnSort;

    // ---- 列表视图缓存 ----
    private List<BlockCount> view = new ArrayList<BlockCount>();
    /** 过滤后列表里的方块总数（状态栏显示用，搜索时要跟着变）。 */
    private int viewTotal;
    private int viewVersion = -1;
    private String viewSearch = null;
    private boolean viewSortDesc;
    private int visibleRows = 1;

    // ---- 侧边栏（容器内容，只读） ----
    private int sidebarX;
    private int sidebarH;
    private int sidebarRows = 1;
    private int sidebarScroll;
    private List<BlockCount> storageView = new ArrayList<BlockCount>();
    private int storageViewVersion = -1;
    /** 侧边栏定时向服务端拉取容器内容 —— 方块会主动往隔壁容器送东西。 */
    private int storageRefreshTimer;

    // ---- 数量选择弹窗 ----
    /** 非 null 表示弹窗开着，正在为这个条目选数量。 */
    private BlockCount selecting;
    private GuiTextField amountField;
    private int popupX;
    private int popupY;
    private int popupW = 200;
    private int popupH = 96;
    /** 输入不合法时的红字提示。 */
    private String popupError;

    private final Comparator<BlockCount> byCountDesc = new Comparator<BlockCount>() {
        @Override
        public int compare(BlockCount a, BlockCount b) {
            if (a.count != b.count) {
                return a.count > b.count ? -1 : 1;
            }
            return a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
        }
    };

    private final Comparator<BlockCount> byCountAsc = new Comparator<BlockCount>() {
        @Override
        public int compare(BlockCount a, BlockCount b) {
            if (a.count != b.count) {
                return a.count < b.count ? -1 : 1;
            }
            return a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
        }
    };

    public GuiEnderTouch(InventoryPlayer playerInventory, TileEntityEnderTouch te) {
        super(new ContainerEnderTouch(playerInventory, te));
        this.te = te;
        this.radius = te.getRadius();
        this.xSize = BASE_W + SIDEBAR_GAP + SIDEBAR_W;
        this.ySize = 224;
    }

    // ==================================================================
    // 初始化 / 每 tick
    // ==================================================================

    @Override
    public void initGui() {
        // 窗口太小就压缩，免得 GUI 被挤到屏幕外
        this.xSize = Math.min(BASE_W + SIDEBAR_GAP + SIDEBAR_W, this.width - 16);
        this.ySize = Math.min(224, this.height - 16);
        super.initGui();

        int left = this.guiLeft;
        int top = this.guiTop;
        // 左边这一栏的右边界（按钮都贴着它排），不是整个 GUI 的右边界
        int leftEdge = left + Math.min(BASE_W, this.xSize);
        visibleRows = Math.max(1, (this.ySize - LIST_TOP - 6) / ROW_H);

        // 侧边栏：贴着左栏右边排，宽度吃掉剩下的空间
        sidebarX = Math.min(BASE_W, this.xSize) + SIDEBAR_GAP;
        sidebarH = this.ySize - SIDEBAR_TOP - 6;
        sidebarRows = Math.max(1, sidebarH / ROW_H);

        this.buttonList.clear();
        btnMinus = new GuiButton(0, left + 46, top + 20, 20, 18, "-");
        btnPlus = new GuiButton(1, left + 98, top + 20, 20, 18, "+");
        btnConfirm = new GuiButton(2, leftEdge - 72, top + 20, 66, 18,
                StatCollector.translateToLocal("gui.peek_quarry.ender_touch.confirm"));
        btnCancel = new GuiButton(3, leftEdge - 72, top + 39, 66, 16,
                StatCollector.translateToLocal("gui.peek_quarry.ender_touch.cancel"));
        btnSort = new GuiButton(4, leftEdge - 72, top + 68, 66, 16, "");
        this.buttonList.add(btnMinus);
        this.buttonList.add(btnPlus);
        this.buttonList.add(btnConfirm);
        this.buttonList.add(btnCancel);
        this.buttonList.add(btnSort);

        int baseW = Math.min(BASE_W, this.xSize);
        searchField = new GuiTextField(this.fontRendererObj, left + LIST_X, top + 68, baseW - 84, 16);
        searchField.setMaxStringLength(64);
        searchField.setText(searchText);
        searchField.setFocused(false);

        // 数量输入框：位置在弹窗里算，这里只负责创建
        popupW = Math.min(200, this.xSize - 20);
        // 高度要留出「确认/取消」下面那行提示文字，否则会压在按钮上
        popupH = 114;
        popupX = left + (this.xSize - popupW) / 2;
        popupY = top + (this.ySize - popupH) / 2;
        amountField = new GuiTextField(this.fontRendererObj, popupX + 8, popupY + 30, popupW - 16, 18);
        amountField.setMaxStringLength(9);
        amountField.setFocused(false);
        if (selecting != null && amountField.getText().isEmpty()) {
            amountField.setText(String.valueOf(selecting.count));
        }

        // 结果列表只存在于服务端的 TileEntity 里（含从 NBT 恢复的），没有任何自动同步：
        // 半径和进度走 Container 窗口属性，但列表太大不适合那么传。
        // 所以打开界面时必须主动要一次，否则重进世界后列表会是空的。
        if (!requestedResults) {
            requestedResults = true;
            PacketHandler.CHANNEL.sendToServer(
                    new PacketHandler.MsgRequestResults(te.xCoord, te.yCoord, te.zCoord));
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (searchField != null) {
            searchField.updateCursorCounter();
            if (!searchText.equals(searchField.getText())) {
                searchText = searchField.getText();
            }
        }
        if (amountField != null) {
            amountField.updateCursorCounter();
        }
        refreshStorageView();

        // 容器内容会自己变（方块在往外送），所以定时拉一次，否则侧边栏是死的
        if (++storageRefreshTimer >= 20) {
            storageRefreshTimer = 0;
            PacketHandler.CHANNEL.sendToServer(
                    new PacketHandler.MsgRequestStorage(te.xCoord, te.yCoord, te.zCoord));
        }

        // 服务端回传的半径（首次打开时生效；玩家自己调过之后就不再覆盖）
        if (!radiusDirty) {
            int fromServer = te.getRadius();
            if (fromServer != radius) {
                radius = fromServer;
                scroll = 0;
            }
        }

        boolean busy = te.isScanning() || te.isExtracting();
        btnConfirm.enabled = !busy;
        btnCancel.enabled = busy;
        btnMinus.enabled = !busy;
        btnPlus.enabled = !busy;
        btnSort.displayString = StatCollector.translateToLocal(sortDescending
                ? "gui.peek_quarry.ender_touch.sort_desc"
                : "gui.peek_quarry.ender_touch.sort_asc");
    }

    private void refreshStorageView() {
        int version = te.getStorageVersion();
        if (version == storageViewVersion) {
            return;
        }
        storageViewVersion = version;
        List<BlockCount> list = new ArrayList<BlockCount>(te.getStorage());
        Collections.sort(list, byCountDesc);
        storageView = list;
        clampSidebarScroll();
    }

    private void clampSidebarScroll() {
        int max = Math.max(0, storageView.size() - sidebarRows);
        if (sidebarScroll > max) {
            sidebarScroll = max;
        }
        if (sidebarScroll < 0) {
            sidebarScroll = 0;
        }
    }

    // ==================================================================
    // 交互
    // ==================================================================

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
                radius = TileEntityEnderTouch.clampRadius(radius - 1);
                radiusDirty = true;
                scroll = 0;
                break;
            case 1:
                radius = TileEntityEnderTouch.clampRadius(radius + 1);
                radiusDirty = true;
                scroll = 0;
                break;
            case 2:
                PacketHandler.CHANNEL.sendToServer(
                        new PacketHandler.MsgStartScan(te.xCoord, te.yCoord, te.zCoord, radius));
                scroll = 0;
                break;
            case 3:
                PacketHandler.CHANNEL.sendToServer(
                        new PacketHandler.MsgCancelScan(te.xCoord, te.yCoord, te.zCoord));
                break;
            case 4:
                sortDescending = !sortDescending;
                scroll = 0;
                break;
            default:
                break;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // 弹窗是模态的：开着的时候按键全部归它
        if (selecting != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                closePopup();
                return;
            }
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                confirmPopup();
                return;
            }
            amountField.textboxKeyTyped(typedChar, keyCode);
            return;
        }

        if (searchField != null && searchField.isFocused()) {
            if (searchField.textboxKeyTyped(typedChar, keyCode)) {
                return;
            }
            // 搜索框聚焦时吞掉其它按键，否则按 E / 数字键会关界面或切物品栏
            if (keyCode != Keyboard.KEY_ESCAPE) {
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (selecting != null) {
            handlePopupClick(mouseX, mouseY, button);
            return;   // 模态：不要透给底下的列表 / 按钮
        }

        // 中键点统计列表里的某一行 → 打开数量选择弹窗
        if (button == 2) {
            int row = rowAt(mouseX, mouseY);
            if (row >= 0 && row < view.size()) {
                openPopup(view.get(row));
                return;
            }
            // 右键搜索框：清空
            if (searchField != null && insideField(searchField, mouseX, mouseY)) {
                searchField.setText("");
                searchText = "";
                return;
            }
        }

        super.mouseClicked(mouseX, mouseY, button);
        if (searchField != null) {
            searchField.mouseClicked(mouseX, mouseY, button);
        }
    }

    /** 鼠标是否在某个文本框上面。 */
    private static boolean insideField(GuiTextField field, int mouseX, int mouseY) {
        return mouseX >= field.xPosition && mouseX < field.xPosition + field.width
                && mouseY >= field.yPosition && mouseY < field.yPosition + field.height;
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        if (selecting != null) {
            return;   // 弹窗开着时不滚列表
        }
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        int step = (wheel > 0 ? 1 : -1) * 2;
        if (isInsideList(mouseX, mouseY)) {
            scroll -= step;
            clampScroll();
        } else if (isInsideSidebar(mouseX, mouseY)) {
            sidebarScroll -= step;
            clampSidebarScroll();
        }
    }

    // ---- 数量选择弹窗 ----

    private void openPopup(BlockCount bc) {
        selecting = bc;
        popupError = null;
        if (amountField != null) {
            amountField.setText(String.valueOf(bc.count));
            amountField.setFocused(true);
            amountField.setCursorPositionEnd();
        }
    }

    private void closePopup() {
        selecting = null;
        popupError = null;
        if (amountField != null) {
            amountField.setFocused(false);
        }
    }

    /**
     * 快捷按钮：前三个是<b>累加</b>（点一下 +1 / +100 / +1000），
     * 最后一个「全部」才是直接设为可提取上限。
     * 累加超过上限就夹到上限，不用弹错误提示 —— 点按钮本来就是为了快。
     */
    private void applyQuickAmount(int index) {
        if (selecting == null || amountField == null) {
            return;
        }
        int value;
        if (index == 3) {
            value = selecting.count;
        } else {
            value = parseAmount();
            if (index == 0) {
                value += 1;
            } else if (index == 1) {
                value += 100;
            } else {
                value += 1000;
            }
        }
        if (value > selecting.count) {
            value = selecting.count;
        }
        if (value < 1) {
            value = 1;
        }
        amountField.setText(String.valueOf(value));
        amountField.setCursorPositionEnd();
        popupError = null;
    }

    /** 读输入框里的数量；空的或不是数字都当 0，方便累加。 */
    private int parseAmount() {
        try {
            int v = Integer.parseInt(amountField.getText().trim());
            return v > 0 ? v : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void confirmPopup() {
        if (selecting == null) {
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(amountField.getText().trim());
        } catch (NumberFormatException e) {
            popupError = StatCollector.translateToLocal("gui.peek_quarry.ender_touch.popup.bad");
            return;
        }
        if (amount <= 0) {
            popupError = StatCollector.translateToLocal("gui.peek_quarry.ender_touch.popup.bad");
            return;
        }
        if (amount > selecting.count) {
            popupError = StatCollector.translateToLocalFormatted(
                    "gui.peek_quarry.ender_touch.popup.toomany", String.valueOf(selecting.count));
            return;
        }
        String name = Block.blockRegistry.getNameForObject(selecting.block);
        if (name == null) {
            closePopup();
            return;
        }
        PacketHandler.CHANNEL.sendToServer(new PacketHandler.MsgExtract(
                te.xCoord, te.yCoord, te.zCoord, name, selecting.meta, amount));
        closePopup();
    }

    private void handlePopupClick(int mouseX, int mouseY, int button) {
        for (int i = 0; i < 4; i++) {
            if (inside(mouseX, mouseY, quickX(i), quickY(), quickW(), quickH())) {
                applyQuickAmount(i);
                return;
            }
        }
        if (inside(mouseX, mouseY, actionX(0), actionY(), actionW(), actionH())) {
            confirmPopup();
            return;
        }
        if (inside(mouseX, mouseY, actionX(1), actionY(), actionW(), actionH())) {
            closePopup();
            return;
        }
        // 右键数量输入框：清空（方便重新输一个数，或先清零再点 +100 累加）
        if (button == 2 && insideField(amountField, mouseX, mouseY)) {
            amountField.setText("");
            amountField.setCursorPositionZero();
            popupError = null;
            return;
        }
        amountField.mouseClicked(mouseX, mouseY, button);
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int quickW() {
        return Math.max(1, (popupW - 16 - 3 * 4) / 4);
    }

    private int quickX(int index) {
        return popupX + 8 + index * (quickW() + 4);
    }

    private int quickY() {
        return popupY + 54;
    }

    private int quickH() {
        return 16;
    }

    private int actionW() {
        return Math.max(1, (popupW - 16 - 4) / 2);
    }

    private int actionX(int index) {
        return popupX + 8 + index * (actionW() + 4);
    }

    private int actionY() {
        return popupY + 76;
    }

    private int actionH() {
        return 18;
    }

    private boolean isInsideSidebar(int mouseX, int mouseY) {
        int x = guiLeft + sidebarX;
        int y = guiTop + SIDEBAR_TOP;
        return mouseX >= x && mouseX < x + sidebarWidth() && mouseY >= y && mouseY < y + sidebarH;
    }

    private int sidebarWidth() {
        return Math.max(0, this.xSize - sidebarX - 6);
    }

    private boolean isInsideList(int mouseX, int mouseY) {
        int x = guiLeft + LIST_X;
        int y = guiTop + LIST_TOP;
        return mouseX >= x && mouseX < x + listWidth() && mouseY >= y && mouseY < y + listHeight();
    }

    private int listWidth() {
        return Math.min(BASE_W, this.xSize) - LIST_X * 2;
    }

    private int listHeight() {
        return this.ySize - LIST_TOP - 6;
    }

    private void clampScroll() {
        int max = Math.max(0, view.size() - visibleRows);
        if (scroll > max) {
            scroll = max;
        }
        if (scroll < 0) {
            scroll = 0;
        }
    }

    // ==================================================================
    // 过滤 + 排序
    // ==================================================================

    private void refreshView() {
        int version = te.getResultVersion();
        if (version == viewVersion && searchText.equals(viewSearch) && sortDescending == viewSortDesc) {
            return;
        }
        viewVersion = version;
        viewSearch = searchText;
        viewSortDesc = sortDescending;

        String query = searchText.trim().toLowerCase();
        List<BlockCount> list = new ArrayList<BlockCount>();
        List<BlockCount> source = te.getResults();
        int total = 0;
        for (int i = 0; i < source.size(); i++) {
            BlockCount bc = source.get(i);
            if (query.isEmpty() || bc.getSearchText().contains(query)) {
                list.add(bc);
                total += bc.count;
            }
        }
        Collections.sort(list, sortDescending ? byCountDesc : byCountAsc);
        view = list;
        viewTotal = total;
        clampScroll();
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        refreshView();

        int left = guiLeft;
        int top = guiTop;
        int right = left + this.xSize;
        int bottom = top + this.ySize;

        drawRect(left, top, right, bottom, PANEL_BG);
        // 一圈边框
        drawRect(left, top, right, top + 1, PANEL_BORDER);
        drawRect(left, bottom - 1, right, bottom, PANEL_BORDER);
        drawRect(left, top, left + 1, bottom, PANEL_BORDER);
        drawRect(right - 1, top, right, bottom, PANEL_BORDER);

        // 进度条
        int baseW = Math.min(BASE_W, this.xSize);
        int barX = left + LIST_X;
        int barW = baseW - 84;
        drawRect(barX, top + 55, barX + barW, top + 61, BAR_BG);
        int total = te.getChunksTotal();
        if (total > 0) {
            int done = Math.min(te.getChunksDone(), total);
            int filled = (int) ((long) barW * done / total);
            if (filled > 0) {
                drawRect(barX, top + 55, barX + filled, top + 61, BAR_FILL);
            }
        }

        // 列表底
        int lx = left + LIST_X;
        int ly = top + LIST_TOP;
        int lw = listWidth();
        int lh = listHeight();
        drawRect(lx, ly, lx + lw, ly + lh, LIST_BG);
        drawRect(lx, ly, lx + lw, ly + 1, LIST_BORDER);
        drawRect(lx, ly + lh - 1, lx + lw, ly + lh, LIST_BORDER);

        // 行悬浮高亮
        int hovered = rowAt(mouseX, mouseY);
        if (hovered >= 0 && hovered < view.size()) {
            int ry = ly + (hovered - scroll) * ROW_H;
            drawRect(lx + 1, ry, lx + lw - 1, ry + ROW_H, ROW_HOVER);
        }

        // 滚动条
        if (view.size() > visibleRows) {
            int trackX = lx + lw - 5;
            drawRect(trackX, ly + 1, trackX + 4, ly + lh - 1, BAR_BG);
            int barH = Math.max(12, (int) ((long) (lh - 2) * visibleRows / view.size()));
            int maxScroll = view.size() - visibleRows;
            int barY = ly + 1 + (maxScroll == 0 ? 0 : (lh - 2 - barH) * scroll / maxScroll);
            drawRect(trackX, barY, trackX + 4, barY + barH, PANEL_BORDER);
        }

        drawSidebarBackground(mouseX, mouseY);
    }

    private void drawSidebarBackground(int mouseX, int mouseY) {
        int sw = sidebarWidth();
        if (sw <= 8) {
            return;   // 窗口太窄，侧边栏没地方放
        }
        int sx = guiLeft + sidebarX;
        int sy = guiTop + SIDEBAR_TOP;

        drawRect(sx, sy, sx + sw, sy + sidebarH, LIST_BG);
        drawRect(sx, sy, sx + sw, sy + 1, LIST_BORDER);
        drawRect(sx, sy + sidebarH - 1, sx + sw, sy + sidebarH, LIST_BORDER);
        drawRect(sx, sy, sx + 1, sy + sidebarH, LIST_BORDER);
        drawRect(sx + sw - 1, sy, sx + sw, sy + sidebarH, LIST_BORDER);

        int hovered = sidebarRowAt(mouseX, mouseY);
        if (hovered >= 0 && hovered < storageView.size()) {
            int ry = sy + (hovered - sidebarScroll) * ROW_H;
            drawRect(sx + 1, ry, sx + sw - 1, ry + ROW_H, ROW_HOVER);
        }

        if (storageView.size() > sidebarRows) {
            int trackX = sx + sw - 5;
            drawRect(trackX, sy + 1, trackX + 4, sy + sidebarH - 1, BAR_BG);
            int barH = Math.max(12, (int) ((long) (sidebarH - 2) * sidebarRows / storageView.size()));
            int maxScroll = storageView.size() - sidebarRows;
            int barY = sy + 1 + (maxScroll == 0 ? 0 : (sidebarH - 2 - barH) * sidebarScroll / maxScroll);
            drawRect(trackX, barY, trackX + 4, barY + barH, PANEL_BORDER);
        }
    }

    /** 鼠标在侧边栏第几行（视口内），不在里面返回 -1。 */
    private int sidebarRowAt(int mouseX, int mouseY) {
        if (!isInsideSidebar(mouseX, mouseY)) {
            return -1;
        }
        int row = (mouseY - (guiTop + SIDEBAR_TOP)) / ROW_H;
        if (row < 0 || row >= sidebarRows) {
            return -1;
        }
        return sidebarScroll + row;
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        // 左栏的右边界：覆盖范围文字贴它右对齐，不要跑到侧边栏上面去
        int baseRight = Math.min(BASE_W, this.xSize);

        fontRendererObj.drawString(StatCollector.translateToLocal("gui.peek_quarry.ender_touch.title"),
                6, 7, TEXT);

        // 右上角：覆盖范围
        int side = TileEntityEnderTouch.sideFor(radius);
        String coverage = StatCollector.translateToLocalFormatted("gui.peek_quarry.ender_touch.coverage",
                String.valueOf(side), String.valueOf(side),
                String.valueOf(TileEntityEnderTouch.chunksFor(radius)));
        fontRendererObj.drawString(coverage,
                baseRight - 6 - fontRendererObj.getStringWidth(coverage), 7, TEXT_DIM);

        // 半径
        fontRendererObj.drawString(StatCollector.translateToLocal("gui.peek_quarry.ender_touch.radius"),
                6, 25, TEXT);
        String radiusText = String.valueOf(radius);
        fontRendererObj.drawString(radiusText, 82 - fontRendererObj.getStringWidth(radiusText) / 2, 25, TEXT_COUNT);

        // 状态
        fontRendererObj.drawString(statusText(), 6, 43, TEXT_DIM);

        drawResultRows(mouseX, mouseY);
        drawSidebarRows(mouseX, mouseY);
    }

    private String statusText() {
        switch (te.getState()) {
            case TileEntityEnderTouch.STATE_SCANNING:
                return StatCollector.translateToLocalFormatted("gui.peek_quarry.ender_touch.status.scanning",
                        String.valueOf(te.getChunksDone()), String.valueOf(te.getChunksTotal()));
            case TileEntityEnderTouch.STATE_EXTRACTING:
                return StatCollector.translateToLocalFormatted("gui.peek_quarry.ender_touch.status.extracting",
                        String.valueOf(te.getChunksDone()), String.valueOf(te.getChunksTotal()));
            case TileEntityEnderTouch.STATE_DONE:
                // 用过滤后的数字：搜索时状态栏不能还报全量总数，否则和列表对不上
                return StatCollector.translateToLocalFormatted("gui.peek_quarry.ender_touch.status.done",
                        String.valueOf(view.size()), String.valueOf(viewTotal));
            case TileEntityEnderTouch.STATE_CANCELLED:
                return StatCollector.translateToLocalFormatted("gui.peek_quarry.ender_touch.status.cancelled",
                        String.valueOf(te.getChunksDone()), String.valueOf(te.getChunksTotal()));
            default:
                return StatCollector.translateToLocal("gui.peek_quarry.ender_touch.status.idle");
        }
    }

    private void drawSidebarRows(int mouseX, int mouseY) {
        int sw = sidebarWidth();
        if (sw <= 8) {
            return;
        }
        int sx = sidebarX;
        int sy = SIDEBAR_TOP;

        // 标题 + 容器总量
        fontRendererObj.drawString(StatCollector.translateToLocal("gui.peek_quarry.ender_touch.sidebar"), sx + 3, 7, TEXT);
        String total = String.valueOf(te.getTotalStored());
        fontRendererObj.drawString(total,
                sx + sw - 4 - fontRendererObj.getStringWidth(total), 7, TEXT_COUNT);

        if (storageView.isEmpty()) {
            String msg = StatCollector.translateToLocal("gui.peek_quarry.ender_touch.sidebar.empty");
            fontRendererObj.drawString(msg,
                    sx + (sw - fontRendererObj.getStringWidth(msg)) / 2, sy + 8, 0xFF707078);
            return;
        }

        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        itemRender.zLevel = 100.0F;

        for (int row = 0; row < sidebarRows; row++) {
            int index = sidebarScroll + row;
            if (index >= storageView.size()) {
                break;
            }
            BlockCount bc = storageView.get(index);
            int rowY = sy + row * ROW_H;

            ItemStack stack = bc.getStack();
            if (stack != null) {
                itemRender.renderItemAndEffectIntoGUI(fontRendererObj, this.mc.getTextureManager(),
                        stack, sx + 2, rowY + 1);
            } else {
                drawBlockIcon(bc, sx + 2, rowY + 1);
            }

            String count = String.valueOf(bc.count);
            int countW = fontRendererObj.getStringWidth(count);
            int nameRoom = sw - 22 - countW - 6;
            fontRendererObj.drawString(trim(bc.getDisplayName(), Math.max(8, nameRoom)),
                    sx + 20, rowY + 5, TEXT);
            fontRendererObj.drawString(count, sx + sw - 4 - countW, rowY + 5, TEXT_COUNT);
        }

        itemRender.zLevel = 0.0F;
        RenderHelper.disableStandardItemLighting();

        int hovered = sidebarRowAt(mouseX, mouseY);
        if (hovered >= 0 && hovered < storageView.size()) {
            List<String> lines = new ArrayList<String>();
            lines.add(storageView.get(hovered).getDisplayName());
            lines.add(StatCollector.translateToLocalFormatted("gui.peek_quarry.ender_touch.total",
                    String.valueOf(storageView.get(hovered).count)));
            drawHoveringText(lines, mouseX - guiLeft, mouseY - guiTop, fontRendererObj);
        }
    }

    private void drawResultRows(int mouseX, int mouseY) {
        int lx = LIST_X;
        int ly = LIST_TOP;
        int lw = listWidth();

        if (view.isEmpty()) {
            String msg = StatCollector.translateToLocal("gui.peek_quarry.ender_touch.empty");
            fontRendererObj.drawString(msg, lx + (lw - fontRendererObj.getStringWidth(msg)) / 2, ly + 20, 0xFF707078);
            return;
        }

        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        itemRender.zLevel = 100.0F;

        for (int row = 0; row < visibleRows; row++) {
            int index = scroll + row;
            if (index >= view.size()) {
                break;
            }
            BlockCount bc = view.get(index);
            int rowY = ly + row * ROW_H;

            ItemStack stack = bc.getStack();
            if (stack != null) {
                itemRender.renderItemAndEffectIntoGUI(fontRendererObj, this.mc.getTextureManager(),
                        stack, lx + 3, rowY + 1);
            } else {
                // 没有 ItemBlock 的方块（甘蔗、农作物、水/岩浆…）直接画方块贴图，
                // 否则这一行会是个空格子
                drawBlockIcon(bc, lx + 3, rowY + 1);
            }

            fontRendererObj.drawString(trim(bc.getDisplayName(), lw - 70), lx + 22, rowY + 5, TEXT);

            String count = String.valueOf(bc.count);
            fontRendererObj.drawString(count, lx + lw - 8 - fontRendererObj.getStringWidth(count), rowY + 5, TEXT_COUNT);
        }

        itemRender.zLevel = 0.0F;
        RenderHelper.disableStandardItemLighting();

        // 悬浮行的完整名称
        int hovered = rowAt(mouseX, mouseY);
        if (hovered >= 0 && hovered < view.size()) {
            List<String> lines = new ArrayList<String>();
            lines.add(view.get(hovered).getDisplayName());
            lines.add(StatCollector.translateToLocalFormatted("gui.peek_quarry.ender_touch.total",
                    String.valueOf(view.get(hovered).count)));
            lines.add(StatCollector.translateToLocal("gui.peek_quarry.ender_touch.hint.middle"));
            drawHoveringText(lines, mouseX - guiLeft, mouseY - guiTop, fontRendererObj);
        }
    }

    /** 直接把方块贴图画进 GUI（没有 ItemBlock 时的回退）。 */
    private void drawBlockIcon(BlockCount bc, int x, int y) {
        IIcon icon;
        try {
            icon = bc.block.getIcon(0, bc.meta);
        } catch (Throwable ignored) {
            return;
        }
        if (icon == null) {
            return;
        }
        this.mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        float previous = this.zLevel;
        this.zLevel = 100.0F;   // 和物品图标同一层，别被列表底盖住
        drawTexturedModelRectFromIcon(x, y, icon, 16, 16);
        this.zLevel = previous;
    }

    /** 名称太长就截断加省略号，别把数量挤出去。 */
    private String trim(String text, int maxWidth) {
        if (fontRendererObj.getStringWidth(text) <= maxWidth) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (fontRendererObj.getStringWidth(sb.toString() + c + "...") > maxWidth) {
                break;
            }
            sb.append(c);
        }
        return sb + "...";
    }

    /** 鼠标在第几行（视口内），不在列表里返回 -1。 */
    private int rowAt(int mouseX, int mouseY) {
        if (!isInsideList(mouseX, mouseY)) {
            return -1;
        }
        int row = (mouseY - (guiTop + LIST_TOP)) / ROW_H;
        if (row < 0 || row >= visibleRows) {
            return -1;
        }
        return scroll + row;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (searchField != null) {
            searchField.drawTextBox();
            // 占位提示必须画在文本框之后，否则会被 drawTextBox 整块盖掉。
            // drawScreen 里没有被 GUI 平移过，所以用绝对坐标。
            if (searchField.getText().isEmpty() && !searchField.isFocused()) {
                fontRendererObj.drawString(
                        StatCollector.translateToLocal("gui.peek_quarry.ender_touch.search_hint"),
                        guiLeft + LIST_X + 4, guiTop + 72, 0xFF66666E);
            }
        }
        if (selecting != null) {
            drawPopup(mouseX, mouseY);
        }
    }

    // ==================================================================
    // 数量选择弹窗
    // ==================================================================

    private void drawPopup(int mouseX, int mouseY) {
        // 先压暗背景，突出弹窗
        drawRect(guiLeft, guiTop, guiLeft + this.xSize, guiTop + this.ySize, 0xC0000000);

        drawRect(popupX, popupY, popupX + popupW, popupY + popupH, PANEL_BG);
        drawRect(popupX, popupY, popupX + popupW, popupY + 1, PANEL_BORDER);
        drawRect(popupX, popupY + popupH - 1, popupX + popupW, popupY + popupH, PANEL_BORDER);
        drawRect(popupX, popupY, popupX + 1, popupY + popupH, PANEL_BORDER);
        drawRect(popupX + popupW - 1, popupY, popupX + popupW, popupY + popupH, PANEL_BORDER);

        // 标题行：图标 + 名称 + 扫描到的总数
        ItemStack stack = selecting.getStack();
        if (stack != null) {
            RenderHelper.enableGUIStandardItemLighting();
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            itemRender.zLevel = 100.0F;
            itemRender.renderItemAndEffectIntoGUI(fontRendererObj, this.mc.getTextureManager(),
                    stack, popupX + 6, popupY + 6);
            itemRender.zLevel = 0.0F;
            RenderHelper.disableStandardItemLighting();
        } else {
            drawBlockIcon(selecting, popupX + 6, popupY + 6);
        }

        String title = trim(selecting.getDisplayName(), popupW - 60);
        fontRendererObj.drawString(title, popupX + 26, popupY + 10, TEXT);
        String avail = StatCollector.translateToLocalFormatted("gui.peek_quarry.ender_touch.popup.avail",
                String.valueOf(selecting.count));
        fontRendererObj.drawString(avail,
                popupX + popupW - 6 - fontRendererObj.getStringWidth(avail), popupY + 10, TEXT_DIM);

        // 数量输入框
        amountField.drawTextBox();

        // 快捷键：+1 / +100 / +1000 是累加，全部是设为上限
        String[] quick = {
                "+1", "+100", "+1000",
                StatCollector.translateToLocal("gui.peek_quarry.ender_touch.popup.all")
        };
        for (int i = 0; i < quick.length; i++) {
            drawSmallButton(quickX(i), quickY(), quickW(), quickH(), quick[i], mouseX, mouseY);
        }

        // 确认 / 取消
        drawSmallButton(actionX(0), actionY(), actionW(), actionH(),
                StatCollector.translateToLocal("gui.peek_quarry.ender_touch.popup.confirm"), mouseX, mouseY);
        drawSmallButton(actionX(1), actionY(), actionW(), actionH(),
                StatCollector.translateToLocal("gui.peek_quarry.ender_touch.popup.cancel"), mouseX, mouseY);

        if (popupError != null) {
            fontRendererObj.drawString(popupError, popupX + 8, popupY + popupH - 14, 0xFFFF5555);
        } else {
            fontRendererObj.drawString(
                    StatCollector.translateToLocal("gui.peek_quarry.ender_touch.popup.warn"),
                    popupX + 8, popupY + popupH - 14, 0xFFFFAA33);
        }
    }

    /** 自绘小按钮：弹窗里的按钮不值得塞进 buttonList 再管理一遍。 */
    private void drawSmallButton(int x, int y, int w, int h, String label, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        drawRect(x, y, x + w, y + h, hover ? 0xFF3A3A48 : 0xFF2A2A34);
        drawRect(x, y, x + w, y + 1, PANEL_BORDER);
        drawRect(x, y + h - 1, x + w, y + h, PANEL_BORDER);
        drawRect(x, y, x + 1, y + h, PANEL_BORDER);
        drawRect(x + w - 1, y, x + w, y + h, PANEL_BORDER);
        fontRendererObj.drawString(label,
                x + (w - fontRendererObj.getStringWidth(label)) / 2,
                y + (h - 8) / 2, hover ? 0xFFFFFF80 : TEXT);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;   // 单机时别暂停，否则服务端扫描/提取会停住
    }
}
