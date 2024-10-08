/*
 * Copyright (c) 2022-2023 Amberichu (davchoo).
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author Amberichu
 */

package me.amberichu.headextractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.steveice10.opennbt.NBTIO;
import com.github.steveice10.opennbt.tag.builtin.CompoundTag;
import com.github.steveice10.opennbt.tag.builtin.ListTag;
import com.github.steveice10.opennbt.tag.builtin.StringTag;
import com.github.steveice10.opennbt.tag.builtin.Tag;

import java.io.*;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Head Extractor is a tool and library to extract the player profile from the player heads in a Minecraft world.
 * <p>
 * This is accomplished somewhat inefficiently by searching chunk NBT, player data NBT, and entity NBT for lists of
 * Compound tags that contain a String tag named Value.
 * In addition, mcfunction and json files in data packs are scanned for Base64 encoded player profiles.
 */
public class HeadExtractor {

    private static final String USAGE = """
            HeadExtractor by Amberichu
            https://github.com/davchoo/HeadExtractor
            
            Head Extractor is a tool to extract the player profile from the player heads in a Minecraft world.
            
            Usage: java -jar HeadExtractor-<VERSION>-all.jar [OPTIONS] <WORLD DIRECTORIES>
            
            Options:
            --exclude-entities:    Exclude heads carried by entities
            --exclude-region:      Exclude heads placed in the world and in containers
            --exclude-playerdata:  Exclude heads in players' inventories
            --exclude-datapacks:   Exclude base64-encoded player profiles in .json or .mcfunction files in datapacks
            There is also a corresponding --include option for each of the above.
            The default behavior is to include all heads.""";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Adapted from https://stackoverflow.com/a/475217
    private static final Pattern BASE64_PATTERN = Pattern.compile("\\\\?[\"']((?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=))\\\\?[\"']");

    public static void main(String[] args) throws IOException {
        Set<Path> worldPaths = new HashSet<>();
        boolean includeEntities = true;
        boolean includeRegion = true;
        boolean includePlayerData = true;
        boolean includeDataPacks = true;

        for (String arg : args) {
            if (arg.startsWith("--")) {
                switch (arg) {
                    case "--include-entities" -> includeEntities = true;
                    case "--include-region" -> includeRegion = true;
                    case "--include-playerdata" -> includePlayerData = true;
                    case "--include-datapacks" -> includeDataPacks = true;
                    case "--exclude-entities" -> includeEntities = false;
                    case "--exclude-region" -> includeRegion = false;
                    case "--exclude-playerdata" -> includePlayerData = false;
                    case "--exclude-datapacks" -> includeDataPacks = false;
                    case "--help" -> {
                        System.out.println(USAGE);
                        return;
                    }
                    default -> {
                        System.err.println("Unknown option " + arg + ", use --help for help.");
                        System.exit(1);
                    }
                }
            } else {
                try {
                    Path worldPath = Path.of(arg);

                    if (!Files.isDirectory(worldPath)) {
                        System.err.println("World path " + arg + " does not exist, use --help for help.");
                        System.exit(1);
                    }

                    worldPaths.add(worldPath);
                } catch (InvalidPathException e) {
                    System.err.println("Invalid world path " + arg + ", use --help for help.");
                    System.exit(1);
                }
            }
        }

        if (worldPaths.isEmpty()) {
            System.out.println(USAGE);
            return;
        }

        Set<String> heads = extractHeads(worldPaths, includeEntities, includeRegion, includePlayerData, includeDataPacks);
        heads.forEach(System.out::println);
    }

    /**
     * Extract player head textures from worlds
     * @param worldPaths Paths to the worlds to scan
     * @param includeEntities Whether to scan heads carried by non-player entities
     * @param includeRegion Whether to scan heads placed in the world or in containers
     * @param includePlayerData Whether to scan heads carried by players
     * @param includeDataPacks Whether to scan .json and .mcfunction files in datapacks
     * @return A set of the base64-encoded player profiles in the given worlds
     * @throws IOException If an I/O error occurs
     */
    public static Set<String> extractHeads(Set<Path> worldPaths, boolean includeEntities, boolean includeRegion,
                                            boolean includePlayerData, boolean includeDataPacks) throws IOException {
        Set<String> heads = ConcurrentHashMap.newKeySet();
        if (!(includeEntities || includeRegion || includePlayerData || includeDataPacks)) return heads;

        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() - 1);
        List<CompletableFuture<?>> tasks = new ArrayList<>();

        Consumer<String> headConsumer = head -> {
            if (validateHead(head)) {
                heads.add(head);
            }
        };

        for (Path worldPath : worldPaths) {
            if (includeEntities || includeRegion) {
                for (Path path : gatherMCA(worldPath, includeEntities, includeRegion)) {
                    tasks.add(CompletableFuture.runAsync(() -> processMCA(path, headConsumer), executor));
                }
            }
            if (includePlayerData) {
                for (Path path : gatherPlayerData(worldPath)) {
                    tasks.add(CompletableFuture.runAsync(() -> processDAT(path, headConsumer), executor));
                }
            }
            if (includeDataPacks) gatherFromDataPacks(worldPath, headConsumer);
        }

        // Wait for all tasks to be complete
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        // Shut down the executor service to ensure the threads are killed
        executor.shutdownNow();

        return heads;
    }

    private static List<Path> gatherMCA(Path worldPath, boolean includeEntities, boolean includeRegion)
            throws IOException {
        Path entitiesPath = worldPath.resolve("entities");
        Path regionPath = worldPath.resolve("region");

        List<Path> mcaPaths = new ArrayList<>();
        if (includeEntities && Files.isDirectory(entitiesPath)) {
            try (Stream<Path> stream = Files.list(entitiesPath)) {
                stream.forEach(mcaPaths::add);
            }
        }
        if (includeRegion && Files.isDirectory(regionPath)) {
            try (Stream<Path> stream = Files.list(regionPath)) {
                stream.forEach(mcaPaths::add);
            }
        }
        mcaPaths.removeIf(path -> !Files.isRegularFile(path) || !path.getFileName().toString().endsWith("mca"));
        return mcaPaths;
    }

    private static List<Path> gatherPlayerData(Path worldPath) throws IOException {
        Path playerDataPath = worldPath.resolve("playerdata");
        Path levelDataPath = worldPath.resolve("level.dat");

        List<Path> dataPaths = new ArrayList<>();
        if (Files.isDirectory(playerDataPath)) {
            try (Stream<Path> stream = Files.list(playerDataPath)) {
                stream.forEach(dataPaths::add);
            }
        }
        dataPaths.add(levelDataPath);
        dataPaths.removeIf(path -> !Files.isRegularFile(path) || !path.getFileName().toString().endsWith("dat"));
        return dataPaths;
    }

    private static void gatherFromDataPacks(Path worldPath, Consumer<String> headConsumer) throws IOException {
        Path dataPacksPath = worldPath.resolve("datapacks");
        if (!Files.isDirectory(dataPacksPath)) {
            return;
        }

        try (Stream<Path> stream = Files.list(dataPacksPath)) {
            for (Path dataPackPath : stream.toList()) {
                if (Files.isDirectory(dataPackPath)) {
                    processDataPack(dataPackPath, headConsumer);
                } else if (Files.isRegularFile(dataPackPath) && dataPackPath.getFileName().toString().endsWith("zip")) {
                    try (FileSystem fileSystem = FileSystems.newFileSystem(dataPackPath, Collections.emptyMap())) {
                        fileSystem.getRootDirectories().forEach(path -> processDataPack(path, headConsumer));
                    }
                }
            }
        }
    }

    private static void processDataPack(Path dataPack, Consumer<String> headConsumer) {
        try (Stream<Path> stream = Files.walk(dataPack)) {
            for (Path path : stream.toList()) {
                if (Files.isRegularFile(path)) {
                    String filename = path.getFileName().toString();
                    if (filename.endsWith("json") || filename.endsWith("mcfunction")) {
                        try {
                            processString(Files.readString(path), headConsumer);
                        } catch (IOException e) {
                            System.err.println("Unable to read " + path + " due to exception: " + e);
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Unable to fully process " + dataPack + " due to exception: " + e);
        }
    }

    private static void processDAT(Path datPath, Consumer<String> headConsumer) {
        try {
            processTag(NBTIO.readFile(datPath.toFile()), headConsumer);
        } catch (IOException e) {
            System.err.println("Unable to fully process " + datPath + " due to exception: " + e);
        }
    }

    private static void processMCA(Path mcaPath, Consumer<String> headConsumer) {
        try (FileChannel channel = FileChannel.open(mcaPath, StandardOpenOption.READ)) {
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            buffer.order(ByteOrder.BIG_ENDIAN);
            for (int i = 0; i < 1024; i++) {
                int location = buffer.getInt(4 * i);
                if (location == 0) {
                    // Chunk is not present
                    continue;
                }

                int offset = ((location >> 8) & 0xFFFFFF) * 4096;
                int length = buffer.getInt(offset) - 1;
                byte compressionType = buffer.get(offset + 4);

                byte[] payload = new byte[length];
                buffer.get(offset + 5, payload);

                InputStream inputStream = new ByteArrayInputStream(payload);
                if (compressionType == 1) {
                    inputStream = new GZIPInputStream(inputStream);
                } else if (compressionType == 2) {
                    inputStream = new InflaterInputStream(inputStream);
                }
                inputStream = new BufferedInputStream(inputStream);
                processTag(NBTIO.readTag(inputStream), headConsumer);
            }
        } catch (IOException e) {
            System.err.println("Unable to fully process " + mcaPath + " due to exception: " + e);
        }
    }

    private static void processTag(Tag rootTag, Consumer<String> headConsumer) {
        Deque<Tag> tags = new ArrayDeque<>();
        tags.add(rootTag);
        while (!tags.isEmpty()) {
            Tag tag = tags.pop();
            if (tag instanceof CompoundTag compoundTag) {
                tags.addAll(compoundTag.values());
            } else if (tag instanceof ListTag listTag) {
                Class<?> elementType = listTag.getElementType();
                if (elementType != StringTag.class && elementType != ListTag.class && elementType != CompoundTag.class) {
                    // The ListTag can't store player profiles
                    continue;
                }
                if (listTag.getName().equals("textures")) { // Pre-1.20.5 item component rework
                    if (listTag.size() != 0 && listTag.get(0) instanceof CompoundTag texture) {
                        if (texture.get("Value") instanceof StringTag valueTag) {
                            headConsumer.accept(valueTag.getValue());
                        }
                    }
                } else if (listTag.getName().equals("properties")) { // Item component storage system
                    if (listTag.size() != 0 && listTag.get(0) instanceof CompoundTag texture) {
                        if (texture.get("name") instanceof StringTag nameTag &&
                                texture.get("value") instanceof StringTag valueTag) {
                            if (nameTag.getValue().equals("textures")) headConsumer.accept(valueTag.getValue());
                        }
                    }
                } else { // Scan children of this ListTag
                    listTag.forEach(tags::addLast);
                }
            } else if (tag instanceof StringTag stringTag) {
                processString(stringTag.getValue(), headConsumer);
            }
        }
    }

    private static void processString(String string, Consumer<String> headConsumer) {
        Matcher m = BASE64_PATTERN.matcher(string);
        while (m.find()) {
            headConsumer.accept(m.group(1));
        }
    }

    private static boolean validateHead(String head) {
        try {
            JsonNode node = MAPPER.readTree(Base64.getDecoder().decode(head));
            if (!node.isObject()) {
                return false;
            }

            JsonNode textures = node.get("textures");
            if (textures == null || !textures.isObject()) {
                return false;
            }

            JsonNode skin = textures.get("SKIN");
            if (skin == null || !textures.isObject()) {
                return false;
            }

            JsonNode url = skin.get("url");
            return url != null && url.isTextual();
        } catch (Exception e) {
            return false;
        }
    }
}
