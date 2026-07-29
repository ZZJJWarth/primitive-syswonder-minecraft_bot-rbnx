// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraftforge.client.event.ClientChatReceivedEvent;

/** Relays explicitly addressed player chat through the loopback command interface. */
public final class MinecraftChatBridge {
    private static final int QUEUE_CAPACITY = 128;
    private static final int MAX_DRAIN = 32;
    private static final int MAX_CHAT_LENGTH = 256;
    private final ArrayBlockingQueue<IncomingChat> incoming = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final String prefix = envPrefix();

    /** Captures signed player chat only when it addresses the configured bot prefix. */
    public void onPlayerChat(ClientChatReceivedEvent.Player event) {
        Minecraft minecraft = Minecraft.getInstance();
        UUID sender = event.getSender();
        if (minecraft.player != null && sender.equals(minecraft.player.getUUID())) {
            return;
        }

        String content = event.getPlayerChatMessage().signedContent().trim();
        String instruction;
        if (content.equals(prefix)) {
            instruction = "";
        } else if (content.startsWith(prefix)
                && content.length() > prefix.length()
                && Character.isWhitespace(content.charAt(prefix.length()))) {
            instruction = content.substring(prefix.length()).trim();
        } else {
            return;
        }

        String senderName = sender.toString();
        if (minecraft.getConnection() != null) {
            PlayerInfo info = minecraft.getConnection().getPlayerInfo(sender);
            if (info != null) {
                senderName = info.getProfile().getName();
            }
        }
        IncomingChat chat = new IncomingChat(sender.toString(), senderName, instruction);
        if (!incoming.offer(chat)) {
            incoming.poll();
            incoming.offer(chat);
            RobonixMinecraftMod.LOGGER.warn("Minecraft chat bridge queue was full; dropped oldest request");
        }
    }

    /** Drains a bounded batch without blocking the Mod command worker. */
    public CompletableFuture<JsonObject> poll(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, MAX_DRAIN));
        JsonArray messages = new JsonArray();
        for (int index = 0; index < limit; index++) {
            IncomingChat chat = incoming.poll();
            if (chat == null) {
                break;
            }
            JsonObject message = new JsonObject();
            message.addProperty("sender_uuid", chat.senderUuid());
            message.addProperty("sender_name", chat.senderName());
            message.addProperty("text", chat.text());
            messages.add(message);
        }
        JsonObject result = new JsonObject();
        result.add("messages", messages);
        return CompletableFuture.completedFuture(result);
    }

    /** Sends one public server-chat message as the bot player on the client thread. */
    public CompletableFuture<JsonObject> send(String message) {
        if (message.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("chat message must not be empty"));
        }
        if (message.length() > MAX_CHAT_LENGTH) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("chat message exceeds 256 characters"));
        }
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            try {
                if (minecraft.player == null || minecraft.getConnection() == null) {
                    throw new IllegalStateException("Minecraft client is not connected to a server");
                }
                minecraft.getConnection().sendChat(message);
                JsonObject response = new JsonObject();
                response.addProperty("sent", true);
                result.complete(response);
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    private static String envPrefix() {
        String value = System.getenv().getOrDefault("RBNX_MINECRAFT_CHAT_PREFIX", "@bot").trim();
        if (value.isEmpty() || value.length() > 32 || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "RBNX_MINECRAFT_CHAT_PREFIX must be 1..32 characters without whitespace");
        }
        return value;
    }

    private record IncomingChat(String senderUuid, String senderName, String text) {}
}
