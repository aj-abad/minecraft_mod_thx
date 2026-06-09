package com.theoxylo.thx.util;

/** Minimal float 3-vector used by the helicopter flight model. Ported verbatim from the 1.6.1 mod. */
public class Vector3
{
    public float x;
    public float y;
    public float z;

    public Vector3() {}

    public Vector3(float x, float y, float z) { set(x, y, z); }

    public void set(float x, float y, float z)
    {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public float lengthSquared() { return x * x + y * y + z * z; }

    public float length() { return (float) Math.sqrt(x * x + y * y + z * z); }

    public Vector3 normalize()
    {
        float length = length();
        if (length == 0) return this;
        x /= length;
        y /= length;
        z /= length;
        return this;
    }

    public Vector3 scale(float f)
    {
        x *= f;
        y *= f;
        z *= f;
        return this;
    }

    public static Vector3 add(Vector3 left, Vector3 right, Vector3 dest)
    {
        if (dest == null) dest = new Vector3();
        dest.set(left.x + right.x, left.y + right.y, left.z + right.z);
        return dest;
    }

    @Override
    public String toString() { return "[x " + x + ", y " + y + ", z " + z + "]"; }
}
