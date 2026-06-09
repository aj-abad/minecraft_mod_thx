package com.theoxylo.thx.client.model;

import net.minecraft.client.model.ModelRenderer;

/**
 * The helicopter geometry: vanilla {@link ModelRenderer} boxes with texture-UV
 * offsets into helicopter.png (a 64x32 logical UV layout, supplied at 8x as a
 * 512x256 texture). Ported verbatim from the 1.6.1 ThxModelHelicopter; only the
 * ThxConfig texture lookup became a constant.
 */
public class ThxModelHelicopter extends ThxModelHelicopterBase
{
    float x2scale = 0.125f; // all boxes are authored half-size and scaled x2 at render to save texture space
    float centerScale = 1.0f;

    public ModelRenderer mainRotor;
    public ModelRenderer rotor2;
    public ModelRenderer rotor3;

    public ModelRenderer windshield;
    public ModelRenderer bottom;
    public ModelRenderer frontWall;
    public ModelRenderer backWall;
    public ModelRenderer leftWall;
    public ModelRenderer rightWall;

    public ModelRenderer tail;
    public ModelRenderer tailRotor;

    public ThxModelHelicopter()
    {
        renderTexture = "thx:textures/entity/helicopter.png"; // was ThxConfig.getProperty("texture_helicopter")

        bottom:
        {
            float length = 10f;
            float height = 8f;
            float width  = 2f;
            bottom = new ModelRenderer(this, 0, 22);
            bottom.addBox(-length / 2f, -height / 2f, -width / 2f, (int) length, (int) height, (int) width);
            bottom.setRotationPoint(0f * centerScale, 2f * centerScale, 0f * centerScale);
            bottom.rotateAngleX = PI / 2f; // 90 deg roll left to lay flat
        }
        frontWall:
        {
            float length = 10f;
            float height =  3f;
            float width  =  1f;
            frontWall = new ModelRenderer(this, 0, 4);
            frontWall.addBox(-length / 2f, -height / 2f, -width / 2f, (int) length, (int) height, (int) width);
            frontWall.setRotationPoint(-5.5f * centerScale, 0f * centerScale, 0f * centerScale);
            frontWall.rotateAngleY = PI * 1.5f; // 270 deg yaw
        }
        backWall:
        {
            float length = 10f;
            float height =  3f;
            float width  =  1f;
            backWall = new ModelRenderer(this, 0, 9);
            backWall.addBox(-length / 2f, -height / 2f, -width / 2f, (int) length, (int) height, (int) width);
            backWall.setRotationPoint(5.5f * centerScale, 0f * centerScale, 0f * centerScale);
            backWall.rotateAngleY = PI / 2f; // 90 deg yaw
        }
        leftWall:
        {
            float length = 10f;
            float height =  3f;
            float width  =  1f;
            leftWall = new ModelRenderer(this, 25, 19);
            leftWall.addBox(-length / 2f, -height / 2f, -width / 2f, (int) length, (int) height, (int) width);
            leftWall.setRotationPoint(0f * centerScale, 0f * centerScale, -4.5f * centerScale);
        }
        rightWall:
        {
            float length = 10f;
            float height =  3f;
            float width  =  1f;
            rightWall = new ModelRenderer(this, 25, 24);
            rightWall.addBox(-length / 2f, -height / 2f, -width / 2f, (int) length, (int) height, (int) width);
            rightWall.setRotationPoint(0f * centerScale, 0f * centerScale, 4.5f * centerScale);
            rightWall.rotateAngleY = PI; // flip 180 deg so decal is on outside
        }
        mainRotor:
        {
            float length = 30f;
            float height =  0f;
            float width  =  1f;
            mainRotor = new ModelRenderer(this, 0, 0);
            mainRotor.addBox(-length / 2f, -height / 2f, -width / 2f, (int) length, (int) height, (int) width);
            mainRotor.setRotationPoint(2f * centerScale, -11.7f * centerScale, 0f * centerScale);
        }
        tailRotor:
        {
            float length = 8f;
            float height = 1f;
            float width  = 0f;
            tailRotor = new ModelRenderer(this, 0, 2);
            tailRotor.addBox(-length / 2f, -height / 2f, -width / 2f, (int) length, (int) height, (int) width);
            tailRotor.setRotationPoint(16f * centerScale, -7f * centerScale, .7f * centerScale);
        }
        tail:
        {
            float length = 10f;
            float height =  2f;
            float width  =  1f;
            tail = new ModelRenderer(this, 42, 29);
            tail.addBox(-length / 2f, -height / 2f, -width / 2f, (int) length, (int) height, (int) width);
            tail.setRotationPoint(12f * centerScale, -7f * centerScale, 0f * centerScale);
        }
        rotorVerticalSupport:
        {
            float length =  1f;
            float height = 11f;
            float width  =  2f;
            rotor2 = new ModelRenderer(this, 58, 11);
            rotor2.addBox(-length / 2f, -height / 2f, -width / 2f, (int) length, (int) height, (int) width);
            rotor2.setRotationPoint(6.5f * centerScale, -5f * centerScale, 0f * centerScale);
        }
        rotorHorizontalSupport:
        {
            float length =  6f;
            float height =  1f;
            float width  =  2f;
            rotor3 = new ModelRenderer(this, 48, 25);
            rotor3.addBox(-length / 2f, -height / 2f, -width / 2f, (int) length, (int) height, (int) width);
            rotor3.setRotationPoint(4f * centerScale, -11f * centerScale, 0f * centerScale);
        }
        windshield:
        {
            float length = 9f;
            float height = 7f;
            float width  = 0f;
            windshield = new ModelRenderer(this, 22, 2);
            windshield.addBox(-length / 2f, -height / 2f, -width / 2f, (int) length, (int) height, (int) width);
            windshield.setRotationPoint(-5.5f * centerScale, -4.5f * centerScale, 0f * centerScale);
            windshield.rotateAngleY = PI * 1.5f; // 270 deg yaw
        }
    }

    public void render()
    {
        super.render();

        if (!visible) return;

        bottom.render(x2scale);

        frontWall.render(x2scale);
        backWall.render(x2scale);
        leftWall.render(x2scale);
        rightWall.render(x2scale);

        windshield.render(x2scale);

        rotor2.render(x2scale);
        rotor3.render(x2scale);

        tail.render(x2scale);

        mainRotor.rotateAngleY = mainRotorAngle;
        mainRotor.render(x2scale);
        mainRotor.rotateAngleY += 1.5707f;
        mainRotor.render(x2scale);

        tailRotor.rotateAngleZ = tailRotorAngle;
        tailRotor.render(x2scale);
        tailRotor.rotateAngleZ += 1.5707f;
        tailRotor.render(x2scale);
    }
}
