package com.theoxylo.thx.client.viewer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;

import com.theoxylo.thx.client.model.ThxModelHelicopter;

/**
 * Standalone LWJGL viewer for the helicopter model. Reuses the real Minecraft
 * {@code ModelRenderer} render path (via {@link ThxModelHelicopter}), so what
 * you see matches in-game. NOT loaded by the mod — it has a {@code main} and is
 * launched with {@code gradlew runModelViewer}.
 *
 * Controls: LMB drag = orbit, RMB/MMB drag = pan, mouse wheel = zoom,
 *           R = toggle rotor spin, F = toggle wireframe, Esc = quit.
 */
public class ModelViewer
{
    private static final int WIDTH = 1024;
    private static final int HEIGHT = 700;
    // Live-editable source texture (preferred, so F5 reload picks up edits) with a classpath fallback.
    private static final String TEXTURE_FILE = "src/main/resources/assets/thx/textures/entity/helicopter.png";
    private static final String TEXTURE_CP   = "/assets/thx/textures/entity/helicopter.png";

    private float camYaw = 35f;
    private float camPitch = 18f;
    private float distance = 9f;
    private float panX = 0f;
    private float panY = 0f;

    private boolean spin = false;
    private boolean wireframe = false;

    private int texId;
    private int texW;
    private int texH;
    private int reloadCount;
    private ThxModelHelicopter model;

    public static void main(String[] args) throws Exception
    {
        new ModelViewer().run();
    }

    private void run() throws Exception
    {
        Display.setDisplayMode(new DisplayMode(WIDTH, HEIGHT));
        Display.setTitle("THX Model Viewer");
        Display.create();

        initGL();
        reload(); // initial texture + model load (also sets the title bar)

        while (!Display.isCloseRequested() && !Keyboard.isKeyDown(Keyboard.KEY_ESCAPE))
        {
            pollKeys();
            handleMouse();
            model.rotorSpeed = spin ? 1.0f : 0f;
            renderFrame();
            Display.update();
            Display.sync(60);
        }

        Display.destroy();
    }

    private void initGL()
    {
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE); // model's -1 scale flips winding; just draw both sides
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_LIGHT0);
        GL11.glLightModeli(GL11.GL_LIGHT_MODEL_TWO_SIDE, GL11.GL_TRUE);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);
        GL11.glLight(GL11.GL_LIGHT0, GL11.GL_AMBIENT, floats(0.55f, 0.55f, 0.55f, 1f));
        GL11.glLight(GL11.GL_LIGHT0, GL11.GL_DIFFUSE, floats(0.65f, 0.65f, 0.65f, 1f));

        GL11.glClearColor(0.16f, 0.17f, 0.19f, 1f);
    }

    private void renderFrame()
    {
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, wireframe ? GL11.GL_LINE : GL11.GL_FILL);

        // projection
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        float aspect = (float) WIDTH / HEIGHT;
        float near = 0.1f, far = 2000f;
        float top = near * (float) Math.tan(Math.toRadians(60) / 2.0);
        float right = top * aspect;
        GL11.glFrustum(-right, right, -top, top, near, far);

        // camera
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glTranslatef(panX, panY, -distance);
        GL11.glRotatef(camPitch, 1f, 0f, 0f);
        GL11.glRotatef(camYaw, 0f, 1f, 0f);

        GL11.glLight(GL11.GL_LIGHT0, GL11.GL_POSITION, floats(0.4f, 1f, 0.3f, 0f));

        // reference grid + axes (unlit, untextured)
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        drawGrid(10, 1f);
        drawAxes(3f);

        // model
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor3f(1f, 1f, 1f);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        GL11.glPushMatrix();
        model.render(); // applies its own model-space transforms
        GL11.glPopMatrix();
    }

    // ---- input ---------------------------------------------------------------

    private void pollKeys()
    {
        while (Keyboard.next())
        {
            if (!Keyboard.getEventKeyState()) continue; // key-down edges only
            switch (Keyboard.getEventKey())
            {
                case Keyboard.KEY_R: spin = !spin; break;
                case Keyboard.KEY_F: wireframe = !wireframe; break;
                case Keyboard.KEY_F5: reload(); break;
                default: break;
            }
        }
    }

    private void handleMouse()
    {
        int wheel = Mouse.getDWheel();
        if (wheel != 0)
        {
            distance -= wheel * 0.006f;
            if (distance < 1f) distance = 1f;
            if (distance > 400f) distance = 400f;
        }
        int dx = Mouse.getDX();
        int dy = Mouse.getDY();
        if (Mouse.isButtonDown(0)) // orbit
        {
            camYaw += dx * 0.4f;
            camPitch -= dy * 0.4f;
            if (camPitch > 89f) camPitch = 89f;
            if (camPitch < -89f) camPitch = -89f;
        }
        else if (Mouse.isButtonDown(1) || Mouse.isButtonDown(2)) // pan
        {
            panX += dx * distance * 0.0015f;
            panY += dy * distance * 0.0015f;
        }
    }

    // ---- scene helpers -------------------------------------------------------

    private void drawGrid(int half, float step)
    {
        GL11.glColor3f(0.32f, 0.34f, 0.38f);
        GL11.glBegin(GL11.GL_LINES);
        for (int i = -half; i <= half; i++)
        {
            GL11.glVertex3f(i * step, 0f, -half * step);
            GL11.glVertex3f(i * step, 0f,  half * step);
            GL11.glVertex3f(-half * step, 0f, i * step);
            GL11.glVertex3f( half * step, 0f, i * step);
        }
        GL11.glEnd();
    }

    private void drawAxes(float len)
    {
        GL11.glLineWidth(2f);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glColor3f(1f, 0.3f, 0.3f); GL11.glVertex3f(0, 0, 0); GL11.glVertex3f(len, 0, 0); // X red
        GL11.glColor3f(0.3f, 1f, 0.3f); GL11.glVertex3f(0, 0, 0); GL11.glVertex3f(0, len, 0); // Y green (up)
        GL11.glColor3f(0.4f, 0.5f, 1f); GL11.glVertex3f(0, 0, 0); GL11.glVertex3f(0, 0, len); // Z blue
        GL11.glEnd();
        GL11.glLineWidth(1f);
    }

    // ---- reload --------------------------------------------------------------

    /** Re-read the texture from disk and rebuild the model (F5). Model *geometry*
     *  code changes still need a viewer restart (recompile); the texture is live. */
    private void reload()
    {
        if (texId != 0) GL11.glDeleteTextures(texId);
        try
        {
            texId = loadTexture();
        }
        catch (Exception e)
        {
            System.err.println("texture reload failed: " + e);
        }
        model = new ThxModelHelicopter();
        model.rotorSpeed = spin ? 1.0f : 0f;
        reloadCount++;
        updateTitle();
    }

    private void updateTitle()
    {
        Display.setTitle("THX Model Viewer  |  LMB orbit  RMB/MMB pan  wheel zoom  R rotor  F wireframe  F5 reload  Esc quit"
            + "    [tex " + texW + "x" + texH + ", reload #" + reloadCount + "]");
    }

    // ---- texture -------------------------------------------------------------

    private int loadTexture() throws Exception
    {
        // Prefer the live source file so F5 picks up edits; fall back to the classpath copy.
        BufferedImage img = null;
        File file = new File(TEXTURE_FILE);
        if (file.isFile())
        {
            img = ImageIO.read(file);
        }
        if (img == null)
        {
            InputStream in = ModelViewer.class.getResourceAsStream(TEXTURE_CP);
            if (in == null) throw new RuntimeException("texture not found (file " + file.getAbsolutePath()
                + " or classpath " + TEXTURE_CP + ")");
            img = ImageIO.read(in);
            in.close();
        }
        int w = img.getWidth();
        int h = img.getHeight();
        texW = w;
        texH = h;
        int[] px = new int[w * h];
        img.getRGB(0, 0, w, h, px, 0, w);

        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int y = 0; y < h; y++)
        {
            for (int x = 0; x < w; x++)
            {
                int p = px[y * w + x]; // ARGB
                buf.put((byte) ((p >> 16) & 0xFF)); // R
                buf.put((byte) ((p >> 8) & 0xFF));  // G
                buf.put((byte) (p & 0xFF));         // B
                buf.put((byte) ((p >> 24) & 0xFF)); // A
            }
        }
        buf.flip();

        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        return id;
    }

    private static FloatBuffer floats(float... v)
    {
        FloatBuffer b = BufferUtils.createFloatBuffer(v.length);
        b.put(v);
        b.flip();
        return b;
    }
}
