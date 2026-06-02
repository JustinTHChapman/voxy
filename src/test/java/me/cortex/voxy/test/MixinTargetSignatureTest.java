package me.cortex.voxy.test;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the Minecraft classes/methods that voxy mixins target
 * still exist with the expected signatures. If any of these tests fail
 * after an MC/NeoForge upgrade, the corresponding mixin almost certainly
 * needs an update.
 */
class MixinTargetSignatureTest {

    @Test
    void clientLevelConstructorMatchesMixinClientLevelInject() throws Exception {
        // MixinClientLevel.voxy$getBottom @Inject method = "<init>" expects these params:
        //   ClientPacketListener, ClientLevelData, ResourceKey<Level>, Holder<DimensionType>,
        //   int, int, Supplier<ProfilerFiller>, LevelRenderer, boolean, long
        Constructor<?> match = Arrays.stream(ClientLevel.class.getDeclaredConstructors())
                .filter(c -> {
                    Class<?>[] p = c.getParameterTypes();
                    return p.length == 10
                            && p[0] == ClientPacketListener.class
                            && p[2] == ResourceKey.class
                            && p[3] == Holder.class
                            && p[4] == int.class
                            && p[5] == int.class
                            && p[6] == Supplier.class
                            && p[8] == boolean.class
                            && p[9] == long.class;
                })
                .findFirst()
                .orElse(null);
        assertNotNull(match,
                "ClientLevel constructor with 10 params (incl. Supplier<ProfilerFiller>) not found — "
                        + "MixinClientLevel#voxy$getBottom signature must be updated. Actual ctors: "
                        + Arrays.toString(ClientLevel.class.getDeclaredConstructors()));
    }

    @Test
    void blockableEventLoopHasDoRunTask() throws Exception {
        // MixinBlockableEventLoop @Redirect targets Ljava/lang/Runnable;run()V inside doRunTask
        Method doRunTask = Arrays.stream(BlockableEventLoop.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("doRunTask"))
                .findFirst()
                .orElse(null);
        assertNotNull(doRunTask, "BlockableEventLoop#doRunTask not found — MixinBlockableEventLoop redirect target invalid");
        assertEquals(1, doRunTask.getParameterCount());
        assertEquals(Runnable.class, doRunTask.getParameterTypes()[0],
                "BlockableEventLoop#doRunTask should take Runnable — MixinBlockableEventLoop must be updated");
    }

    @Test
    void clientPacketListenerHasHandleLogin() throws Exception {
        // MixinClientPacketListener @Inject method = "handleLogin"
        Method handleLogin = Arrays.stream(ClientPacketListener.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("handleLogin")
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == ClientboundLoginPacket.class)
                .findFirst()
                .orElse(null);
        assertNotNull(handleLogin, "ClientPacketListener#handleLogin(ClientboundLoginPacket) not found");
    }

    @Test
    void clientboundLoginPacketHasCommonPlayerSpawnInfo() throws Exception {
        // MixinClientPacketListener inject target: ClientboundLoginPacket.commonPlayerSpawnInfo()
        Method m = Arrays.stream(ClientboundLoginPacket.class.getDeclaredMethods())
                .filter(x -> x.getName().equals("commonPlayerSpawnInfo") && x.getParameterCount() == 0)
                .findFirst()
                .orElse(null);
        assertNotNull(m, "ClientboundLoginPacket#commonPlayerSpawnInfo() not found — MixinClientPacketListener inject target invalid");
    }

    @Test
    void minecraftHasConstructorForMixinGPUSelect() throws Exception {
        // MixinGPUSelect @Inject(method="<init>", at=@At("RETURN")) — just confirm a ctor exists
        assertTrue(Minecraft.class.getDeclaredConstructors().length > 0,
                "Minecraft has no declared constructors");
    }

    @Test
    void clientCommonPacketListenerImplHasOnPacketError() throws Exception {
        // MixinClientCommonPacketListenerImpl @Inject method = "onPacketError" — force-crashes if
        // a ClientboundLoginPacket fails to handle (i.e. anything during world entry throws).
        // If this mixin silently fails to inject, voxy fixes don't take effect for login errors,
        // but the more pressing concern is whether this method even exists in MC 1.21.1.
        Class<?> cls = Class.forName("net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl");
        Method m = Arrays.stream(cls.getDeclaredMethods())
                .filter(x -> x.getName().equals("onPacketError")
                        && x.getParameterCount() == 2
                        && x.getParameterTypes()[0] == net.minecraft.network.protocol.Packet.class
                        && x.getParameterTypes()[1] == Exception.class)
                .findFirst()
                .orElse(null);
        assertNotNull(m,
                "ClientCommonPacketListenerImpl#onPacketError(Packet, Exception) not found — "
                        + "MixinClientCommonPacketListenerImpl silently no-ops. Declared methods: "
                        + Arrays.toString(cls.getDeclaredMethods()));
    }

    @Test
    void minecraftDisconnectScreenBooleanOverloadExists() throws Exception {
        // MixinMinecraft @Inject targets disconnect(Screen, boolean) — must exist
        Method m = Arrays.stream(Minecraft.class.getDeclaredMethods())
                .filter(x -> x.getName().equals("disconnect")
                        && x.getParameterCount() == 2
                        && x.getParameterTypes()[0] == net.minecraft.client.gui.screens.Screen.class
                        && x.getParameterTypes()[1] == boolean.class)
                .findFirst()
                .orElse(null);
        assertNotNull(m,
                "Minecraft#disconnect(Screen, boolean) not found — MixinMinecraft inject invalid");
    }

    @Test
    void levelConstructorMatchesMixinWorldInject() throws Exception {
        // MixinWorld @Inject method = "<init>" expects 9 params:
        //   WritableLevelData, ResourceKey<Level>, RegistryAccess, Holder<DimensionType>,
        //   Supplier<ProfilerFiller>, boolean, boolean, long, int
        Constructor<?> match = Arrays.stream(Level.class.getDeclaredConstructors())
                .filter(c -> {
                    Class<?>[] p = c.getParameterTypes();
                    return p.length == 9
                            && p[0] == net.minecraft.world.level.storage.WritableLevelData.class
                            && p[1] == ResourceKey.class
                            && p[2] == net.minecraft.core.RegistryAccess.class
                            && p[3] == Holder.class
                            && p[4] == Supplier.class
                            && p[5] == boolean.class
                            && p[6] == boolean.class
                            && p[7] == long.class
                            && p[8] == int.class;
                })
                .findFirst()
                .orElse(null);
        assertNotNull(match,
                "Level constructor with 9 params not found — MixinWorld voxy$injectIdentifier signature must be updated. Actual: "
                        + Arrays.toString(Level.class.getDeclaredConstructors()));
    }

    @Test
    void levelRendererHasSetLevelAndAllChanged() throws Exception {
        Class<?> lr = net.minecraft.client.renderer.LevelRenderer.class;
        assertNotNull(Arrays.stream(lr.getDeclaredMethods())
                .filter(m -> m.getName().equals("setLevel") && m.getParameterCount() == 1).findFirst().orElse(null),
                "LevelRenderer#setLevel(ClientLevel) not found — MixinLevelRenderer broken");
        assertNotNull(Arrays.stream(lr.getDeclaredMethods())
                .filter(m -> m.getName().equals("allChanged") && m.getParameterCount() == 0).findFirst().orElse(null),
                "LevelRenderer#allChanged() not found — MixinLevelRenderer broken");
        assertNotNull(Arrays.stream(lr.getDeclaredMethods())
                .filter(m -> m.getName().equals("close") && m.getParameterCount() == 0).findFirst().orElse(null),
                "LevelRenderer#close() not found — MixinLevelRenderer broken");
    }

    @Test
    void clientChunkCacheHasDropAndStorageField() throws Exception {
        Class<?> cls = net.minecraft.client.multiplayer.ClientChunkCache.class;
        assertNotNull(cls.getDeclaredField("storage"),
                "ClientChunkCache#storage field not found — MixinClientChunkCache reflection broken");
        assertNotNull(Arrays.stream(cls.getDeclaredMethods())
                .filter(m -> m.getName().equals("drop") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == net.minecraft.world.level.ChunkPos.class)
                .findFirst().orElse(null),
                "ClientChunkCache#drop(ChunkPos) not found — MixinClientChunkCache inject broken");
    }

    @Test
    void levelHasGetMinBuildHeight() throws Exception {
        // MixinClientLevel calls ((Level)(Object)this).getMinBuildHeight()
        Method m = Arrays.stream(Level.class.getMethods())
                .filter(x -> x.getName().equals("getMinBuildHeight") && x.getParameterCount() == 0)
                .findFirst()
                .orElse(null);
        assertNotNull(m, "Level#getMinBuildHeight() not found — MixinClientLevel must be updated");
        assertEquals(int.class, m.getReturnType());
    }
}
