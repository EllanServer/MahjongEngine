package top.ellan.mahjong.craftengine.scene;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import top.ellan.mahjong.application.interaction.InteractionHandle;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.SceneNodeId;

/** Semantic identity persisted inside CE's own furniture data. */
public record ManagedFurnitureIdentity(
        TableId tableId,
        SceneNodeId nodeId,
        Channel channel,
        Optional<InteractionHandle> interaction) {
    private static final int SCHEMA = 1;
    private static final String SCHEMA_KEY = "mahjongpaper_schema";
    private static final String TABLE_KEY = "mahjongpaper_table";
    private static final String NODE_KEY = "mahjongpaper_node";
    private static final String CHANNEL_KEY = "mahjongpaper_channel";
    private static final String INTERACTION_KEY = "mahjongpaper_interaction";

    public ManagedFurnitureIdentity {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(channel, "channel");
        interaction = Objects.requireNonNull(interaction, "interaction");
        if (channel == Channel.CONDITIONAL && interaction.isPresent()) {
            throw new IllegalArgumentException("Conditional furniture cannot own an interaction handle");
        }
    }

    FurniturePersistentData persistentData(String variant) {
        FurniturePersistentData data = FurniturePersistentData.of(new CompoundTag());
        if (variant != null && !variant.isBlank()) {
            data.setVariant(variant);
        }
        data.addTag(FurniturePersistentData.CUSTOM_DATA, customData());
        return data;
    }

    CompoundTag customData() {
        CompoundTag data = new CompoundTag();
        writeTo(data);
        return data;
    }

    void writeTo(CompoundTag data) {
        data.putInt(SCHEMA_KEY, SCHEMA);
        data.putString(TABLE_KEY, tableId.toString());
        data.putString(NODE_KEY, nodeId.value());
        data.putString(CHANNEL_KEY, channel.id());
        interaction.ifPresent(handle -> data.putString(INTERACTION_KEY, handle.value().toString()));
    }

    String semanticKey() {
        return tableId + "\n" + channel.id() + "\n" + nodeId.value();
    }

    static Optional<ManagedFurnitureIdentity> from(Furniture furniture) {
        Objects.requireNonNull(furniture, "furniture");
        Tag data = furniture.persistentData().getTag(FurniturePersistentData.CUSTOM_DATA);
        return data instanceof CompoundTag compound ? from(compound) : Optional.empty();
    }

    static Optional<ManagedFurnitureIdentity> from(CompoundTag data) {
        Objects.requireNonNull(data, "data");
        if (data.getInt(SCHEMA_KEY, -1) != SCHEMA) {
            return Optional.empty();
        }
        try {
            TableId tableId = TableId.parse(data.getString(TABLE_KEY, ""));
            SceneNodeId nodeId = new SceneNodeId(data.getString(NODE_KEY, ""));
            Channel channel = Channel.from(data.getString(CHANNEL_KEY, ""));
            String encodedInteraction = data.getString(INTERACTION_KEY, "");
            Optional<InteractionHandle> interaction = encodedInteraction.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new InteractionHandle(UUID.fromString(encodedInteraction)));
            return Optional.of(new ManagedFurnitureIdentity(tableId, nodeId, channel, interaction));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public enum Channel {
        PUBLIC("public"),
        CONDITIONAL("conditional");

        private final String id;

        Channel(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        static Channel from(String id) {
            for (Channel channel : values()) {
                if (channel.id.equals(id)) {
                    return channel;
                }
            }
            throw new IllegalArgumentException("Unknown managed furniture channel: " + id);
        }
    }
}
