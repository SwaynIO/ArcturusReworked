package com.eu.habbo.habbohotel.items.interactions;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.items.Item;
import com.eu.habbo.habbohotel.items.interactions.wired.WiredSettings;
import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.habbohotel.rooms.RoomUnit;
import com.eu.habbo.messages.ClientMessage;
import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.rooms.items.ItemStateComposer;
import gnu.trove.map.hash.TLongLongHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public abstract class InteractionWired extends InteractionDefault {
    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionWired.class);
    private long cooldown;
    private TLongLongHashMap userExecutionCache = new TLongLongHashMap(3);

    InteractionWired(ResultSet set, Item baseItem) throws SQLException {
        super(set, baseItem);
        this.setExtradata("0");
    }

    InteractionWired(int id, int userId, Item item, String extradata, int limitedStack, int limitedSells) {
        super(id, userId, item, extradata, limitedStack, limitedSells);
        this.setExtradata("0");
    }

    public abstract boolean execute(RoomUnit roomUnit, Room room, Object[] stuff);

    public abstract String getWiredData();

    public abstract void serializeWiredData(ServerMessage message, Room room);

    public abstract void loadWiredData(ResultSet set, Room room) throws SQLException;

    @Override
    public void run() {
        if (this.needsUpdate()) {
            String wiredData = this.getWiredData();

            if (wiredData == null) {
                wiredData = "";
            }

            try (Connection connection = Emulator.getDatabase().getDataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("UPDATE items SET wired_data = ? WHERE id = ?")) {
                if (this.getRoomId() != 0) {
                    statement.setString(1, wiredData);
                } else {
                    statement.setString(1, "");
                }
                statement.setInt(2, this.getId());
                statement.execute();
            } catch (SQLException e) {
                LOGGER.error("Caught SQL exception", e);
            }
        }
        super.run();
    }

    @Override
    public void onPickUp(Room room) {
        this.onPickUp();
    }

    public abstract void onPickUp();

    public void activateBox(Room room) {
        this.activateBox(room, (RoomUnit)null, 0L);
    }

    public void activateBox(Room room, RoomUnit roomUnit, long millis) {
        this.setExtradata(this.getExtradata().equals("1") ? "0" : "1");
        room.sendComposer(new ItemStateComposer(this).compose());
        if (roomUnit != null) {
            this.addUserExecutionCache(roomUnit.getId(), millis);
        }
    }

    protected long requiredCooldown() {
        return 50L;
    }


    public boolean canExecute(long newMillis) {
        return newMillis - this.cooldown >= this.requiredCooldown();
    }

    public void setCooldown(long newMillis) {
        this.cooldown = newMillis;
    }

    @Override
    public boolean allowWiredResetState() {
        return false;
    }

    @Override
    public boolean isUsable() {
        return true;
    }

    public boolean userCanExecute(int roomUnitId, long timestamp) {
        if (roomUnitId == -1) {
            return true;
        } else {
            if (this.userExecutionCache.containsKey((long)roomUnitId)) {
                long lastTimestamp = this.userExecutionCache.get((long)roomUnitId);
                if (timestamp - lastTimestamp < 100L) {
                    return false;
                }
            }

            return true;
        }
    }

    public void clearUserExecutionCache() {
        this.userExecutionCache.clear();
    }

    public void addUserExecutionCache(int roomUnitId, long timestamp) {
        this.userExecutionCache.put((long)roomUnitId, timestamp);
    }

    public static WiredSettings readSettings(ClientMessage packet, boolean isEffect)
    {
        // Security: Validate array sizes to prevent DoS attacks
        int intParamCount = packet.readInt();
        if (intParamCount < 0 || intParamCount > 1000) { // Reasonable limit for wired parameters
            throw new IllegalArgumentException("Invalid intParamCount: " + intParamCount);
        }
        
        int[] intParams = new int[intParamCount];
        for(int i = 0; i < intParamCount; i++)
        {
            intParams[i] = packet.readInt();
        }

        String stringParam = packet.readString();
        // Security: Validate string length to prevent memory exhaustion
        if (stringParam != null && stringParam.length() > 10000) { // 10KB limit
            throw new IllegalArgumentException("String parameter too long: " + stringParam.length());
        }

        int itemCount = packet.readInt();
        if (itemCount < 0 || itemCount > 500) { // Reasonable limit for wired items
            throw new IllegalArgumentException("Invalid itemCount: " + itemCount);
        }
        
        int[] itemIds = new int[itemCount];
        for(int i = 0; i < itemCount; i++)
        {
            itemIds[i] = packet.readInt();
        }

        WiredSettings settings = new WiredSettings(intParams, stringParam, itemIds, -1);

        if(isEffect)
        {
            int delay = packet.readInt();
            // Security: Validate delay to prevent abuse
            if (delay < 0 || delay > 60000) { // Max 60 seconds delay
                throw new IllegalArgumentException("Invalid delay: " + delay);
            }
            settings.setDelay(delay);
        }

        int selectionCode = packet.readInt();
        // Security: Validate selection code range
        if (selectionCode < 0 || selectionCode > 1000) {
            throw new IllegalArgumentException("Invalid selection code: " + selectionCode);
        }
        settings.setStuffTypeSelectionCode(selectionCode);
        return settings;
    }
}
