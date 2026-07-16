package top.ellan.mahjong.render.scene;

import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.render.display.DisplayClickAction;
import top.ellan.mahjong.render.display.DisplayEntities;
import top.ellan.mahjong.render.display.DisplayInteractionRayRegistry;
import top.ellan.mahjong.render.layout.TableRenderLayout;
import top.ellan.mahjong.render.TableRenderSubject;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * Renders the private (owner-only) and public hand tiles, including click hitboxes.
 */
public final class HandRenderer {
    private HandRenderer() {
    }

    public static List<DisplayEntities.EntitySpec> renderHandPrivateTileSpecs(
        TableRenderSubject session,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        int tileIndex
    ) {
        return renderHandPrivateTilePlan(session, seat, plan, tileIndex).entitySpecs();
    }

    public static HandTileRenderPlan renderHandPrivateTilePlan(
        TableRenderSubject session,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        int tileIndex
    ) {
        if (seat.playerId() == null || tileIndex < 0 || tileIndex >= seat.hand().size()) {
            return HandTileRenderPlan.empty();
        }

        List<UUID> ownerOnly = List.of(seat.playerId());
        Location tileLocation = TableGeometry.toLocation(session, plan.privateHandPoints().get(tileIndex));
        DisplayEntities.EntitySpec tileSpec = DisplayEntities.tileDisplay(
            tileLocation,
            plan.yaw(),
            session.currentVariant(),
            seat.hand().get(tileIndex),
            DisplayEntities.TileRenderPose.STANDING
        )
            .privateViewers(ownerOnly)
            .spec();
        TableGeometry.Offset acrossAxis = TableGeometry.offsetAcrossSeat(seat.wind(), 1.0D);
        UUID worldId = tileLocation.getWorld() == null ? null : tileLocation.getWorld().getUID();
        DisplayInteractionRayRegistry.RayInteraction interaction = new DisplayInteractionRayRegistry.RayInteraction(
            worldId,
            tileLocation.getX(),
            tileLocation.getY(),
            tileLocation.getZ(),
            acrossAxis.x(),
            acrossAxis.z(),
            TableRenderConstants.HAND_INTERACTION_WIDTH,
            TableRenderConstants.HAND_INTERACTION_HEIGHT,
            (float) TableRenderConstants.TILE_DEPTH,
            DisplayClickAction.handTile(session.id(), seat.playerId(), tileIndex)
        );
        return new HandTileRenderPlan(List.of(tileSpec), List.of(interaction));
    }

    public static List<Entity> renderHandPublic(
        TableRenderSubject session,
        TableRenderSnapshot snapshot,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan
    ) {
        if (seat.playerId() == null) {
            return List.of();
        }

        List<Entity> spawned = new ArrayList<>(seat.hand().size());
        List<UUID> ownerHidden = List.of(seat.playerId());
        for (int i = 0; i < seat.hand().size(); i++) {
            spawned.add(DisplayEntities.tileDisplay(
                TableGeometry.toLocation(session, plan.publicHandPoints().get(i)),
                plan.yaw(),
                session.currentVariant(),
                MahjongTile.UNKNOWN,
                DisplayEntities.TileRenderPose.STANDING
            )
                .hiddenViewers(ownerHidden)
                .spawn(session.bukkitPlugin()));
        }
        return spawned;
    }

    public static List<Entity> renderHandPublicTile(
        TableRenderSubject session,
        TableRenderSnapshot snapshot,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        int tileIndex
    ) {
        if (seat.playerId() == null || tileIndex < 0 || tileIndex >= seat.hand().size()) {
            return List.of();
        }

        List<UUID> ownerHidden = List.of(seat.playerId());
        return List.of(DisplayEntities.tileDisplay(
            TableGeometry.toLocation(session, plan.publicHandPoints().get(tileIndex)),
            plan.yaw(),
            session.currentVariant(),
            MahjongTile.UNKNOWN,
            DisplayEntities.TileRenderPose.STANDING
        )
            .hiddenViewers(ownerHidden)
            .spawn(session.bukkitPlugin()));
    }

    public static List<DisplayEntities.EntitySpec> renderHandPublicTileSpecs(
        TableRenderSubject session,
        TableRenderSnapshot snapshot,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        int tileIndex
    ) {
        if (seat.playerId() == null || tileIndex < 0 || tileIndex >= seat.hand().size()) {
            return List.of();
        }

        List<UUID> ownerHidden = List.of(seat.playerId());
        return List.of(DisplayEntities.tileDisplay(
            TableGeometry.toLocation(session, plan.publicHandPoints().get(tileIndex)),
            plan.yaw(),
            session.currentVariant(),
            MahjongTile.UNKNOWN,
            DisplayEntities.TileRenderPose.STANDING
        )
            .hiddenViewers(ownerHidden)
            .spec());
    }

    public record HandTileRenderPlan(
        List<DisplayEntities.EntitySpec> entitySpecs,
        List<DisplayInteractionRayRegistry.RayInteraction> rayInteractions
    ) {
        public HandTileRenderPlan {
            entitySpecs = List.copyOf(entitySpecs);
            rayInteractions = List.copyOf(rayInteractions);
        }

        static HandTileRenderPlan empty() {
            return new HandTileRenderPlan(List.of(), List.of());
        }
    }
}
