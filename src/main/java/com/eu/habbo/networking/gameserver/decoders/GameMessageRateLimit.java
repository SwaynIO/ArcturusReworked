package com.eu.habbo.networking.gameserver.decoders;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.gameclients.GameClient;
import com.eu.habbo.messages.ClientMessage;
import com.eu.habbo.networking.gameserver.GameServerAttributes;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

public class GameMessageRateLimit extends MessageToMessageDecoder<ClientMessage> {

    // Enhanced rate limiting configuration for better DoS protection
    private static final int RESET_TIME = 1; // seconds
    private static final int MAX_COUNTER = 25; // Increased limit for normal usage
    private static final int BURST_LIMIT = 50; // Maximum burst packets per second
    private static final int WARNING_THRESHOLD = 15; // Log warning at this level

    @Override
    protected void decode(ChannelHandlerContext ctx, ClientMessage message, List<Object> out) throws Exception {
        GameClient client = ctx.channel().attr(GameServerAttributes.CLIENT).get();

        if (client == null) {
            return;
        }

        int count = 0;

        // Check if reset time has passed.
        int timestamp = Emulator.getIntUnixTimestamp();
        if (timestamp - client.lastPacketCounterCleared > RESET_TIME) {
            // Reset counter.
            client.incomingPacketCounter.clear();
            client.lastPacketCounterCleared = timestamp;
        } else {
            // Get stored count for message id.
            count = client.incomingPacketCounter.getOrDefault(message.getMessageId(), 0);
        }

        // Enhanced DoS protection with logging
        if (count > BURST_LIMIT) {
            // Potential DoS attack - disconnect client
            ctx.channel().close();
            return;
        } else if (count > MAX_COUNTER) {
            // Normal rate limiting - drop packet
            if (count > WARNING_THRESHOLD) {
                // Log suspicious activity
                if (client.getHabbo() != null) {
                    System.out.println("Rate limiting user: " + client.getHabbo().getHabboInfo().getUsername() + 
                                     " (Message ID: " + message.getMessageId() + ", Count: " + count + ")");
                } else {
                    System.out.println("Rate limiting anonymous client (IP: " + ctx.channel().remoteAddress() + 
                                     ", Message ID: " + message.getMessageId() + ", Count: " + count + ")");
                }
            }
            return;
        }

        client.incomingPacketCounter.put(message.getMessageId(), ++count);

        // Continue processing.
        out.add(message);
    }

}
