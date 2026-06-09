package com.theoxylo.thx.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;

import com.theoxylo.thx.entity.ThxEntityHelicopter;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Client -> server: the pilot's current control input for a helicopter, as a
 * 6-bit key bitmask. Sent only when the bitmask changes (so holding a key is one
 * packet, releasing is another). The server applies it; the entity tracker syncs
 * the resulting motion back to all clients.
 */
public class HelicopterInputMessage implements IMessage
{
    public int entityId;
    public byte keys;

    public HelicopterInputMessage() {}

    public HelicopterInputMessage(int entityId, byte keys)
    {
        this.entityId = entityId;
        this.keys = keys;
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        entityId = buf.readInt();
        keys = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeInt(entityId);
        buf.writeByte(keys);
    }

    public static class Handler implements IMessageHandler<HelicopterInputMessage, IMessage>
    {
        @Override
        public IMessage onMessage(HelicopterInputMessage msg, MessageContext ctx)
        {
            // Runs on a Netty thread. We only store an int field (atomic) on the entity;
            // the main server thread reads it next tick. Single-player / trusted for now.
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;

            Entity e = player.worldObj.getEntityByID(msg.entityId);
            if (e instanceof ThxEntityHelicopter && ((ThxEntityHelicopter) e).riddenByEntity == player)
            {
                ((ThxEntityHelicopter) e).inputKeys = msg.keys & 0xFF;
            }
            return null;
        }
    }
}
