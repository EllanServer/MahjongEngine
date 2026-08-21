package top.ellan.mahjong.craftengine.privateview;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.presentation.node.SceneVisibility;
import top.ellan.mahjong.spi.PlayerId;

class PrivateFurnitureVisibilityTest {
    @Test
    void authorizationIsExactAndFailsClosed() {
        UUID furniture = UUID.randomUUID();
        PlayerId allowed = new PlayerId(UUID.randomUUID());
        PlayerId other = new PlayerId(UUID.randomUUID());
        try (PrivateFurnitureVisibility visibility = new PrivateFurnitureVisibility()) {
            visibility.authorizeIds(furniture, SceneVisibility.privateTo(allowed));

            assertTrue(visibility.canView(furniture, allowed.value()));
            assertFalse(visibility.canView(furniture, other.value()));
            assertFalse(visibility.canView(UUID.randomUUID(), allowed.value()));
        }
    }

    @Test
    void supportsAnExplicitSharedPrivateAudienceButRejectsPublicFurniture() {
        UUID furniture = UUID.randomUUID();
        PlayerId first = new PlayerId(UUID.randomUUID());
        PlayerId second = new PlayerId(UUID.randomUUID());
        try (PrivateFurnitureVisibility visibility = new PrivateFurnitureVisibility()) {
            visibility.authorizeIds(
                    furniture, SceneVisibility.privateTo(Set.of(first, second)));

            assertTrue(visibility.canView(furniture, first.value()));
            assertTrue(visibility.canView(furniture, second.value()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> visibility.authorizeIds(
                            UUID.randomUUID(), SceneVisibility.publicToAll()));
        }
    }

    @Test
    void closingRegistryRevokesEveryAudience() {
        UUID furniture = UUID.randomUUID();
        PlayerId viewer = new PlayerId(UUID.randomUUID());
        PrivateFurnitureVisibility visibility = new PrivateFurnitureVisibility();
        visibility.authorizeIds(furniture, SceneVisibility.privateTo(viewer));

        visibility.close();

        assertFalse(visibility.canView(furniture, viewer.value()));
    }
}
