package top.ellan.mahjong.craftengine.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.interaction.InteractionHandle;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.SceneNodeId;

class ManagedFurnitureIdentityTest {
    private static final TableId TABLE =
            new TableId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final SceneNodeId NODE = new SceneNodeId("interaction/discard/0");
    private static final InteractionHandle HANDLE =
            new InteractionHandle(UUID.fromString("22222222-2222-2222-2222-222222222222"));

    @Test
    void roundTripsThroughCraftEngineCustomFurnitureData() {
        ManagedFurnitureIdentity expected = new ManagedFurnitureIdentity(
                TABLE,
                NODE,
                ManagedFurnitureIdentity.Channel.PUBLIC,
                Optional.of(HANDLE));

        FurniturePersistentData persistent = expected.persistentData("ground");
        CompoundTag custom = (CompoundTag)
                persistent.getTag(FurniturePersistentData.CUSTOM_DATA);

        assertEquals(Optional.of(expected), ManagedFurnitureIdentity.from(custom));
        assertEquals(Optional.of("ground"), persistent.variant());
    }

    @Test
    void rejectsUnknownSchemaAndSeparatesLifecycleChannels() {
        assertTrue(ManagedFurnitureIdentity.from(new CompoundTag()).isEmpty());
        ManagedFurnitureIdentity publicIdentity = new ManagedFurnitureIdentity(
                TABLE, NODE, ManagedFurnitureIdentity.Channel.PUBLIC, Optional.empty());
        ManagedFurnitureIdentity privateIdentity = new ManagedFurnitureIdentity(
                TABLE, NODE, ManagedFurnitureIdentity.Channel.CONDITIONAL, Optional.empty());
        assertNotEquals(publicIdentity.semanticKey(), privateIdentity.semanticKey());
    }
}
