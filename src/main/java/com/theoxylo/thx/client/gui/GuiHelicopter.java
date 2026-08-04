package com.theoxylo.thx.client.gui;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.util.ResourceLocation;

import com.theoxylo.thx.Reference;
import com.theoxylo.thx.entity.ThxEntityHelicopter;
import com.theoxylo.thx.inventory.ContainerHelicopter;

/**
 * The helicopter loadout screen: fuel slot (with the furnace-style flame gauge
 * beneath it), a 3x3 cargo grid, and the launcher slot, over the player
 * inventory. The sections are captioned on a shared row between the title and
 * the slots.
 *
 * The flame shows the burn remaining on the item currently in the firebox
 * (synced by the container's progress bars); the unlit silhouette is baked
 * into the background art at (27,56). The menu only opens on an unridden
 * craft, so the values sit still while you look at them.
 *
 * Cargo and ammo are optional hardware. A craft built without them still shows
 * both sections — the slots are inert and the caption greys out — so the menu
 * doubles as the advert for what a chest or a dispenser would buy.
 */
public class GuiHelicopter extends GuiContainer
{
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(Reference.MODID, "textures/gui/helicopter.png");
    private static final ResourceLocation FURNACE_TEXTURE =
            new ResourceLocation("textures/gui/container/furnace.png");

    private static final int LABEL_COLOR = 0x404040;
    private static final int LABEL_COLOR_ABSENT = 0x909090;

    private final boolean hasCargo;
    private final boolean hasAmmo;

    public GuiHelicopter(InventoryPlayer playerInv, IInventory inv, ThxEntityHelicopter heli)
    {
        super(new ContainerHelicopter(playerInv, inv, heli));
        this.xSize = 176;
        this.ySize = 176;
        this.hasCargo = heli.inventory.hasCargo();
        this.hasAmmo = heli.inventory.hasAmmo();
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY)
    {
        GL11.glColor4f(1f, 1f, 1f, 1f);
        this.mc.getTextureManager().bindTexture(TEXTURE);
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);

        // the furnace's lit flame over the baked silhouette, shrinking upward as the burn runs down
        ContainerHelicopter container = (ContainerHelicopter) inventorySlots;
        if (container.burnMaxDisplay > 0 && container.burnRemainingDisplay > 0)
        {
            int k = container.burnRemainingDisplay * 13 / container.burnMaxDisplay;
            if (k > 13) k = 13; // a paused burn can slightly exceed a clamped max (see toShortRange)
            this.mc.getTextureManager().bindTexture(FURNACE_TEXTURE);
            drawTexturedModalRect(guiLeft + 27, guiTop + 56 + 12 - k, 176, 12 - k, 14, k + 1);
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY)
    {
        // Coordinates here are relative to guiLeft/guiTop.
        fontRendererObj.drawString(I18n.format("container.thx.helicopter"), 8, 6, LABEL_COLOR);

        // Section captions on a shared row above the slots, each centered on
        // its section, with a margin below the title. A section this craft
        // wasn't built with is captioned in grey over its inert slots.
        drawCenteredLabel("container.thx.helicopter.fuel", 34, 24, true);
        drawCenteredLabel("container.thx.helicopter.cargo", 88, 24, hasCargo);
        drawCenteredLabel("container.thx.helicopter.launcher", 142, 24, hasAmmo);
    }

    private void drawCenteredLabel(String key, int centerX, int y, boolean installed)
    {
        String text = I18n.format(key);
        fontRendererObj.drawString(text, centerX - fontRendererObj.getStringWidth(text) / 2, y,
                installed ? LABEL_COLOR : LABEL_COLOR_ABSENT);
    }
}
