/*
 * Copyright 2023 Alessandro Bellia
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bell;

import com.alexkasko.delta.DirDeltaCreator;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class P2PPlugin extends JavaPlugin {
    // private static BasicHttpClientConnectionManager connectionManager = null;

    @Override
    public void onEnable() {
        // Bukkit.getPluginManager().registerEvents(new PlayerJoinListener(), this);
        // Bukkit.getMessenger().registerOutgoingPluginChannel(this, "p2pplugin:defaultchannel");
        /*ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setSocketTimeout(Timeout.ofSeconds(200))
                .setConnectTimeout(Timeout.ofSeconds(200))
                .build();
        if (connectionManager == null) {
            connectionManager = new BasicHttpClientConnectionManager();
            connectionManager.setConnectionConfig(connectionConfig);
        }*/


        // Start the data synchronization task
        // Upload world if it doesn't exist in the fastapi server
        checkAndUploadWorld();
        DeltaCompletionListener deltaCompletionListener = new DeltaCompletionListener(URI.create("wss://p2pmc.fly.dev/ws"));
        deltaCompletionListener.connect();
        startDataAsyncTask(0);
    }

    private void checkAndUploadWorld() {
        File oldWorld = new File(Bukkit.getWorldContainer().getAbsoluteFile().getParentFile(), "world_old");
        File currentWorld = new File(Bukkit.getWorldContainer().getAbsoluteFile().getParentFile(), Objects.requireNonNull(Bukkit.getWorlds().get(0)).getName());

        // Check if world exists in fastapi server (https://p2pmc.fly.dev/world_exists) get request. Returns {world_exists: true/false}
        RestTemplate restTemplate = new RestTemplate();
        String url = "https://p2pmc.fly.dev/world_exists";
        ResponseEntity<WorldExistsDTO> response = restTemplate.getForEntity(url, WorldExistsDTO.class);
        WorldExistsDTO worldExistsDTO = response.getBody();
        if (worldExistsDTO == null) {
            throw new RuntimeException("WorldExistsDTO is null");
        }

        if (!worldExistsDTO.getWorldExists()) {

            // Upload world to fastapi server (https://p2pmc.fly.dev/upload_world) post request
            // Compress world folder
            File compressedWorldFolder = compressWorldFolder(currentWorld);

            // Send file to fastapi endpoint /upload_world via multi-part post request
            String uploadUrl = "https://p2pmc.fly.dev/upload_world";
            try {
                uploadCompressedWorldFolder(compressedWorldFolder, uploadUrl);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            // Copy world to world_prev
            try {
                FileFilter fileFilter = new IOFileFilter() {
                    @Override
                    public boolean accept(File file) {
                        return !file.getName().contains("session.lock");
                    }

                    @Override
                    public boolean accept(File dir, String name) {
                        return !name.contains("session.lock");
                    }
                };

                FileUtils.copyDirectory(currentWorld, oldWorld, fileFilter);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

    }

    private void startDataAsyncTask(long delaySeconds) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    sendServerDataToPeers();
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }.runTaskLaterAsynchronously(this, 30 * delaySeconds); // Run every 30 seconds (20 ticks per second)
    }


    private void sendServerDataToPeers() throws IOException, InterruptedException {
        sendKeepAlive();
        // Get world folder
        File worldFolder = Bukkit.getWorldContainer().getAbsoluteFile().getParentFile();
        worldFolder = new File(worldFolder, Objects.requireNonNull(Bukkit.getWorlds().get(0)).getName());
        Path oldWorldFolder = Files.createDirectories(Bukkit.getWorldContainer().getAbsoluteFile().getParentFile().toPath().resolve("world_old"));
        Path patchFilePath = Bukkit.getWorldContainer().getAbsoluteFile().getParentFile().toPath().resolve("patch.zip");

        DeltaCompletionListener.semaphore.acquire();

        // Save world and disable auto-save
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-off");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");
            future.complete(null);
        });

        // Wait for world to save
        future.join();

        // create a copy of "world" named "world_prev", replacing any existing "world_prev" folder
        FileFilter fileFilter = new IOFileFilter() {
            @Override
            public boolean accept(File file) {
                return !file.getName().contains("session.lock");
            }

            @Override
            public boolean accept(File dir, String name) {
                return !name.contains("session.lock");
            }
        };
        File prevWorld = new File(Bukkit.getWorldContainer().getAbsoluteFile().getParentFile(), "world_prev");
        prevWorld = new File(prevWorld, Objects.requireNonNull(Bukkit.getWorlds().get(0)).getName());
        if (prevWorld.getParentFile().exists()) {
            FileUtils.deleteDirectory(prevWorld.getParentFile());
        }

        FileUtils.copyDirectory(worldFolder, prevWorld, fileFilter, false);

        // Create delta patch from old world folder to current world folder
        createDeltaPatch(prevWorld, oldWorldFolder, patchFilePath);

        // Re-enable auto-save
        Bukkit.getScheduler().runTask(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-on"));

        boolean uploadWorld = false;
        if (uploadWorld) {

            // Compress world folder
            File compressedWorldFolder = compressWorldFolder(worldFolder);

            // Send file to fastapi endpoint /upload_world via multi-part post request
            String uploadUrl = "https://p2pmc.fly.dev/upload_world";
            try {
                uploadCompressedWorldFolder(compressedWorldFolder, uploadUrl);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        startDataAsyncTask(20);


        /*byte[] serializedWorldContainer = serializeWorldContainer(compressedWorldFolder);
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Send server data to each connected player
            sendDataToPeer(player, serializedWorldContainer);
        }*/
    }

    private void createDeltaPatch(File worldFolder, Path oldWorldFolder, Path patchFilePath) throws IOException {
        // Create delta patch from old world folder to current world folder
        File oldDir = new File(oldWorldFolder.toString());
        File newDir = worldFolder;
        File patchFile = new File(patchFilePath.toString());
        if (patchFile.exists()) {
            patchFile.delete();
        }

        // Create a filter to exclude the "session.lock" file
        IOFileFilter filesFilter = new IOFileFilter() {
            @Override
            public boolean accept(File file) {
                return !file.getName().contains("session.lock");
            }

            @Override
            public boolean accept(File file, String s) {
                return !s.contains("session.lock");
            }
        };

        // Create delta patch
        new DirDeltaCreator().create(oldDir, newDir, filesFilter, new FileOutputStream(patchFile));
        System.out.println("Created delta patch from old world folder to current world folder");

        // Upload delta patch
        String deltaUploadUrl = "https://p2pmc.fly.dev/upload_delta_world";
        uploadDeltaPatch(patchFile, deltaUploadUrl);
    }

    private void uploadDeltaPatch(File patchFile, String deltaUploadUrl) {
        System.out.println("Uploading delta patch to " + deltaUploadUrl);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(patchFile));
        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> response = restTemplate.postForEntity(deltaUploadUrl, requestEntity, String.class);
        System.out.println(response.getBody());
    }

    private void uploadCompressedWorldFolder(File compressedWorldFolder, String uploadUrl) throws IOException {
        System.out.println("Uploading compressed world folder to " + uploadUrl);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(compressedWorldFolder));
        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> response = restTemplate.postForEntity(uploadUrl, requestEntity, String.class);
        System.out.println(response.getBody());
    }

    private void sendKeepAlive() {
        RestTemplate restTemplate = new RestTemplate();
        String requestJson = "{\"port\": \"25565\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> requestEntity = new HttpEntity<>(requestJson, headers);
        ResponseEntity<String> response = restTemplate.postForEntity("https://p2pmc.fly.dev/add_peer", requestEntity, String.class);
        System.out.println(response.getBody());
    }

    private void zipFile(File file, String fileName, ZipOutputStream gzos) throws IOException {
        if (file.isHidden() || fileName.contains("session.lock")) {
            return;
        }
        if (file.isDirectory()) {
            if (fileName.endsWith("/")) {
                gzos.putNextEntry(new ZipEntry(fileName));
                gzos.closeEntry();
            } else {
                gzos.putNextEntry(new ZipEntry(fileName + "/"));
                gzos.closeEntry();
            }
            File[] children = file.listFiles();
            if (children != null) {
                for (File childFile : children) {
                    zipFile(childFile, fileName + "/" + childFile.getName(), gzos);
                }
            }
        } else {
            ZipEntry zipEntry = new ZipEntry(fileName);
            gzos.putNextEntry(zipEntry);
            Files.copy(file.toPath(), gzos);
            gzos.closeEntry();
        }
    }

    public File compressWorldFolder(File worldFolder) {
        File compressedFile = new File(worldFolder.getParentFile(), worldFolder.getName() + ".zip");
        if (compressedFile.exists()) {
            compressedFile.delete();
        }
        try (FileOutputStream fos = new FileOutputStream(compressedFile);
             ZipOutputStream zipOut = new ZipOutputStream(fos)) {
            zipFile(worldFolder, worldFolder.getName(), zipOut);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return compressedFile;
    }

    /*public byte[] serializeWorldContainer(File worldContainer) throws IOException {
        try (FileInputStream fis = new FileInputStream(worldContainer);
             FileChannel fileChannel = fis.getChannel()) {
            ByteBuffer buffer = ByteBuffer.allocate((int) fileChannel.size());
            fileChannel.read(buffer);
            buffer.flip();
            return buffer.array();
        }
    }*/

    /*private void sendDataToPeer(Player player, byte[] worldData) {
        player.sendPluginMessage(this, "p2pplugin:defaultchannel", worldData);
    }*/

}
