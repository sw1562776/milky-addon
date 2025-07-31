package com.milky;

import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.HttpsURLConnection;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownServiceException;

public class Utils
{

    // returns -1 if fails, 200 if successful, and slot of chestplate if it had to swap (needed for mio grimdura)
    public static int firework(MinecraftClient mc, boolean elytraRequired) {

        // cant use a rocket if not wearing an elytra
        int elytraSwapSlot = -1;
        if (elytraRequired && !mc.player.getInventory().getArmorStack(2).isOf(Items.ELYTRA))
        {
            FindItemResult itemResult = InvUtils.findInHotbar(Items.ELYTRA);
            if (!itemResult.found()) {
                return -1;
            }
            else
            {
                elytraSwapSlot = itemResult.slot();
                InvUtils.swap(itemResult.slot(), true);
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                InvUtils.swapBack();
                mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            }
        }

        FindItemResult itemResult = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!itemResult.found()) return -1;

        if (itemResult.isOffhand()) {
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
            mc.player.swingHand(Hand.OFF_HAND);
        } else {
            InvUtils.swap(itemResult.slot(), true);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
            InvUtils.swapBack();
        }
        if (elytraSwapSlot != -1)
        {
            return elytraSwapSlot;
        }
        return 200;
    }

    public static void setPressed(KeyBinding key, boolean pressed)
    {
        key.setPressed(pressed);
        Input.setKeyState(key, pressed);
    }

    public static int emptyInvSlots(MinecraftClient mc) {
        int airCount = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.AIR) {
                airCount++;
            }
        }
        return airCount;
    }

    /**
     * Returns the position in the direction of the yaw.
     * @param pos The starting position.
     * @param yaw The yaw in degrees.
     * @param distance The distance to move in the direction of the yaw.
     * @return The new position.
     */
    public static Vec3d positionInDirection(Vec3d pos, double yaw, double distance)
    {
        Vec3d offset = yawToDirection(yaw).multiply(distance);
        return pos.add(offset);
    }

    /**
     * Converts a yaw in degrees to a direction vector.
     * @param yaw The yaw in degrees.
     * @return The direction vector.
     */
    public static Vec3d yawToDirection(double yaw)
    {
        yaw = yaw * Math.PI / 180;
        double x = -Math.sin(yaw);
        double z = Math.cos(yaw);
        return new Vec3d(x, 0, z);
    }

    /**
     * Returns the distance from a point to a direction vector, not including the Y axis.
     * @param point The point to measure from.
     * @param direction The direction vector.
     * @param start The starting point of the direction vector, or null if the direction vector starts at (0, 0).
     * @return The distance from the point to the direction vector.
     */
    public static double distancePointToDirection(Vec3d point, Vec3d direction, @Nullable Vec3d start) {
        if (start == null) start = Vec3d.ZERO;

        point = point.multiply(new Vec3d(1, 0, 1));
        start = start.multiply(new Vec3d(1, 0, 1));
        direction = direction.multiply(new Vec3d(1, 0, 1));

        Vec3d directionVec = point.subtract(start);

        double projectionLength = directionVec.dotProduct(direction) / direction.lengthSquared();
        Vec3d projection = direction.multiply(projectionLength);
        Vec3d perp = directionVec.subtract(projection);
        return perp.length();
    }

    /**
     * Returns the angle rounded to the closest main 8 axis'.
     * @param yaw The yaw in degrees.
     * @return The angle on the axis.
     */
    public static double angleOnAxis(double yaw)
    {
        if (yaw < 0) yaw += 360;
        return Math.round(yaw / 45.0f) * 45;
    }

    public static Vec3d normalizedPositionOnAxis(Vec3d pos) {
        double angle = -Math.atan2(pos.x, pos.z);
        double angleDeg = Math.toDegrees(angle);

        return positionInDirection(new Vec3d(0,0,0), angleOnAxis(angleDeg), 1);
    }

    public static int totalInvCount(MinecraftClient mc, Item item) {
        if (mc.player == null) return 0;
        int itemCount = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) {
                itemCount += stack.getCount();
            }
        }
        return itemCount;
    }


    public static void sendWebhook(String webhookURL, String title, String message, String pingID, String playerName)
    {
        String json = "";
        json += "{\"embeds\": [{"
            + "\"title\": \""+ title +"\","
            + "\"description\": \""+ message +"\","
            + "\"color\": 15258703,"
            + "\"footer\": {"
            + "\"text\": \"From: " + playerName + "\"}"
            + "}]}";
        sendRequest(webhookURL, json);

        if (pingID != null)
        {
            json = "{\"content\": \"<@" + pingID + ">\"}";
            sendRequest(webhookURL, json);
        }
    }

    public static void sendWebhook(String webhookURL, String jsonObject, String pingID)
    {
        sendRequest(webhookURL, jsonObject);

        if (pingID != null)
        {
            jsonObject = "{\"content\": \"<@" + pingID + ">\"}";
            sendRequest(webhookURL, jsonObject);
        }
    }

    private static void sendRequest(String webhookURL, String json) {
        try {
            URL url = new URL(webhookURL);
            HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
            con.addRequestProperty("Content-Type", "application/json");
            con.addRequestProperty("User-Agent", "Mozilla");
            con.setDoOutput(true);
            con.setRequestMethod("POST");
            OutputStream stream = con.getOutputStream();
            stream.write(json.getBytes());
            stream.flush();
            stream.close();
            con.getInputStream().close();
            con.disconnect();
        }
        catch (MalformedURLException | UnknownServiceException e)
        {
//            searchArea.logToWebhook.set(false);
//            searchArea.webhookLink.set("");
//            info("Issue with webhook link. It has been cleared, try again.");
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}
