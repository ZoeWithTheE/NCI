package com.nexo.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NexoClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("nexo_client");

    private static final int HELLO_MAGIC = 5132360;
    private static final int REGISTRY_MAGIC = 5132370;
    private static final int MIN_SUPPORTED_PROTOCOL = 1;
    private static final int MAX_SUPPORTED_PROTOCOL = 2;
    private static final String CLIENT_IMPLEMENTATION_VERSION = FabricLoader.getInstance()
            .getModContainer("nexo_client")
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");

    private static final int STATUS_OK = 0;
    private static final int STATUS_INCOMPATIBLE = 1;
    private static final int STATUS_NO_PERMISSION = 2;

    private static final int HELLO_RETRY_TICKS = 20;
    private static final int MAX_HELLO_ATTEMPTS = 6;
    private static final int MAX_DYNAMIC_GROUP_TABS = 8;
    private static final List<String> CLIENT_FEATURES = List.of(
            "hello.range.v1",
            "registry.envelope.v1",
            "registry.groups.v1"
    );

    private static final Map<String, TabGroupData> syncedGroups = new LinkedHashMap<>();
    private static final List<MutableComponent> tabSlotTitles = new ArrayList<>(MAX_DYNAMIC_GROUP_TABS);
    private static final List<String> tabSlotGroupIds = new ArrayList<>(MAX_DYNAMIC_GROUP_TABS);

    private static volatile boolean registryReceived;
    private static volatile boolean pendingGroupRefresh;
    private static int helloAttempts;
    private static int helloRetryCountdown;

    // Protocol 2 chunked delivery state
    private static int negotiatedProtocol = 0;
    private static String pendingServerVersion = null;
    private static volatile boolean awaitingChunks = false;
    private static int chunkFinalizeCountdown = 0;
    private static final Map<String, TabGroupData> chunkAccumulator = new LinkedHashMap<>();

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.serverboundPlay().register(HelloPayload.ID, HelloPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RegistryPayload.ID, RegistryPayload.CODEC);

        for (int slot = 0; slot < MAX_DYNAMIC_GROUP_TABS; slot++) {
            tabSlotGroupIds.add(null);
            registerSlotTab(slot);
        }
        resetTabSlots();

        ClientPlayNetworking.registerGlobalReceiver(RegistryPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                if (registryReceived) {
                    LOGGER.warn("Ignoring duplicate registry payload from server {} — already synced", payload.serverVersion());
                    return;
                }
                registryReceived = applyRegistryPayload(payload, context.client());
            });
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            boolean hadSyncedContent = !syncedGroups.isEmpty();
            clearSyncedState();
            registryReceived = false;
            pendingGroupRefresh = hadSyncedContent;
            helloAttempts = 0;
            helloRetryCountdown = 0;
            negotiatedProtocol = 0;
            pendingServerVersion = null;
            awaitingChunks = false;
            chunkFinalizeCountdown = 0;
            chunkAccumulator.clear();
            sendHello("join");
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!registryReceived && !awaitingChunks && helloAttempts < MAX_HELLO_ATTEMPTS && client.getConnection() != null) {
                helloRetryCountdown--;
                if (helloRetryCountdown <= 0) {
                    sendHello("retry");
                }
            }

            if (awaitingChunks && chunkFinalizeCountdown > 0) {
                chunkFinalizeCountdown--;
                if (chunkFinalizeCountdown == 0) {
                    rebuildSyncedGroups(new ArrayList<>(chunkAccumulator.values()));
                    chunkAccumulator.clear();
                    awaitingChunks = false;
                    registryReceived = true;
                    pendingGroupRefresh = true;
                    LOGGER.info(
                            "Registry sync complete from server {} using protocol {} ({} groups)",
                            pendingServerVersion,
                            negotiatedProtocol,
                            syncedGroups.size()
                    );
                }
            }

            if (pendingGroupRefresh && refreshItemGroups(client)) {
                pendingGroupRefresh = false;
                LOGGER.info("Refreshed creative tab display after registry sync");
            }
        });
    }

    private static void sendHello(String reason) {
        boolean modern = helloAttempts % 2 == 0;
        ClientPlayNetworking.send(new HelloPayload(
                modern,
                MIN_SUPPORTED_PROTOCOL,
                MAX_SUPPORTED_PROTOCOL,
                CLIENT_IMPLEMENTATION_VERSION,
                CLIENT_FEATURES
        ));
        helloAttempts++;
        helloRetryCountdown = HELLO_RETRY_TICKS;
        LOGGER.info("Sent nexo hello attempt {} ({}, mode={})", helloAttempts, reason, modern ? "modern" : "legacy");
    }

    private static boolean applyRegistryPayload(RegistryPayload payload, Minecraft client) {
        boolean hadSyncedContent = !syncedGroups.isEmpty();
        if (!isProtocolSupported(payload.negotiatedProtocol())) {
            clearSyncedState();
            pendingGroupRefresh = hadSyncedContent;
            LOGGER.warn(
                    "Rejected registry payload protocol {} (client supports {}-{})",
                    payload.negotiatedProtocol(),
                    MIN_SUPPORTED_PROTOCOL,
                    MAX_SUPPORTED_PROTOCOL
            );
            return false;
        }

        if (payload.status() != STATUS_OK) {
            clearSyncedState();
            pendingGroupRefresh = hadSyncedContent;
            if (payload.status() == STATUS_INCOMPATIBLE) {
                LOGGER.warn("Server {} rejected client: {}", payload.serverVersion(), payload.message());
            } else if (payload.status() == STATUS_NO_PERMISSION) {
                LOGGER.warn("Server {} denied inventory sync: {}", payload.serverVersion(), payload.message());
            } else {
                LOGGER.warn("Server {} sent unknown status {}: {}", payload.serverVersion(), payload.status(), payload.message());
            }

            if (payload.status() == STATUS_NO_PERMISSION && client.player != null) {
                client.player.sendSystemMessage(Component.literal("[Nexo] " + payload.message()));
            }
            // Any explicit status response from the server is terminal — stop retrying.
            return true;
        }

        if (payload.negotiatedProtocol() < MAX_SUPPORTED_PROTOCOL) {
            LOGGER.warn(
                    "Server {} negotiated protocol {} — client supports up to {}; falling back to legacy single-packet mode",
                    payload.serverVersion(),
                    payload.negotiatedProtocol(),
                    MAX_SUPPORTED_PROTOCOL
            );
        }

        negotiatedProtocol = payload.negotiatedProtocol();

        if (negotiatedProtocol >= 2) {
            // Protocol 2: server sends groups spread across multiple nexo:registry packets.
            // Accumulate all chunks; finalize 5 ticks after the last one arrives.
            if (!awaitingChunks) {
                chunkAccumulator.clear();
                awaitingChunks = true;
                pendingServerVersion = payload.serverVersion();
            }
            for (TabGroupData group : payload.groups()) {
                if (group.id() == null || group.id().isBlank()) continue;
                if (chunkAccumulator.containsKey(group.id())) {
                    TabGroupData existing = chunkAccumulator.get(group.id());
                    List<ItemStack> merged = new ArrayList<>(existing.items());
                    merged.addAll(group.items());
                    chunkAccumulator.put(group.id(), new TabGroupData(
                            existing.id(), existing.title(), existing.icon(), List.copyOf(merged)));
                } else {
                    chunkAccumulator.put(group.id(), group);
                }
            }
            chunkFinalizeCountdown = 5;
            LOGGER.info(
                    "Received registry chunk from server {} (protocol {}, {} groups accumulated)",
                    payload.serverVersion(),
                    negotiatedProtocol,
                    chunkAccumulator.size()
            );
            return false;
        }

        // Protocol 1: single packet contains all groups.
        rebuildSyncedGroups(payload.groups());
        pendingGroupRefresh = true;
        LOGGER.info(
                "Received {} tab groups from server {} using protocol {} (rendering {} groups)",
                payload.groups().size(),
                payload.serverVersion(),
                payload.negotiatedProtocol(),
                syncedGroups.size()
        );
        return true;
    }

    private static void rebuildSyncedGroups(List<TabGroupData> incomingGroups) {
        Map<String, TabGroupData> rebuiltGroups = new LinkedHashMap<>();
        for (TabGroupData group : incomingGroups) {
            if (group.id() == null || group.id().isBlank()) {
                LOGGER.warn("Ignoring tab group with blank id");
                continue;
            }
            if (rebuiltGroups.size() >= MAX_DYNAMIC_GROUP_TABS) {
                LOGGER.warn("Dropping tab group '{}' because client supports {} dynamic tab slots", group.id(), MAX_DYNAMIC_GROUP_TABS);
                continue;
            }
            if (rebuiltGroups.containsKey(group.id())) {
                LOGGER.warn("Ignoring duplicate tab group id '{}' in registry payload", group.id());
                continue;
            }

            String groupTitle = group.title() == null || group.title().isBlank() ? group.id() : group.title();
            ItemStack groupIcon = group.icon() == null || group.icon().isEmpty() ? defaultTabIcon() : group.icon().copy();

            List<ItemStack> itemCopies = new ArrayList<>(group.items().size());
            for (ItemStack stack : group.items()) {
                if (stack != null && !stack.isEmpty()) {
                    ItemStack copy = stack.copy();
                    copy.remove(DataComponents.CONSUMABLE);
                    itemCopies.add(copy);
                }
            }

            rebuiltGroups.put(group.id(), new TabGroupData(
                    group.id(),
                    groupTitle,
                    groupIcon,
                    List.copyOf(itemCopies)
            ));
        }

        // Replace entire synced state in one pass so previous payload data cannot leak into the new snapshot.
        syncedGroups.clear();
        syncedGroups.putAll(rebuiltGroups);
        resetTabSlots();

        int slot = 0;
        for (TabGroupData rebuilt : rebuiltGroups.values()) {
            assignGroupToTabSlot(slot, rebuilt.id(), rebuilt.title());
            slot++;
        }
    }

    private static void registerSlotTab(int slotIndex) {
        MutableComponent displayName = Component.empty();
        tabSlotTitles.add(displayName);
        setSlotTitle(slotIndex, defaultSlotTitle(slotIndex));
        Identifier tabId = Identifier.fromNamespaceAndPath("nexo", "tab_slot_" + (slotIndex + 1));
        Registry.register(
                BuiltInRegistries.CREATIVE_MODE_TAB,
                tabId,
                FabricCreativeModeTab.builder()
                        .title(displayName)
                        .icon(() -> {
                            String groupId = tabSlotGroupIds.get(slotIndex);
                            if (groupId == null) {
                                return defaultTabIcon();
                            }
                            TabGroupData current = syncedGroups.get(groupId);
                            if (current == null) {
                                return defaultTabIcon();
                            }
                            return current.icon().copy();
                        })
                        .displayItems((parameters, output) -> {
                            String groupId = tabSlotGroupIds.get(slotIndex);
                            if (groupId == null) {
                                return;
                            }
                            TabGroupData current = syncedGroups.get(groupId);
                            if (current == null) {
                                return;
                            }

                            java.util.List<ItemStack> seen = new java.util.ArrayList<>();
                            for (ItemStack stack : current.items()) {
                                if (seen.stream().noneMatch(s -> ItemStack.isSameItemSameComponents(s, stack))) {
                                    seen.add(stack);
                                    output.accept(stack.copy(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                                }
                            }
                        })
                        .build()
        );
        LOGGER.info("Registered tab slot {} as {}", slotIndex + 1, tabId);
    }

    private static void assignGroupToTabSlot(int slotIndex, String groupId, String groupTitle) {
        tabSlotGroupIds.set(slotIndex, groupId);
        setSlotTitle(slotIndex, groupTitle);
    }

    private static void resetTabSlots() {
        for (int slot = 0; slot < MAX_DYNAMIC_GROUP_TABS; slot++) {
            tabSlotGroupIds.set(slot, null);
            setSlotTitle(slot, defaultSlotTitle(slot));
        }
    }

    private static void setSlotTitle(int slotIndex, String title) {
        MutableComponent current = tabSlotTitles.get(slotIndex);
        current.getSiblings().clear();
        current.append(Component.literal(title));
    }

    // Item components are bound after client entrypoints run on 26.x, so the icon can't be a static ItemStack.
    private static ItemStack defaultTabIcon() {
        return new ItemStack(Items.BOOK);
    }

    private static String defaultSlotTitle(int slotIndex) {
        return "Nexo " + (slotIndex + 1);
    }

    private static boolean isProtocolSupported(int protocol) {
        return protocol >= MIN_SUPPORTED_PROTOCOL && protocol <= MAX_SUPPORTED_PROTOCOL;
    }

    private static void clearSyncedState() {
        syncedGroups.clear();
        resetTabSlots();
    }

    private static boolean refreshItemGroups(Minecraft client) {
        if (client.player == null) {
            return false;
        }

        var player = client.player;
        if (player.connection == null) {
            return false;
        }

        return true;
    }

    private record HelloPayload(
            boolean modern,
            int minProtocol,
            int maxProtocol,
            String clientVersion,
            List<String> features
    ) implements CustomPacketPayload {
        private static final Type<HelloPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("nexo", "hello"));
        private static final StreamCodec<RegistryFriendlyByteBuf, HelloPayload> CODEC =
                StreamCodec.ofMember(HelloPayload::encode, HelloPayload::decode);

        private static HelloPayload decode(RegistryFriendlyByteBuf buf) {
            int first = buf.readVarInt();
            if (first == HELLO_MAGIC) {
                int minProtocol = buf.readVarInt();
                int maxProtocol = buf.readVarInt();
                String clientVersion = buf.readUtf(64);

                int featureCount = buf.readVarInt();
                List<String> features = new ArrayList<>(Math.max(0, Math.min(featureCount, 64)));
                for (int i = 0; i < featureCount; i++) {
                    String feature = buf.readUtf(64);
                    if (i < 64) {
                        features.add(feature);
                    }
                }
                return new HelloPayload(true, minProtocol, maxProtocol, clientVersion, List.copyOf(features));
            }

            String clientVersion = buf.readableBytes() > 0 ? buf.readUtf(64) : "legacy";
            return new HelloPayload(false, first, first, clientVersion, List.of());
        }

        private static void encode(HelloPayload payload, RegistryFriendlyByteBuf buf) {
            if (payload.modern) {
                buf.writeVarInt(HELLO_MAGIC);
                buf.writeVarInt(payload.minProtocol);
                buf.writeVarInt(payload.maxProtocol);
                buf.writeUtf(payload.clientVersion, 64);
                buf.writeVarInt(payload.features.size());
                for (String feature : payload.features) {
                    buf.writeUtf(feature, 64);
                }
                return;
            }

            buf.writeVarInt(payload.maxProtocol);
            buf.writeUtf(payload.clientVersion, 64);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    private record RegistryPayload(
            int negotiatedProtocol,
            int status,
            String message,
            String serverVersion,
            List<String> serverFeatures,
            List<TabGroupData> groups
    )
            implements CustomPacketPayload {
        private static final Type<RegistryPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("nexo", "registry"));
        private static final StreamCodec<RegistryFriendlyByteBuf, RegistryPayload> CODEC =
                StreamCodec.ofMember(RegistryPayload::encode, RegistryPayload::decode);

        private static RegistryPayload decode(RegistryFriendlyByteBuf buf) {
            int first = buf.readVarInt();

            int status;
            int negotiatedProtocol;
            String message;
            String serverVersion;
            List<String> serverFeatures = new ArrayList<>();

            if (first == REGISTRY_MAGIC) {
                status = buf.readVarInt();
                negotiatedProtocol = buf.readVarInt();
                serverVersion = buf.readUtf(64);
                message = buf.readUtf(256);

                int featureCount = buf.readVarInt();
                for (int i = 0; i < featureCount; i++) {
                    String feature = buf.readUtf(64);
                    if (i < 64) {
                        serverFeatures.add(feature);
                    }
                }
            } else {
                negotiatedProtocol = first;
                status = buf.readVarInt();
                message = buf.readUtf(256);
                serverVersion = "legacy";
            }

            List<TabGroupData> groups = new ArrayList<>();
            if (status == STATUS_OK) {
                int groupCount = buf.readVarInt();
                for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
                    String id = buf.readUtf(64);
                    String title = buf.readUtf(64);
                    ItemStack icon = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);

                    int itemCount = buf.readVarInt();
                    List<ItemStack> items = new ArrayList<>(itemCount);
                    for (int itemIndex = 0; itemIndex < itemCount; itemIndex++) {
                        items.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
                    }

                    groups.add(new TabGroupData(id, title, icon, List.copyOf(items)));
                }
            }

            return new RegistryPayload(
                    negotiatedProtocol,
                    status,
                    message,
                    serverVersion,
                    List.copyOf(serverFeatures),
                    List.copyOf(groups)
            );
        }

        private static void encode(RegistryPayload payload, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(REGISTRY_MAGIC);
            buf.writeVarInt(payload.status);
            buf.writeVarInt(payload.negotiatedProtocol);
            buf.writeUtf(payload.serverVersion, 64);
            buf.writeUtf(payload.message, 256);
            buf.writeVarInt(payload.serverFeatures.size());
            for (String feature : payload.serverFeatures) {
                buf.writeUtf(feature, 64);
            }

            if (payload.status == STATUS_OK) {
                buf.writeVarInt(payload.groups.size());
                for (TabGroupData group : payload.groups) {
                    buf.writeUtf(group.id(), 64);
                    buf.writeUtf(group.title(), 64);
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, group.icon());

                    buf.writeVarInt(group.items().size());
                    for (ItemStack stack : group.items()) {
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
                    }
                }
            }
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    private record TabGroupData(String id, String title, ItemStack icon, List<ItemStack> items) {
    }
}
