package com.peek.quarry;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import com.google.common.base.Charsets;

import io.netty.buffer.ByteBuf;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * 「末影之触」的网络通道：客户端请求扫描，服务端把统计结果发回来。
 *
 * <p><strong>线程模型很重要：</strong>FML 的 {@code IMessageHandler.onMessage} 是在
 * <em>netty 网络线程</em>上直接调用的（见 {@code SimpleChannelHandlerWrapper.channelRead0}），
 * 不是主线程。所以服务端方向的包一律先塞进 {@link #PENDING} 队列，
 * 再由 {@link #onServerTick} 在主线程上执行 —— 这样就不会在网络线程里碰世界数据。</p>
 *
 * <p>客户端方向则用 {@code Minecraft.func_152344_a()} 排到客户端主线程。</p>
 */
public final class PacketHandler {

    public static final SimpleNetworkWrapper CHANNEL =
            NetworkRegistry.INSTANCE.newSimpleChannel("peek_quarry");

    /** 客户端 → 服务端：开始扫描。 */
    private static final int ID_START = 0;
    /** 客户端 → 服务端：取消扫描。 */
    private static final int ID_CANCEL = 1;
    /** 客户端 → 服务端：请求当前已存的结果（打开 GUI 时用）。 */
    private static final int ID_REQUEST = 2;
    /** 服务端 → 客户端：结果分片。 */
    private static final int ID_RESULTS = 3;
    /** 客户端 → 服务端：请求把某种方块提取 amount 个进容器。 */
    private static final int ID_EXTRACT = 4;
    /** 服务端 → 客户端：容器内容分片。 */
    private static final int ID_STORAGE = 5;
    /** 客户端 → 服务端：只请求容器内容（侧边栏定时刷新用）。 */
    private static final int ID_REQUEST_STORAGE = 6;

    /** 一个结果包最多带多少条，避免超过 1.7.10 的自定义包 32KB 上限。 */
    private static final int RESULTS_PER_PACKET = 128;

    private static final Queue<Runnable> PENDING = new ConcurrentLinkedQueue<Runnable>();

    /**
     * FML 1.7.10 的 {@code EventBus} 只有 {@code register(Object)}，<b>没有 {@code register(Class)}</b>，
     * 而且它只扫实例方法上的 {@code @SubscribeEvent}。所以这里必须注册一个实例，
     * 把 {@link #onServerTick} 写成 static 再传 {@code PacketHandler.class} 是无效的
     * —— 不会报错，但队列永远没人消费。
     */
    private static final PacketHandler TICK_HANDLER = new PacketHandler();

    private PacketHandler() {}

    public static void init() {
        CHANNEL.registerMessage(MsgStartScan.Handler.class, MsgStartScan.class, ID_START, Side.SERVER);
        CHANNEL.registerMessage(MsgCancelScan.Handler.class, MsgCancelScan.class, ID_CANCEL, Side.SERVER);
        CHANNEL.registerMessage(MsgRequestResults.Handler.class, MsgRequestResults.class, ID_REQUEST, Side.SERVER);
        CHANNEL.registerMessage(MsgScanResults.Handler.class, MsgScanResults.class, ID_RESULTS, Side.CLIENT);
        CHANNEL.registerMessage(MsgExtract.Handler.class, MsgExtract.class, ID_EXTRACT, Side.SERVER);
        CHANNEL.registerMessage(MsgStorage.Handler.class, MsgStorage.class, ID_STORAGE, Side.CLIENT);
        CHANNEL.registerMessage(MsgRequestStorage.Handler.class, MsgRequestStorage.class,
                ID_REQUEST_STORAGE, Side.SERVER);
        FMLCommonHandler.instance().bus().register(TICK_HANDLER);
    }

    // ==================================================================
    // ByteBuf 字符串编解码
    // FML 的 IMessage 拿到的是 netty 的 ByteBuf，它没有 readUTF/writeUTF
    // （那是 MC 自己 PacketBuffer 的方法），所以手动加长度前缀。
    // ==================================================================

    private static void writeString(ByteBuf buf, String value) {
        byte[] bytes = value.getBytes(Charsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readString(ByteBuf buf) {
        int length = buf.readInt();
        if (length < 0 || length > 512) {
            throw new IllegalArgumentException("bad string length " + length);
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, Charsets.UTF_8);
    }

    // ==================================================================
    // 服务端主线程调度
    // ==================================================================

    static void enqueue(Runnable task) {
        PENDING.add(task);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Runnable task;
        while ((task = PENDING.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                PeekQuarry.logger.error("[{}] 处理网络请求时出错", PeekQuarry.NAME, t);
            }
        }
    }

    // ==================================================================
    // 服务端 → 客户端：发送结果 / 容器内容
    // ==================================================================

    public static void sendResultsTo(TileEntityEnderTouch te, String playerName) {
        EntityPlayerMP player = resolve(playerName);
        if (player == null) {
            return;
        }
        sendEntryList(te.sortedResults(), player, te.xCoord, te.yCoord, te.zCoord, true);
    }

    public static void sendStorageTo(TileEntityEnderTouch te, String playerName) {
        EntityPlayerMP player = resolve(playerName);
        if (player == null) {
            return;
        }
        List<BlockCount> all = new ArrayList<BlockCount>(te.getStorage());
        sendEntryList(all, player, te.xCoord, te.yCoord, te.zCoord, false);
    }

    private static EntityPlayerMP resolve(String playerName) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || playerName == null) {
            return null;
        }
        EntityPlayerMP player = server.getConfigurationManager().func_152612_a(playerName);
        if (player == null || player.playerNetServerHandler == null) {
            return null;   // 玩家已经掉线了
        }
        return player;
    }

    /** 结果和容器内容共用同一套分片发送。 */
    private static void sendEntryList(List<BlockCount> all, EntityPlayerMP player,
                                      int x, int y, int z, boolean asResults) {
        if (all.isEmpty()) {
            sendSlice(player, x, y, z, asResults, true, true, new ArrayList<BlockCount>());
            return;
        }
        boolean first = true;
        for (int index = 0; index < all.size(); index += RESULTS_PER_PACKET) {
            int end = Math.min(index + RESULTS_PER_PACKET, all.size());
            List<BlockCount> slice = new ArrayList<BlockCount>(all.subList(index, end));
            boolean last = end >= all.size();
            sendSlice(player, x, y, z, asResults, first, last, slice);
            first = false;
        }
    }

    private static void sendSlice(EntityPlayerMP player, int x, int y, int z,
                                  boolean asResults, boolean clear, boolean last, List<BlockCount> entries) {
        if (asResults) {
            CHANNEL.sendTo(new MsgScanResults(x, y, z, clear, last, entries), player);
        } else {
            CHANNEL.sendTo(new MsgStorage(x, y, z, clear, last, entries), player);
        }
    }

    // ==================================================================
    // 条目编解码：结果和容器内容是同一套「种类 + 数量」，共用
    // ==================================================================

    private static void writeEntries(ByteBuf buf, List<BlockCount> entries) {
        buf.writeInt(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            BlockCount bc = entries.get(i);
            String name = Block.blockRegistry.getNameForObject(bc.block);
            writeString(buf, name == null ? "minecraft:air" : name);
            buf.writeByte(bc.meta);
            buf.writeInt(bc.count);
        }
    }

    private static List<BlockCount> readEntries(ByteBuf buf) {
        int n = buf.readInt();
        if (n < 0 || n > RESULTS_PER_PACKET * 4) {
            throw new IllegalArgumentException("bad entry count " + n);
        }
        List<BlockCount> entries = new ArrayList<BlockCount>(n);
        for (int i = 0; i < n; i++) {
            String name = readString(buf);
            int meta = buf.readByte() & 15;
            int count = buf.readInt();
            Object obj = Block.blockRegistry.getObject(name);
            if (obj instanceof Block) {
                entries.add(new BlockCount((Block) obj, meta, count));
            }
        }
        return entries;
    }

    /** 执行扫描前统一做一次目标校验。 */
    private static TileEntityEnderTouch findTile(EntityPlayerMP player, int x, int y, int z) {
        if (player == null || player.worldObj == null) {
            return null;
        }
        World world = player.worldObj;
        if (!world.blockExists(x, y, z)) {
            return null;
        }
        TileEntity te = world.getTileEntity(x, y, z);
        return te instanceof TileEntityEnderTouch ? (TileEntityEnderTouch) te : null;
    }

    // ==================================================================
    // 消息
    // ==================================================================

    /** 客户端 → 服务端：请开始扫描，半径 radius。 */
    public static class MsgStartScan implements IMessage {
        private int x, y, z, radius;

        public MsgStartScan() {}

        public MsgStartScan(int x, int y, int z, int radius) {
            this.x = x; this.y = y; this.z = z; this.radius = radius;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            x = buf.readInt(); y = buf.readInt(); z = buf.readInt(); radius = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(x); buf.writeInt(y); buf.writeInt(z); buf.writeInt(radius);
        }

        public static class Handler implements IMessageHandler<MsgStartScan, IMessage> {
            @Override
            public IMessage onMessage(final MsgStartScan msg, MessageContext ctx) {
                final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                enqueue(new Runnable() {
                    @Override
                    public void run() {
                        TileEntityEnderTouch te = findTile(player, msg.x, msg.y, msg.z);
                        if (te != null) {
                            te.startScan(msg.radius, player.getCommandSenderName());
                        }
                    }
                });
                return null;
            }
        }
    }

    /** 客户端 → 服务端：取消正在进行的扫描。 */
    public static class MsgCancelScan implements IMessage {
        private int x, y, z;

        public MsgCancelScan() {}

        public MsgCancelScan(int x, int y, int z) {
            this.x = x; this.y = y; this.z = z;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            x = buf.readInt(); y = buf.readInt(); z = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(x); buf.writeInt(y); buf.writeInt(z);
        }

        public static class Handler implements IMessageHandler<MsgCancelScan, IMessage> {
            @Override
            public IMessage onMessage(final MsgCancelScan msg, MessageContext ctx) {
                final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                enqueue(new Runnable() {
                    @Override
                    public void run() {
                        TileEntityEnderTouch te = findTile(player, msg.x, msg.y, msg.z);
                        if (te != null) {
                            te.cancelActiveTask();
                        }
                    }
                });
                return null;
            }
        }
    }

    /** 客户端 → 服务端：把已有的结果重发一遍（打开 GUI 时同步）。 */
    public static class MsgRequestResults implements IMessage {
        private int x, y, z;

        public MsgRequestResults() {}

        public MsgRequestResults(int x, int y, int z) {
            this.x = x; this.y = y; this.z = z;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            x = buf.readInt(); y = buf.readInt(); z = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(x); buf.writeInt(y); buf.writeInt(z);
        }

        public static class Handler implements IMessageHandler<MsgRequestResults, IMessage> {
            @Override
            public IMessage onMessage(final MsgRequestResults msg, MessageContext ctx) {
                final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                enqueue(new Runnable() {
                    @Override
                    public void run() {
                        TileEntityEnderTouch te = findTile(player, msg.x, msg.y, msg.z);
                        if (te != null && player.playerNetServerHandler != null) {
                            sendResultsTo(te, player.getCommandSenderName());
                            sendStorageTo(te, player.getCommandSenderName());
                        }
                    }
                });
                return null;
            }
        }
    }

    /**
     * 客户端 → 服务端：只要容器内容。
     *
     * <p>侧边栏开着的时候会定时发这个 —— 方块在主动往隔壁容器送东西，
     * 存储会自己变，客户端不主动拉的话侧边栏就是死的。</p>
     */
    public static class MsgRequestStorage implements IMessage {
        private int x, y, z;

        public MsgRequestStorage() {}

        public MsgRequestStorage(int x, int y, int z) {
            this.x = x; this.y = y; this.z = z;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            x = buf.readInt(); y = buf.readInt(); z = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(x); buf.writeInt(y); buf.writeInt(z);
        }

        public static class Handler implements IMessageHandler<MsgRequestStorage, IMessage> {
            @Override
            public IMessage onMessage(final MsgRequestStorage msg, MessageContext ctx) {
                final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                enqueue(new Runnable() {
                    @Override
                    public void run() {
                        TileEntityEnderTouch te = findTile(player, msg.x, msg.y, msg.z);
                        if (te != null && player.playerNetServerHandler != null) {
                            sendStorageTo(te, player.getCommandSenderName());
                        }
                    }
                });
                return null;
            }
        }
    }

    /** 服务端 → 客户端：结果分片。 */
    public static class MsgScanResults implements IMessage {
        private int x, y, z;
        private boolean clear;
        private boolean last;
        private List<BlockCount> entries = new ArrayList<BlockCount>();

        public MsgScanResults() {}

        public MsgScanResults(int x, int y, int z, boolean clear, boolean last, List<BlockCount> entries) {
            this.x = x; this.y = y; this.z = z;
            this.clear = clear; this.last = last; this.entries = entries;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            x = buf.readInt(); y = buf.readInt(); z = buf.readInt();
            clear = buf.readBoolean();
            last = buf.readBoolean();
            entries = readEntries(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(x); buf.writeInt(y); buf.writeInt(z);
            buf.writeBoolean(clear);
            buf.writeBoolean(last);
            writeEntries(buf, entries);
        }

        public static class Handler implements IMessageHandler<MsgScanResults, IMessage> {
            @Override
            public IMessage onMessage(final MsgScanResults msg, MessageContext ctx) {
                // 网络线程 → 客户端主线程（引用 Minecraft 只在方法体里，dedicated server 不会加载到它）
                net.minecraft.client.Minecraft.getMinecraft().func_152344_a(new Runnable() {
                    @Override
                    public void run() {
                        msg.applyOnClient();
                    }
                });
                return null;
            }
        }

        private void applyOnClient() {
            TileEntityEnderTouch ete = ClientTarget.find(x, y, z);
            if (ete == null) {
                return;
            }
            if (clear) {
                ete.beginNetworkResults();
            }
            for (int i = 0; i < entries.size(); i++) {
                ete.appendNetworkResult(entries.get(i));
            }
            if (last) {
                ete.endNetworkResults();
            }
        }
    }

    /** 客户端 → 服务端：把某种方块从世界里挖 amount 个进容器。 */
    public static class MsgExtract implements IMessage {
        private int x, y, z;
        private String blockName;
        private int meta;
        private int amount;

        public MsgExtract() {}

        public MsgExtract(int x, int y, int z, String blockName, int meta, int amount) {
            this.x = x; this.y = y; this.z = z;
            this.blockName = blockName; this.meta = meta; this.amount = amount;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            x = buf.readInt(); y = buf.readInt(); z = buf.readInt();
            blockName = readString(buf);
            meta = buf.readByte() & 15;
            amount = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(x); buf.writeInt(y); buf.writeInt(z);
            writeString(buf, blockName == null ? "minecraft:air" : blockName);
            buf.writeByte(meta);
            buf.writeInt(amount);
        }

        public static class Handler implements IMessageHandler<MsgExtract, IMessage> {
            @Override
            public IMessage onMessage(final MsgExtract msg, MessageContext ctx) {
                final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                enqueue(new Runnable() {
                    @Override
                    public void run() {
                        TileEntityEnderTouch te = findTile(player, msg.x, msg.y, msg.z);
                        if (te == null) {
                            return;
                        }
                        Object obj = Block.blockRegistry.getObject(msg.blockName);
                        if (!(obj instanceof Block)) {
                            return;
                        }
                        te.requestExtract((Block) obj, msg.meta, msg.amount, player.getCommandSenderName());
                    }
                });
                return null;
            }
        }
    }

    /** 服务端 → 客户端：容器内容分片。 */
    public static class MsgStorage implements IMessage {
        private int x, y, z;
        private boolean clear;
        private boolean last;
        private List<BlockCount> entries = new ArrayList<BlockCount>();

        public MsgStorage() {}

        public MsgStorage(int x, int y, int z, boolean clear, boolean last, List<BlockCount> entries) {
            this.x = x; this.y = y; this.z = z;
            this.clear = clear; this.last = last; this.entries = entries;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            x = buf.readInt(); y = buf.readInt(); z = buf.readInt();
            clear = buf.readBoolean();
            last = buf.readBoolean();
            entries = readEntries(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(x); buf.writeInt(y); buf.writeInt(z);
            buf.writeBoolean(clear);
            buf.writeBoolean(last);
            writeEntries(buf, entries);
        }

        public static class Handler implements IMessageHandler<MsgStorage, IMessage> {
            @Override
            public IMessage onMessage(final MsgStorage msg, MessageContext ctx) {
                net.minecraft.client.Minecraft.getMinecraft().func_152344_a(new Runnable() {
                    @Override
                    public void run() {
                        msg.applyOnClient();
                    }
                });
                return null;
            }
        }

        private void applyOnClient() {
            TileEntityEnderTouch ete = ClientTarget.find(x, y, z);
            if (ete == null) {
                return;
            }
            if (clear) {
                ete.beginNetworkStorage();
            }
            for (int i = 0; i < entries.size(); i++) {
                ete.appendNetworkStorage(entries.get(i));
            }
            if (last) {
                ete.endNetworkStorage();
            }
        }
    }

    /** 客户端侧查 TileEntity 的公共入口（把 Minecraft 的引用收在一处）。 */
    static final class ClientTarget {
        private ClientTarget() {}

        static TileEntityEnderTouch find(int x, int y, int z) {
            World world = net.minecraft.client.Minecraft.getMinecraft().theWorld;
            if (world == null) {
                return null;
            }
            TileEntity te = world.getTileEntity(x, y, z);
            return te instanceof TileEntityEnderTouch ? (TileEntityEnderTouch) te : null;
        }
    }
}
