package com.eu.habbo.networking.camera.messages.outgoing;

import com.eu.habbo.Emulator;
import com.eu.habbo.networking.camera.CameraOutgoingMessage;
import com.eu.habbo.networking.camera.messages.CameraOutgoingHeaders;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CameraLoginComposer extends CameraOutgoingMessage {
    private static final Logger LOGGER = LoggerFactory.getLogger(CameraLoginComposer.class);
    
    public CameraLoginComposer() {
        super(CameraOutgoingHeaders.LoginComposer);
    }

    @Override
    public void compose(Channel channel) {
        String username = Emulator.getConfig().getValue("username", "");
        String password = Emulator.getConfig().getValue("password", "");
        
        // Security: Never log or expose passwords in plain text
        if (username.isEmpty() || password.isEmpty()) {
            LOGGER.warn("Camera authentication credentials not configured");
            return;
        }
        
        this.appendString(username.trim());
        this.appendString(password.trim());
        this.appendString(Emulator.version);
    }
}