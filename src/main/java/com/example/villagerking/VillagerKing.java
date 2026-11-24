package com.example.villagerking;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager; // if this fails: net.minecraft.world.entity.npc.Villager

import java.util.*;

public class VillagerKing implements ModInitializer {

    enum Order { FOLLOW, STAY, HOME, FREE }

    // villager UUID -> (order, king UUID)
    record Command(Order order, UUID king) {}
    private static final Map<UUID, Command> ORDERS = new HashMap<>();

    private static final double HEAR_RADIUS = 32.0;
    // The king must address the crowd, so normal chat doesn't trigger anything.
    private static final List<String> ADDRESS = List.of("villagers", "villager", "subjects", "my people");

    @Override
    public void onInitialize() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
                handleChat(sender, message.signedContent()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 10 == 0) tickOrders(server);
        });
    }

    private void handleChat(ServerPlayer king, String raw) {
        String text = raw.toLowerCase(Locale.ROOT);
        if (ADDRESS.stream().noneMatch(text::contains)) return;

        Order order = parse(text);
        if (order == null) return;

        ServerLevel level = (ServerLevel) king.level();
        List<Villager> heard = level.getEntitiesOfClass(Villager.class,
                king.getBoundingBox().inflate(HEAR_RADIUS));

        if (heard.isEmpty()) {
            king.sendSystemMessage(Component.literal("No villagers are close enough to hear you, Your Majesty."));
            return;
        }

        for (Villager v : heard) {
            if (v.isBaby()) continue;
            if (order == Order.FREE) ORDERS.remove(v.getUUID());
            else ORDERS.put(v.getUUID(), new Command(order, king.getUUID()));
            v.playSound(SoundEvents.VILLAGER_YES, 1.0f, 1.0f);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    v.getX(), v.getY() + 1.5, v.getZ(), 5, 0.3, 0.3, 0.3, 0.0);
        }
        king.sendSystemMessage(Component.literal(
                "Long live the king! " + heard.size() + " villager(s) obey: " + order.name().toLowerCase()));
    }

    private static Order parse(String t) {
        if (t.contains("follow") || t.contains("come"))                       return Order.FOLLOW;
        if (t.contains("stay") || t.contains("stop") || t.contains("wait"))   return Order.STAY;
        if (t.contains("home") || t.contains("bed"))                          return Order.HOME;
        if (t.contains("free") || t.contains("dismiss") || t.contains("at ease") || t.contains("work"))
                                                                              return Order.FREE;
        return null;
    }

    private void tickOrders(net.minecraft.server.MinecraftServer server) {
        Iterator<Map.Entry<UUID, Command>> it = ORDERS.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Command cmd = e.getValue();
            Villager villager = null;
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(e.getKey()) instanceof Villager v) { villager = v; break; }
            }
            if (villager == null || !villager.isAlive()) { it.remove(); continue; }

            var brain = villager.getBrain();
            switch (cmd.order()) {
                case FOLLOW -> {
                    ServerPlayer king = server.getPlayerList().getPlayer(cmd.king());
                    if (king == null || king.level() != villager.level()) break;
                    brain.setMemory(MemoryModuleType.WALK_TARGET,
                            new WalkTarget(new EntityTracker(king, false), 0.6f, 3));
                }
                case STAY -> {
                    brain.eraseMemory(MemoryModuleType.WALK_TARGET);
                    villager.getNavigation().stop();
                }
                case HOME -> {
                    var home = brain.getMemory(MemoryModuleType.HOME);
                    if (home.isPresent() && home.get().dimension() == villager.level().dimension()) {
                        BlockPos pos = home.get().pos();
                        brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(pos, 0.6f, 1));
                        if (villager.blockPosition().closerThan(pos, 2.0)) it.remove(); // arrived
                    } else {
                        it.remove(); // no bed claimed
                    }
                }
                default -> it.remove();
            }
        }
    }
}
