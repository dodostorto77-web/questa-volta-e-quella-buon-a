package autostunslam;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Stun slam: colpo con l'ascia (disabilita lo scudo) -> cambio alla mace -> colpo con la mace.
 * Tutto gira sul tick del client (nessun thread separato), quindi niente race condition.
 * Tasto di default: G (cambiabile in Opzioni > Controlli).
 */
public class StunSlamMod implements ClientModInitializer {

    // Config
    private static final int HIT_DELAY_MIN = 2;        // tick minimi tra ascia e mace
    private static final int HIT_DELAY_MAX = 4;        // tick massimi
    private static final int MAX_WAIT_TICKS = 80;      // sicurezza: oltre questo tempo si annulla
    private static final boolean DEBUG = true;         // mostra messaggi sopra la hotbar per capire dove si ferma

    private enum State { IDLE, AXE_HIT, MACE_HIT }

    private KeyMapping stunSlamKey;
    private State state = State.IDLE;
    private int ticksLeft = 0;
    private int totalTicks = 0;
    private int previousSlot = 0;
    private Entity target = null;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("autostunslam", "main"));
        stunSlamKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.autostunslam.activate",
                InputConstants.KEY_G,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || client.level == null) {
            reset();
            return;
        }

        if (state == State.IDLE) {
            while (stunSlamKey.consumeClick()) {
                start(client, player);
            }
            return;
        }

        // Annulla se il bersaglio non e' piu' valido o e' passato troppo tempo
        if (target == null || !target.isAlive() || ++totalTicks > MAX_WAIT_TICKS) {
            dbg(player, "Annullato: bersaglio non valido o timeout (tick " + totalTicks + ")");
            finish(player);
            return;
        }

        switch (state) {
            case AXE_HIT -> {
                // Attesa tra ascia e mace (copre anche il ping)
                if (--ticksLeft > 0) {
                    return;
                }
                int maceSlot = findSlot(player, true);
                if (maceSlot < 0) {
                    dbg(player, "Mace non trovata nella hotbar (slot 1-9)");
                    finish(player);
                    return;
                }
                // Cambio e colpo nello stesso tick (il pacchetto dello slot parte prima dell'attacco)
                player.getInventory().setSelectedSlot(maceSlot);
                attack(client, player, target);
                dbg(player, "Mace colpita, slot " + (maceSlot + 1) + " (caduta " + String.format("%.1f", player.fallDistance) + ")");
                finish(player);
            }
            case MACE_HIT -> {
                // Colpisce subito dopo il cambio, senza aspettare che la mace sia carica.
                attack(client, player, target);
                dbg(player, "Mace colpita (caduta " + String.format("%.1f", player.fallDistance) + ")");
                finish(player);
            }
            default -> { }
        }
    }

    private void start(Minecraft client, LocalPlayer player) {
        HitResult hit = client.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.ENTITY) {
            dbg(player, "Nessun bersaglio nel mirino");
            return;
        }
        Entity entity = ((EntityHitResult) hit).getEntity();
        if (!(entity instanceof LivingEntity)) {
            dbg(player, "Il bersaglio non e' un'entita' vivente");
            return;
        }
        int axeSlot = findSlot(player, false);
        int maceSlot = findSlot(player, true);
        if (axeSlot < 0 || maceSlot < 0) {
            dbg(player, "Servono ascia e mace nella hotbar (slot 1-9)");
            return;
        }

        target = entity;
        previousSlot = player.getInventory().getSelectedSlot();
        totalTicks = 0;

        // 1) Ascia: colpisce e disabilita lo scudo
        player.getInventory().setSelectedSlot(axeSlot);
        attack(client, player, target);
        dbg(player, "Ascia colpita, slot " + (axeSlot + 1));

        // 2) Attesa casuale tra HIT_DELAY_MIN e HIT_DELAY_MAX tick
        ticksLeft = ThreadLocalRandom.current().nextInt(HIT_DELAY_MIN, HIT_DELAY_MAX + 1);
        state = State.AXE_HIT;
    }

    private void attack(Minecraft client, LocalPlayer player, Entity entity) {
        client.gameMode.attack(player, entity);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private void dbg(LocalPlayer player, String msg) {
        if (DEBUG && player != null) {
            player.displayClientMessage(Component.literal("[StunSlam] " + msg), true);
        }
    }

    private void finish(LocalPlayer player) {
        if (player != null) {
            player.getInventory().setSelectedSlot(previousSlot);
        }
        reset();
    }

    private void reset() {
        state = State.IDLE;
        target = null;
        ticksLeft = 0;
        totalTicks = 0;
    }

    /** Cerca nella hotbar (slot 0-8) la mace (mace=true) oppure un'ascia (mace=false). */
    private int findSlot(LocalPlayer player, boolean mace) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (mace ? stack.is(Items.MACE) : stack.getItem() instanceof AxeItem) {
                return i;
            }
        }
        return -1;
    }
}
