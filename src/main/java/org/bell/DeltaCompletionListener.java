package org.bell;

import org.apache.commons.io.FileUtils;
import org.bukkit.Bukkit;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.Semaphore;


public class DeltaCompletionListener extends WebSocketClient {
    public static final Semaphore semaphore = new Semaphore(1);

    public DeltaCompletionListener(URI serverUri) {
        super(serverUri);
    }

    public DeltaCompletionListener(URI serverUri, Draft draft) {
        super(serverUri, draft);
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        System.out.println("Connected to server");
    }

    @Override
    public void onMessage(String s) {
        JSONObject json = new JSONObject(s);
        String hash = json.getString("hash");
        if (!hash.isEmpty()) {
            File oldWorld = new File(Bukkit.getWorldContainer().getAbsoluteFile().getParentFile(), "world_old");
            File prevWorld = new File(Bukkit.getWorldContainer().getAbsoluteFile().getParentFile(), "world_prev");
            prevWorld = new File(prevWorld, Objects.requireNonNull(Bukkit.getWorlds().get(0)).getName());

            if (oldWorld.exists()) {
                try {
                    FileUtils.deleteDirectory(oldWorld);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            // copy world_prev to world_old
            try {
                FileUtils.copyDirectory(prevWorld, oldWorld);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            semaphore.release();
        }
    }

    @Override
    public void onClose(int i, String s, boolean b) {
        System.out.println("Disconnected from server");
    }

    @Override
    public void onError(Exception e) {
        System.err.println("An error occurred: " + e);
    }
}
