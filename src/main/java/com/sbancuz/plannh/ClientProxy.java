package com.sbancuz.plannh;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.screen.GuiContainerWrapper;
import com.cleanroommc.modularui.screen.ModularContainer;
import com.sbancuz.plannh.client.ChatHandler;
import com.sbancuz.plannh.client.ImportCommand;
import com.sbancuz.plannh.client.WorldHandler;
import com.sbancuz.plannh.gui.FlowchartScreen;
import com.sbancuz.plannh.layout.AutoLayout;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;

public class ClientProxy extends CommonProxy {

    private static final KeyBinding openFlowchartKey = new KeyBinding(
        "key.neiflowchart.open",
        Keyboard.KEY_F8,
        "key.categories.neiflowchart");

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
    }

    @Override
    public void init(final FMLInitializationEvent event) {
        super.init(event);

        // Vanilla has to go first since the furnace handler is likely to be overwritten
        Compat.init();

        ClientRegistry.registerKeyBinding(openFlowchartKey);

        final WorldHandler handler = new WorldHandler();
        MinecraftForge.EVENT_BUS.register(handler);
        FMLCommonHandler.instance()
            .bus()
            .register(handler);

        MinecraftForge.EVENT_BUS.register(new ChatHandler());
        ClientCommandHandler.instance.registerCommand(new ImportCommand());

        FMLCommonHandler.instance()
            .bus()
            .register(this);

        // ELK's first layout pays for its class loading and metadata registration; spend it
        // during init so the first Auto-Layout click doesn't freeze the game. AutoLayout's static
        // block runs on this call, so a mis-shaded ELK fails here as a LinkageError - which must
        // cost the mod its layout button, not its startup.
        try {
            AutoLayout.warmup();
        } catch (final RuntimeException | LinkageError e) {
            PlanNH.LOG.error("ELK warm-up failed; Auto-Layout will be unavailable", e);
        }
    }

    @SubscribeEvent
    public void onKeyInput(final InputEvent.KeyInputEvent event) {
        if (openFlowchartKey.isPressed()) {
            final ModularContainer container = new ModularContainer();
            container.constructClientOnly();
            final FlowchartScreen screen = FlowchartScreen.create();
            Minecraft.getMinecraft()
                .displayGuiScreen(new GuiContainerWrapper(container, screen));
        }
    }
}
