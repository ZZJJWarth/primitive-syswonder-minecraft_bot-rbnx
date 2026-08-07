// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.event.TickEvent;

/** Executes bounded movement commands through normal client movement input. */
public final class MinecraftControl {
    private static final double WALK_SPEED_BLOCKS_PER_SECOND = 4.317;
    private ActiveMove activeMove;
    private ActiveVelocity activeVelocity;
    private boolean respawnRequested;
    private BooleanSupplier actionActive = () -> false;

    /** Installs the colocated action-state guard used by new motion commands. */
    void setActionActive(BooleanSupplier value) {
        actionActive = value;
    }

    /** Starts one movement command on the client thread and completes after its duration. */
    public CompletableFuture<JsonObject> move(String direction, long durationMs) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                beginMove(direction, durationMs, 0.0, result);
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    /** Moves until the player has covered the requested horizontal block distance. */
    public CompletableFuture<JsonObject> moveDistance(String direction, double distance) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                if (!Double.isFinite(distance) || distance <= 0.0 || distance > 32.0) {
                    throw new IllegalArgumentException("distance must be within (0, 32] blocks");
                }
                long timeoutMs = Math.min(30_000L, Math.max(5000L, (long) (distance * 1000.0)));
                beginMove(direction, timeoutMs, distance, result);
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    /** Applies relative yaw and pitch rotations and returns the new observation. */
    public CompletableFuture<JsonObject> turn(double yawDegrees, double pitchDegrees) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            try {
                LocalPlayer player = requirePlayer(minecraft);
                requireNoActionMotion();
                validateViewRotation(yawDegrees, pitchDegrees);
                finishActive(new IllegalStateException("movement replaced by a newer command"));
                finishVelocity(new IllegalStateException("movement replaced by a newer command"));
                applyViewRotation(player, yawDegrees, pitchDegrees);
                result.complete(ObservationFactory.create(minecraft));
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    /** Applies supported chassis velocity fields as bounded movement and view rotation. */
    public CompletableFuture<JsonObject> moveVelocity(
            double linearX,
            double linearY,
            double yawDegrees,
            double pitchDegrees,
            long durationMs
    ) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            try {
                startVelocityMove(minecraft, linearX, linearY, yawDegrees, pitchDegrees, durationMs, result);
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    /** Releases any active movement key and returns the current observation. */
    public CompletableFuture<JsonObject> stop() {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            finishActive(null);
            finishVelocity(null);
            try {
                result.complete(ObservationFactory.create(minecraft));
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    /** Releases all active movement immediately before a Minecraft action starts. */
    void stopForAction() {
        finishActive(new IllegalStateException("movement stopped by a Minecraft action"));
        finishVelocity(new IllegalStateException("movement stopped by a Minecraft action"));
    }

    /** Completes a bounded movement when its deadline passes at client tick end. */
    public void onClientTick(TickEvent.ClientTickEvent.Post event) {
        if (respawnIfNeeded(Minecraft.getInstance())) {
            return;
        }
        ActiveVelocity velocity = activeVelocity;
        if (velocity != null) {
            long nowNanos = System.nanoTime();
            applyVelocityView(velocity, nowNanos);
            if (nowNanos >= velocity.untilNanos()) {
                finishVelocity(null);
            }
        }
        ActiveMove move = activeMove;
        if (move == null) {
            return;
        }
        if (move.targetDistance() > 0.0 && horizontalDistance(move) >= move.targetDistance()) {
            finishActive(null);
        } else if (System.nanoTime() >= move.untilNanos()) {
            Exception failure = move.targetDistance() > 0.0
                    ? new TimeoutException("movement did not reach the requested distance") : null;
            finishActive(failure);
        }
    }

    /** Applies proportional planar movement input while a velocity command is active. */
    public void onMovementInput(MovementInputUpdateEvent event) {
        ActiveVelocity velocity = activeVelocity;
        if (velocity == null || event.getEntity() != Minecraft.getInstance().player) {
            return;
        }
        event.getInput().forwardImpulse = (float) (velocity.linearX() / WALK_SPEED_BLOCKS_PER_SECOND);
        event.getInput().leftImpulse = (float) (velocity.linearY() / WALK_SPEED_BLOCKS_PER_SECOND);
    }

    /** Ends commands outside a world or requests one respawn per player death. */
    private boolean respawnIfNeeded(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            respawnRequested = false;
            Exception failure = new IllegalStateException("Minecraft client is not in a world");
            finishActive(failure);
            finishVelocity(failure);
            return true;
        }
        if (player.isAlive()) {
            respawnRequested = false;
            return false;
        }
        if (!respawnRequested) {
            finishActive(new IllegalStateException("movement interrupted by player death"));
            finishVelocity(new IllegalStateException("movement interrupted by player death"));
            player.respawn();
            respawnRequested = true;
            RobonixMinecraftMod.LOGGER.info("Requested automatic player respawn");
        }
        return true;
    }

    /** Validates and starts proportional movement with gradual yaw and pitch rotation. */
    private void startVelocityMove(
            Minecraft minecraft,
            double linearX,
            double linearY,
            double yawDegrees,
            double pitchDegrees,
            long durationMs,
            CompletableFuture<JsonObject> result
    ) {
        requireNoActionMotion();
        if (!Double.isFinite(linearX) || !Double.isFinite(linearY)
                || !Double.isFinite(yawDegrees) || !Double.isFinite(pitchDegrees)) {
            throw new IllegalArgumentException("movement and view values must be finite");
        }
        if (durationMs < 50L || durationMs > 30_000L) {
            throw new IllegalArgumentException("velocity duration must be within [50, 30000] milliseconds");
        }
        if (Math.hypot(linearX, linearY) > WALK_SPEED_BLOCKS_PER_SECOND) {
            throw new IllegalArgumentException("planar velocity exceeds the normal Minecraft walking speed");
        }
        validateViewRotation(yawDegrees, pitchDegrees);
        LocalPlayer player = requirePlayer(minecraft);
        finishActive(new IllegalStateException("movement replaced by a newer command"));
        finishVelocity(new IllegalStateException("movement replaced by a newer command"));
        long startNanos = System.nanoTime();
        activeVelocity = new ActiveVelocity(
                startNanos,
                startNanos + durationMs * 1_000_000L,
                player,
                linearX,
                linearY,
                player.getYRot(),
                player.getXRot(),
                yawDegrees,
                pitchDegrees,
                result);
    }

    /** Activates one movement key while replacing an older bounded movement. */
    private void beginMove(
            String direction,
            long durationMs,
            double targetDistance,
            CompletableFuture<JsonObject> result
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        requireNoActionMotion();
        LocalPlayer player = requirePlayer(minecraft);
        KeyMapping key = keyFor(minecraft, direction);
        finishActive(new IllegalStateException("movement replaced by a newer command"));
        finishVelocity(new IllegalStateException("movement replaced by a newer command"));
        long untilNanos = System.nanoTime() + Math.max(50L, Math.min(30_000L, durationMs)) * 1_000_000L;
        activeMove = new ActiveMove(
                key, untilNanos, player.getX(), player.getZ(), targetDistance, result);
        key.setDown(true);
    }

    /** Prevents Nav2 or manual commands from racing a native Minecraft action. */
    private void requireNoActionMotion() {
        if (actionActive.getAsBoolean()) {
            throw new IllegalStateException("movement is unavailable while a Minecraft action is active");
        }
    }

    /** Releases the active key and resolves its result with the latest observation. */
    private void finishActive(Exception failure) {
        ActiveMove move = activeMove;
        if (move == null) {
            return;
        }
        move.key().setDown(false);
        CompletableFuture<JsonObject> result = move.result();
        activeMove = null;
        if (result == null) {
            return;
        }
        if (failure != null) {
            result.completeExceptionally(failure);
        } else {
            try {
                result.complete(ObservationFactory.create(Minecraft.getInstance()));
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        }
    }

    /** Stops proportional movement and resolves its result with the latest observation. */
    private void finishVelocity(Exception failure) {
        ActiveVelocity velocity = activeVelocity;
        if (velocity == null) {
            return;
        }
        velocity.player().input.forwardImpulse = 0.0F;
        velocity.player().input.leftImpulse = 0.0F;
        activeVelocity = null;
        if (failure != null) {
            velocity.result().completeExceptionally(failure);
        } else {
            try {
                velocity.result().complete(ObservationFactory.create(Minecraft.getInstance()));
            } catch (Exception exception) {
                velocity.result().completeExceptionally(exception);
            }
        }
    }

    /** Maps the confirmed movement vocabulary to Minecraft key mappings. */
    private static KeyMapping keyFor(Minecraft minecraft, String direction) {
        return switch (direction.toLowerCase(Locale.ROOT)) {
            case "forward" -> minecraft.options.keyUp;
            case "back" -> minecraft.options.keyDown;
            case "left" -> minecraft.options.keyLeft;
            case "right" -> minecraft.options.keyRight;
            case "sprint" -> minecraft.options.keySprint;
            case "sneak" -> minecraft.options.keyShift;
            default -> throw new IllegalArgumentException("unsupported direction: " + direction);
        };
    }

    /** Applies the requested fraction of yaw and pitch for the current velocity tick. */
    private static void applyVelocityView(ActiveVelocity velocity, long nowNanos) {
        LocalPlayer player = velocity.player();
        double elapsed = Math.max(0L, nowNanos - velocity.startNanos());
        double duration = velocity.untilNanos() - velocity.startNanos();
        double progress = Math.min(1.0, elapsed / duration);
        player.setYRot(finiteYaw(velocity.startYaw() - velocity.yawDegrees() * progress));
        player.setYHeadRot(player.getYRot());
        float pitch = velocity.startPitch() + (float) (velocity.pitchDegrees() * progress);
        player.setXRot(Math.max(-90.0F, Math.min(90.0F, pitch)));
    }

    /** Applies Minecraft-compatible yaw and clamped pitch deltas to the player view. */
    private static void applyViewRotation(LocalPlayer player, double yawDegrees, double pitchDegrees) {
        player.setYRot(finiteYaw((double) player.getYRot() - yawDegrees));
        player.setYHeadRot(player.getYRot());
        float pitch = player.getXRot() + (float) pitchDegrees;
        player.setXRot(Math.max(-90.0F, Math.min(90.0F, pitch)));
    }

    /** Rejects view deltas that cannot be represented safely by Minecraft floats. */
    private static void validateViewRotation(double yawDegrees, double pitchDegrees) {
        if (!Double.isFinite(yawDegrees) || !Double.isFinite(pitchDegrees)
                || !Float.isFinite((float) yawDegrees) || !Float.isFinite((float) pitchDegrees)) {
            throw new IllegalArgumentException("yaw and pitch rotations must be finite Minecraft angles");
        }
    }

    /** Keeps an accumulated yaw finite without wrapping ordinary multi-turn commands. */
    private static float finiteYaw(double yawDegrees) {
        double bounded = Math.abs(yawDegrees) <= Float.MAX_VALUE ? yawDegrees : Mth.wrapDegrees(yawDegrees);
        return (float) bounded;
    }

    /** Returns the local player or reports that the client has not joined a world. */
    private static LocalPlayer requirePlayer(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            throw new IllegalStateException("Minecraft client is not in a world");
        }
        return minecraft.player;
    }

    /** Measures horizontal displacement from the beginning of the active movement. */
    private static double horizontalDistance(ActiveMove move) {
        LocalPlayer player = requirePlayer(Minecraft.getInstance());
        return Math.hypot(player.getX() - move.startX(), player.getZ() - move.startZ());
    }

    /** Keeps the active movement state internally consistent across client ticks. */
    private record ActiveMove(
            KeyMapping key,
            long untilNanos,
            double startX,
            double startZ,
            double targetDistance,
            CompletableFuture<JsonObject> result
    ) {}

    /** Keeps one velocity-mode command consistent across input and client tick events. */
    private record ActiveVelocity(
            long startNanos,
            long untilNanos,
            LocalPlayer player,
            double linearX,
            double linearY,
            float startYaw,
            float startPitch,
            double yawDegrees,
            double pitchDegrees,
            CompletableFuture<JsonObject> result
    ) {}
}
