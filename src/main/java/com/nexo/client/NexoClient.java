package com.nexo.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.Items;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NexoClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("nexo_client");

    private static final int HELLO_MAGIC = 5132360;
    private static final int REGISTRY_MAGIC = 5132370;
    private static final int MIN_SUPPORTED_PROTOCOL = 1;
    private static final int MAX_SUPPORTED_PROTOCOL = 1;
    private static final String CLIENT_IMPLEMENTATION_VERSION = "1.0.0";

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

    private static final ItemStack DEFAULT_TAB_ICON = new ItemStack(Items.BOOK);
    private static final Map<String, TabGroupData> syncedGroups = new LinkedHashMap<>();
    private static final List<ItemStack> syncedMaterials = new ArrayList<>();
    private static final List<MutableText> tabSlotTitles = new ArrayList<>(MAX_DYNAMIC_GROUP_TABS);
    private static final List<String> tabSlotGroupIds = new ArrayList<>(MAX_DYNAMIC_GROUP_TABS);

    private static volatile boolean registryReceived;
    private static volatile boolean pendingGroupRefresh;
    private static int helloAttempts;
    private static int helloRetryCountdown;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playC2S().register(HelloPayload.ID, HelloPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RegistryPayload.ID, RegistryPayload.CODEC);

        for (int slot = 0; slot < MAX_DYNAMIC_GROUP_TABS; slot++) {
            tabSlotGroupIds.add(null);
            registerSlotTab(slot);
        }
        resetTabSlots();

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.SEARCH).register(entries -> {
            for (ItemStack material : syncedMaterials) {
                entries.add(material.copy());
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(RegistryPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                registryReceived = applyRegistryPayload(payload, context.client());
            });
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            syncedGroups.clear();
            syncedMaterials.clear();
            resetTabSlots();
            registryReceived = false;
            pendingGroupRefresh = true;
            helloAttempts = 0;
            helloRetryCountdown = 0;
            sendHello("join");
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!registryReceived && helloAttempts < MAX_HELLO_ATTEMPTS && client.getNetworkHandler() != null) {
                helloRetryCountdown--;
                if (helloRetryCountdown <= 0) {
                    sendHello("retry");
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

    private static boolean applyRegistryPayload(RegistryPayload payload, MinecraftClient client) {
        if (!isProtocolSupported(payload.negotiatedProtocol())) {
            syncedGroups.clear();
            syncedMaterials.clear();
            resetTabSlots();
            pendingGroupRefresh = true;
            LOGGER.warn(
                    "Rejected registry payload protocol {} (client supports {}-{})",
                    payload.negotiatedProtocol(),
                    MIN_SUPPORTED_PROTOCOL,
                    MAX_SUPPORTED_PROTOCOL
            );
            return false;
        }

        if (payload.status() != STATUS_OK) {
            syncedGroups.clear();
            syncedMaterials.clear();
            resetTabSlots();
            pendingGroupRefresh = true;

            if (payload.status() == STATUS_INCOMPATIBLE) {
                LOGGER.warn("Server {} rejected client: {}", payload.serverVersion(), payload.message());
            } else if (payload.status() == STATUS_NO_PERMISSION) {
                LOGGER.warn("Server {} denied inventory sync: {}", payload.serverVersion(), payload.message());
            } else {
                LOGGER.warn("Server {} sent unknown status {}: {}", payload.serverVersion(), payload.status(), payload.message());
            }

            boolean terminalStatus = payload.status() == STATUS_NO_PERMISSION;
            if (terminalStatus && client.player != null) {
                client.player.sendMessage(Text.literal("[Nexo] " + payload.message()), false);
            }
            return terminalStatus;
        }

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
            ItemStack groupIcon = group.icon() == null || group.icon().isEmpty() ? DEFAULT_TAB_ICON.copy() : group.icon().copy();

            List<ItemStack> itemCopies = new ArrayList<>(group.items().size());
            for (ItemStack stack : group.items()) {
                if (stack != null && !stack.isEmpty()) {
                    itemCopies.add(stack.copy());
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
        syncedMaterials.clear();
        syncedMaterials.addAll(buildMaterialSnapshot(rebuiltGroups.values()));
        resetTabSlots();

        int slot = 0;
        for (TabGroupData rebuilt : rebuiltGroups.values()) {
            assignGroupToTabSlot(slot, rebuilt.id(), rebuilt.title());
            slot++;
        }
    }

    private static List<ItemStack> buildMaterialSnapshot(Iterable<TabGroupData> groups) {
        List<ItemStack> materials = new ArrayList<>();
        for (TabGroupData group : groups) {
            for (ItemStack source : group.items()) {
                if (source == null || source.isEmpty()) {
                    continue;
                }

                ItemStack normalized = source.copy();
                normalized.setCount(1);

                boolean duplicate = false;
                for (ItemStack existing : materials) {
                    if (ItemStack.areItemsAndComponentsEqual(existing, normalized)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    materials.add(normalized);
                }
            }
        }
        return List.copyOf(materials);
    }

    private static void registerSlotTab(int slotIndex) {
        MutableText displayName = Text.empty();
        tabSlotTitles.add(displayName);
        setSlotTitle(slotIndex, defaultSlotTitle(slotIndex));
        Identifier tabId = Identifier.of("nexo", "tab_slot_" + (slotIndex + 1));
        Registry.register(
                Registries.ITEM_GROUP,
                tabId,
                FabricItemGroup.builder()
                        .displayName(displayName)
                        .icon(() -> {
                            String groupId = tabSlotGroupIds.get(slotIndex);
                            if (groupId == null) {
                                return DEFAULT_TAB_ICON.copy();
                            }
                            TabGroupData current = syncedGroups.get(groupId);
                            if (current == null) {
                                return DEFAULT_TAB_ICON.copy();
                            }
                            return current.icon().copy();
                        })
                        .entries((context, entries) -> {
                            String groupId = tabSlotGroupIds.get(slotIndex);
                            if (groupId == null) {
                                return;
                            }
                            TabGroupData current = syncedGroups.get(groupId);
                            if (current == null) {
                                return;
                            }

                            for (ItemStack stack : current.items()) {
                                entries.add(stack.copy(), ItemGroup.StackVisibility.PARENT_AND_SEARCH_TABS);
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
        MutableText current = tabSlotTitles.get(slotIndex);
        current.getSiblings().clear();
        current.append(Text.literal(title));
    }

    private static String defaultSlotTitle(int slotIndex) {
        return "Nexo " + (slotIndex + 1);
    }

    private static boolean isProtocolSupported(int protocol) {
        return protocol >= MIN_SUPPORTED_PROTOCOL && protocol <= MAX_SUPPORTED_PROTOCOL;
    }

    private static boolean refreshItemGroups(MinecraftClient client) {
        if (client.player == null) {
            return false;
        }

        var player = client.player;
        if (player.networkHandler == null) {
            return false;
        }

        var features = player.networkHandler.getEnabledFeatures();
        if (features.isEmpty()) {
            // Join can fire before enabled features are negotiated; rebuilding now can blank the search tab.
            return false;
        }

        var lookup = player.getEntityWorld().getRegistryManager();
        boolean opTab = player.isCreativeLevelTwoOp();

        // Force one rebuild, then settle on the context used by the creative screen.
        ItemGroups.updateDisplayContext(features, opTab, lookup.toImmutable());
        ItemGroups.updateDisplayContext(features, opTab, lookup);
        return true;
    }

    private record HelloPayload(
            boolean modern,
            int minProtocol,
            int maxProtocol,
            String clientVersion,
            List<String> features
    ) implements CustomPayload {
        private static final Id<HelloPayload> ID = new Id<>(Identifier.of("nexo", "hello"));
        private static final PacketCodec<RegistryByteBuf, HelloPayload> CODEC =
                PacketCodec.of(HelloPayload::encode, HelloPayload::decode);

        private static HelloPayload decode(RegistryByteBuf buf) {
            int first = buf.readVarInt();
            if (first == HELLO_MAGIC) {
                int minProtocol = buf.readVarInt();
                int maxProtocol = buf.readVarInt();
                String clientVersion = buf.readString(64);

                int featureCount = buf.readVarInt();
                List<String> features = new ArrayList<>(Math.max(0, Math.min(featureCount, 64)));
                for (int i = 0; i < featureCount; i++) {
                    String feature = buf.readString(64);
                    if (i < 64) {
                        features.add(feature);
                    }
                }
                return new HelloPayload(true, minProtocol, maxProtocol, clientVersion, List.copyOf(features));
            }

            String clientVersion = buf.readableBytes() > 0 ? buf.readString(64) : "legacy";
            return new HelloPayload(false, first, first, clientVersion, List.of());
        }

        private static void encode(HelloPayload payload, RegistryByteBuf buf) {
            if (payload.modern) {
                buf.writeVarInt(HELLO_MAGIC);
                buf.writeVarInt(payload.minProtocol);
                buf.writeVarInt(payload.maxProtocol);
                buf.writeString(payload.clientVersion, 64);
                buf.writeVarInt(payload.features.size());
                for (String feature : payload.features) {
                    buf.writeString(feature, 64);
                }
                return;
            }

            buf.writeVarInt(payload.maxProtocol);
            buf.writeString(payload.clientVersion, 64);
        }

        @Override
        public Id<? extends CustomPayload> getId() {
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
            implements CustomPayload {
        private static final Id<RegistryPayload> ID = new Id<>(Identifier.of("nexo", "registry"));
        private static final PacketCodec<RegistryByteBuf, RegistryPayload> CODEC =
                PacketCodec.of(RegistryPayload::encode, RegistryPayload::decode);

        private static RegistryPayload decode(RegistryByteBuf buf) {
            int first = buf.readVarInt();

            int status;
            int negotiatedProtocol;
            String message;
            String serverVersion;
            List<String> serverFeatures = new ArrayList<>();

            if (first == REGISTRY_MAGIC) {
                status = buf.readVarInt();
                negotiatedProtocol = buf.readVarInt();
                serverVersion = buf.readString(64);
                message = buf.readString(256);

                int featureCount = buf.readVarInt();
                for (int i = 0; i < featureCount; i++) {
                    String feature = buf.readString(64);
                    if (i < 64) {
                        serverFeatures.add(feature);
                    }
                }
            } else {
                negotiatedProtocol = first;
                status = buf.readVarInt();
                message = buf.readString(256);
                serverVersion = "legacy";
            }

            List<TabGroupData> groups = new ArrayList<>();
            if (status == STATUS_OK) {
                int groupCount = buf.readVarInt();
                for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
                    String id = buf.readString(64);
                    String title = buf.readString(64);
                    ItemStack icon = ItemStack.OPTIONAL_PACKET_CODEC.decode(buf);

                    int itemCount = buf.readVarInt();
                    List<ItemStack> items = new ArrayList<>(itemCount);
                    for (int itemIndex = 0; itemIndex < itemCount; itemIndex++) {
                        items.add(ItemStack.OPTIONAL_PACKET_CODEC.decode(buf));
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

        private static void encode(RegistryPayload payload, RegistryByteBuf buf) {
            buf.writeVarInt(REGISTRY_MAGIC);
            buf.writeVarInt(payload.status);
            buf.writeVarInt(payload.negotiatedProtocol);
            buf.writeString(payload.serverVersion, 64);
            buf.writeString(payload.message, 256);
            buf.writeVarInt(payload.serverFeatures.size());
            for (String feature : payload.serverFeatures) {
                buf.writeString(feature, 64);
            }

            if (payload.status == STATUS_OK) {
                buf.writeVarInt(payload.groups.size());
                for (TabGroupData group : payload.groups) {
                    buf.writeString(group.id(), 64);
                    buf.writeString(group.title(), 64);
                    ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, group.icon());

                    buf.writeVarInt(group.items().size());
                    for (ItemStack stack : group.items()) {
                        ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, stack);
                    }
                }
            }
        }

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    private record TabGroupData(String id, String title, ItemStack icon, List<ItemStack> items) {
    }
}
