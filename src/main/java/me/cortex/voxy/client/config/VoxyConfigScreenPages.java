package me.cortex.voxy.client.config;

import com.google.common.collect.ImmutableList;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.IVoxyWorld;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI;
import net.caffeinemc.mods.sodium.client.gui.options.OptionGroup;
import net.caffeinemc.mods.sodium.client.gui.options.OptionImpact;
import net.caffeinemc.mods.sodium.client.gui.options.OptionImpl;
import net.caffeinemc.mods.sodium.client.gui.options.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.options.control.SliderControl;
import net.caffeinemc.mods.sodium.client.gui.options.control.TickBoxControl;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class VoxyConfigScreenPages implements ModMenuApi {
    public static OptionPage voxyOptionPage = null;

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            var screen = (SodiumOptionsGUI)SodiumOptionsGUI.createScreen(parent);
            //Sorry jelly and douira, please dont hurt me
            try {
                //We cant use .setPage() as that invokes rebuildGui, however the screen hasnt been initalized yet
                // causing things to crash
                var field = SodiumOptionsGUI.class.getDeclaredField("currentPage");
                field.setAccessible(true);
                field.set(screen, voxyOptionPage);
                field.setAccessible(false);
            } catch (Exception e) {
                Logger.error("Failed to set the current page to voxy", e);
            }
            return screen;
        };
    }

    public static OptionPage page() {
        List<OptionGroup> groups = new ArrayList<>();
        VoxyConfig storage = VoxyConfig.CONFIG;

        //General
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Text.translatable("voxy.config.general.enabled"))
                        .setTooltip(Text.translatable("voxy.config.general.enabled.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((s, v)->{
                            s.enabled = v;
                            if (v) {
                                if (VoxyClientInstance.isInGame) {
                                    VoxyCommon.createInstance();
                                    var vrsh = (IGetVoxyRenderSystem) MinecraftClient.getInstance().worldRenderer;
                                    if (vrsh != null && s.enableRendering) {
                                        vrsh.createRenderer();
                                    }
                                }
                            } else {
                                var vrsh = (IGetVoxyRenderSystem) MinecraftClient.getInstance().worldRenderer;
                                if (vrsh != null) {
                                    vrsh.shutdownRenderer();
                                }
                                var world = (IVoxyWorld) MinecraftClient.getInstance().world;
                                if (world != null) {
                                    world.shutdownEngine();
                                }
                                VoxyCommon.shutdownInstance();
                            }
                        }, s -> s.enabled)
                        .build()
                ).add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Text.translatable("voxy.config.general.serviceThreads"))
                        .setTooltip(Text.translatable("voxy.config.general.serviceThreads.tooltip"))
                        .setControl(opt->new SliderControl(opt, 1, Runtime.getRuntime().availableProcessors(), 1, v->Text.literal(Integer.toString(v))))
                        .setBinding((s, v)->{
                            boolean wasEnabled = VoxyCommon.getInstance() != null;
                            var vrsh = (IGetVoxyRenderSystem) MinecraftClient.getInstance().worldRenderer;
                            if (wasEnabled) {
                                if (vrsh != null) {
                                    vrsh.shutdownRenderer();
                                }
                                var world = (IVoxyWorld) MinecraftClient.getInstance().world;
                                if (world != null) {
                                    world.shutdownEngine();
                                }

                                VoxyCommon.shutdownInstance();
                            }

                            s.serviceThreads = v;

                            if (wasEnabled) {
                                VoxyCommon.createInstance();

                                if (vrsh != null && s.enableRendering) {
                                    vrsh.createRenderer();
                                }
                            }
                        }, s -> s.serviceThreads)
                        .setImpact(OptionImpact.HIGH)
                        .build()
                ).add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Text.translatable("voxy.config.general.ingest"))
                        .setTooltip(Text.translatable("voxy.config.general.ingest.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((s, v) -> s.ingestEnabled = v, s -> s.ingestEnabled)
                        .setImpact(OptionImpact.MEDIUM)
                        .build()
                ).build()
        );

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Text.translatable("voxy.config.general.rendering"))
                        .setTooltip(Text.translatable("voxy.config.general.rendering.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((s, v)->{
                            s.enableRendering = v;
                            var vrsh = (IGetVoxyRenderSystem)MinecraftClient.getInstance().worldRenderer;
                            if (vrsh != null) {
                                if (v) {
                                    vrsh.createRenderer();
                                } else {
                                    vrsh.shutdownRenderer();
                                }
                            }
                        }, s -> s.enableRendering)
                        .build()
                ).add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Text.translatable("voxy.config.general.subDivisionSize"))
                        .setTooltip(Text.translatable("voxy.config.general.subDivisionSize.tooltip"))
                        .setControl(opt->new SliderControl(opt, 28, 256, 1, v->Text.literal(Integer.toString(v))))
                        .setBinding((s, v)->s.subDivisionSize = v, s -> (int) s.subDivisionSize)
                        .setImpact(OptionImpact.HIGH)
                        .build()
                ).add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Text.translatable("voxy.config.general.renderDistance"))
                        .setTooltip(Text.translatable("voxy.config.general.renderDistance.tooltip"))
                        .setControl(opt->new SliderControl(opt, 2, 64, 1, v->Text.literal(Integer.toString(v * 32))))//Every unit is equal to 32 vanilla chunks
                        .setBinding((s, v)-> {
                            s.sectionRenderDistance = v;
                            var vrsh = (IGetVoxyRenderSystem)MinecraftClient.getInstance().worldRenderer;
                            if (vrsh != null) {
                                var vrs = vrsh.getVoxyRenderSystem();
                                if (vrs != null) {
                                    vrs.setRenderDistance(v);
                                }
                            }
                        }, s -> s.sectionRenderDistance)
                        .setImpact(OptionImpact.LOW)
                        .build()
                ).build()
        );
        return new OptionPage(Text.translatable("voxy.config.title"), ImmutableList.copyOf(groups));
    }
}
