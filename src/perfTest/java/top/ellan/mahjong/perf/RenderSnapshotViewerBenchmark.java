package top.ellan.mahjong.perf;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.TableRenderSubject;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import top.ellan.mahjong.table.render.TableRenderSnapshotFactory;

/** Exercises the real viewer-membership snapshot path at realistic fan-out sizes. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RenderSnapshotViewerBenchmark {
    private final TableRenderSnapshotFactory factory = new TableRenderSnapshotFactory();
    private final TableRenderSubject viewers4 = fixture(4);
    private final TableRenderSubject viewers32 = fixture(32);
    private final TableRenderSubject viewers128 = fixture(128);
    private long version;

    @Benchmark
    public TableRenderSnapshot snapshotWith4Viewers() {
        return this.factory.create(this.viewers4, ++this.version, 0L);
    }

    @Benchmark
    public TableRenderSnapshot snapshotWith32Viewers() {
        return this.factory.create(this.viewers32, ++this.version, 0L);
    }

    @Benchmark
    public TableRenderSnapshot snapshotWith128Viewers() {
        return this.factory.create(this.viewers128, ++this.version, 0L);
    }

    private static TableRenderSubject fixture(int viewerCount) {
        List<UUID> viewerIds = new ArrayList<>(viewerCount);
        List<Player> viewers = new ArrayList<>(viewerCount);
        for (int index = 0; index < viewerCount; index++) {
            UUID viewerId = new UUID(0x4D41484A4F4E4700L, index + 1L);
            viewerIds.add(viewerId);
            viewers.add(player(viewerId));
        }
        EnumMap<SeatWind, UUID> seats = new EnumMap<>(SeatWind.class);
        for (SeatWind wind : SeatWind.values()) {
            seats.put(wind, viewerIds.get(wind.index()));
        }
        List<MahjongTile> hand = List.of(
            MahjongTile.M1,
            MahjongTile.M2,
            MahjongTile.M3,
            MahjongTile.M4,
            MahjongTile.M5,
            MahjongTile.M6,
            MahjongTile.M7,
            MahjongTile.P1,
            MahjongTile.P2,
            MahjongTile.P3,
            MahjongTile.S1,
            MahjongTile.S2,
            MahjongTile.S3
        );
        InvocationHandler handler = (proxy, method, arguments) -> switch (method.getName()) {
            case "center" -> new Location(null, 0.0D, 64.0D, 0.0D);
            case "viewers" -> viewers;
            case "playerAt" -> seats.get((SeatWind) arguments[0]);
            case "isStarted" -> true;
            case "isRoundFinished", "isRoundStartInProgress", "isQueuedToLeave" -> false;
            case "remainingWallCount" -> 70;
            case "kanCount", "roundIndex" -> 1;
            case "dicePoints" -> 7;
            case "breakDicePoints" -> 5;
            case "honbaCount" -> 2;
            case "dealerSeat" -> SeatWind.EAST;
            case "currentSeat" -> SeatWind.SOUTH;
            case "openDoorSeat" -> SeatWind.WEST;
            case "waitingDisplaySummary" -> "waiting";
            case "ruleDisplaySummary" -> "rules";
            case "publicCenterText" -> "center";
            case "lastPublicDiscardPlayerIdValue" -> viewerIds.get(0);
            case "lastPublicDiscardTile" -> MahjongTile.RED_DRAGON;
            case "doraIndicators" -> List.of(MahjongTile.M1, MahjongTile.P1, MahjongTile.S1);
            case "displayName" -> "viewer-" + viewerIds.indexOf(arguments[0]);
            case "publicSeatStatus" -> ((SeatWind) arguments[0]).name();
            case "points" -> 25_000;
            case "isRiichi", "isReady" -> true;
            case "selectedHandTileIndex", "riichiDiscardIndex" -> -1;
            case "selectedHandTileIndices", "fuuro", "scoringSticks", "cornerSticks" -> List.of();
            case "stickLayoutCount" -> 0;
            case "hand" -> hand;
            case "discards" -> List.of(MahjongTile.EAST, MahjongTile.SOUTH, MahjongTile.WEST);
            case "toString" -> "SnapshotFixture[viewers=" + viewerCount + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> defaultValue(method);
        };
        return (TableRenderSubject) Proxy.newProxyInstance(
            TableRenderSubject.class.getClassLoader(),
            new Class<?>[] {TableRenderSubject.class},
            handler
        );
    }

    private static Player player(UUID viewerId) {
        InvocationHandler handler = (proxy, method, arguments) -> switch (method.getName()) {
            case "getUniqueId" -> viewerId;
            case "isOnline" -> true;
            case "getName" -> "viewer-" + viewerId.getLeastSignificantBits();
            case "toString" -> "PlayerFixture[" + viewerId + "]";
            case "hashCode" -> viewerId.hashCode();
            case "equals" -> proxy == arguments[0];
            default -> defaultValue(method);
        };
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, handler);
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == double.class) {
            return 0.0D;
        }
        return null;
    }
}
