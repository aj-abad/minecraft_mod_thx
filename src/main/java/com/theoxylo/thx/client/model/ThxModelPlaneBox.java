package com.theoxylo.thx.client.model;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.client.renderer.Tessellator;

/**
 * A flat sprite extruded to {@link #THICKNESS} units, the way vanilla renders a
 * held item: both large faces share a single w x h patch of the sheet -- the
 * back one mirrored, so the art reads the same way round from either side --
 * and the four rim faces take the one-texel border of that same patch.
 *
 * Vanilla {@link ModelBox} gives every face its own patch, spending
 * 2*(w+d) x (d+h) texels on a box, so a plane costs two copies of its artwork
 * whether it is a real slab or a degenerate zero-depth box. This costs one.
 *
 * The box is always built in the XY plane and extruded along +Z; a part wanting
 * another orientation rotates its {@link ModelRenderer} instead (as the main
 * rotor's 90 degree pitch does).
 */
class ThxModelPlaneBox extends ModelBox
{
    /**
     * Extrusion depth in model units. Free to be fractional: this class builds its
     * own vertices rather than going through {@link ModelRenderer#addBox}, whose
     * dimensions are integers. However thin it gets, the rim keeps sampling the
     * patch's one-texel border, so it still reads as the sprite's own edge.
     */
    static final float THICKNESS = 0.25f;

    private final float texWidth;
    private final float texHeight;
    private final TexturedQuad[] quads;

    /**
     * @param part      owner, consulted for the sheet size
     * @param texU      left edge of the w x h artwork patch, in texels
     * @param texV      top edge of the patch, in texels
     * @param x         minimum corner of the box, in model units (Y is down)
     * @param w         plane size in model units, and the patch size in texels
     */
    ThxModelPlaneBox(ModelRenderer part, int texU, int texV, float x, float y, float z, int w, int h)
    {
        // The inherited quads are never drawn -- render() below replaces them --
        // but the super call still fills in the posX1..posZ2 bounds. Those take an
        // integer depth, so they round the extrusion up to enclose it; only
        // RendererLivingEntity reads them, and this is not a living entity.
        super(part, texU, texV, x, y, z, w, h, (int) Math.ceil(THICKNESS), 0f);

        texWidth  = part.textureWidth;
        texHeight = part.textureHeight;

        float x2 = x + w;
        float y2 = y + h;
        float z2 = z + THICKNESS;

        float u1 = texU, u2 = texU + w;  // patch edges
        float v1 = texV, v2 = texV + h;
        float uL = u1 + 1, uR = u2 - 1;  // rim strips, one texel inside those edges
        float vT = v1 + 1, vB = v2 - 1;

        quads = new TexturedQuad[]
        {
            // front (-Z): the patch as authored
            quad(vert(x2, y,  z,  u2, v1), vert(x,  y,  z,  u1, v1),
                 vert(x,  y2, z,  u1, v2), vert(x2, y2, z,  u2, v2)),
            // back (+Z): the same patch, mirrored the way vanilla mirrors a rear face
            quad(vert(x,  y,  z2, u2, v1), vert(x2, y,  z2, u1, v1),
                 vert(x2, y2, z2, u1, v2), vert(x,  y2, z2, u2, v2)),
            // rim: each strip runs inwards from the patch edge it meets
            quad(vert(x2, y,  z2, uR, v1), vert(x2, y,  z,  u2, v1),  // +X
                 vert(x2, y2, z,  u2, v2), vert(x2, y2, z2, uR, v2)),
            quad(vert(x,  y,  z,  u1, v1), vert(x,  y,  z2, uL, v1),  // -X
                 vert(x,  y2, z2, uL, v2), vert(x,  y2, z,  u1, v2)),
            quad(vert(x2, y,  z2, u2, vT), vert(x,  y,  z2, u1, vT),  // -Y (top, Y being down)
                 vert(x,  y,  z,  u1, v1), vert(x2, y,  z,  u2, v1)),
            quad(vert(x2, y2, z,  u2, v2), vert(x,  y2, z,  u1, v2),  // +Y (bottom)
                 vert(x,  y2, z2, u1, vB), vert(x2, y2, z2, u2, vB)),
        };
    }

    public void render(Tessellator tessellator, float scale)
    {
        for (int i = 0; i < quads.length; i++)
        {
            quads[i].draw(tessellator, scale);
        }
    }

    /** Vertex at model position (x,y,z) sampling texel (u,v) of the sheet. */
    private PositionTextureVertex vert(float x, float y, float z, float u, float v)
    {
        return new PositionTextureVertex(x, y, z, u / texWidth, v / texHeight);
    }

    /** Corners wound the way {@link ModelBox} winds them, so the outward side is the front face. */
    private static TexturedQuad quad(PositionTextureVertex a, PositionTextureVertex b,
                                     PositionTextureVertex c, PositionTextureVertex d)
    {
        return new TexturedQuad(new PositionTextureVertex[] { a, b, c, d });
    }
}
