package org.patchwork;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class Patcher {

    private static final String MANIFEST_NAME = "patch-manifest.txt";
    private static final String DELETE_PREFIX = "DELETE:";
    private static final String DIFF_EXTENSION = ".line-diff";

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            return;
        }
        String command = args[0];
        try {
            switch (command.toLowerCase()) {
                case "create":
                    createPatch(Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]));
                    break;
                case "apply":
                    applyPatch(Paths.get(args[1]), Paths.get(args[2]));
                    break;
                default:
                    printUsage();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void printUsage() {
        System.out.println("Usage:\n  create <old_dir> <new_dir> <patch.zip>\n  apply <target_dir> <patch.zip>");
    }

    public static void createPatch(Path originalDir, Path modifiedDir, Path patchFile) throws IOException {
        Map<String, String> originalFiles = scanDirectory(originalDir);
        Map<String, String> modifiedFiles = scanDirectory(modifiedDir);

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(patchFile)))) {
            // 1. Handle Deletions
            List<String> toDelete = originalFiles.keySet().stream()
                    .filter(path -> !modifiedFiles.containsKey(path))
                    .collect(Collectors.toList());

            if (!toDelete.isEmpty()) {
                zos.putNextEntry(new ZipEntry(MANIFEST_NAME));
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(zos, StandardCharsets.UTF_8));
                toDelete.forEach(path -> writer.println(DELETE_PREFIX + path));
                writer.flush();
                zos.closeEntry();
            }

            // 2. Handle Additions and Modifications
            for (String path : modifiedFiles.keySet()) {
                Path newPath = modifiedDir.resolve(path);
                if (!originalFiles.containsKey(path)) {
                    // New file: Store whole
                    addFileToZip(zos, path, newPath);
                } else if (!originalFiles.get(path).equals(modifiedFiles.get(path))) {
                    // Modified file
                    Path oldPath = originalDir.resolve(path);
                    if (isBinaryFile(newPath) || isBinaryFile(oldPath)) {
                        addFileToZip(zos, path, newPath); // Binary: Store whole
                    } else {
                        // Text: Store line-diff
                        List<String> diff = generateDiff(oldPath, newPath);
                        zos.putNextEntry(new ZipEntry(path + DIFF_EXTENSION));
                        PrintWriter writer = new PrintWriter(new OutputStreamWriter(zos, StandardCharsets.UTF_8));
                        diff.forEach(writer::println);
                        writer.flush();
                        zos.closeEntry();
                    }
                }
            }
        }
        System.out.println("Patch created at " + patchFile);
    }

    public static void applyPatch(Path targetDir, Path patchFile) throws IOException {
        List<String> filesToDelete = new ArrayList<>();

        // Phase 1: Scan for deletions and process whole-file replacements
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(patchFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals(MANIFEST_NAME)) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith(DELETE_PREFIX)) filesToDelete.add(line.substring(DELETE_PREFIX.length()));
                    }
                } else if (!name.endsWith(DIFF_EXTENSION)) {
                    // Regular file replacement
                    Path dest = targetDir.resolve(name);
                    Files.createDirectories(dest.getParent());
                    Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }

        // Phase 2: Process line-level diffs
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(patchFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith(DIFF_EXTENSION)) {
                    String originalName = entry.getName().substring(0, entry.getName().length() - DIFF_EXTENSION.length());
                    Path targetPath = targetDir.resolve(originalName);
                    
                    List<String> diffLines = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8))
                            .lines().collect(Collectors.toList());
                    
                    applyDiff(targetPath, diffLines);
                }
                zis.closeEntry();
            }
        }

        // Phase 3: Execute deletions
        for (String rel : filesToDelete) {
            Files.deleteIfExists(targetDir.resolve(rel));
        }
        System.out.println("Patch applied successfully.");
    }

    // --- Helper Methods ---

    private static boolean isBinaryFile(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read = is.read(buffer);
            for (int i = 0; i < read; i++) {
                if (buffer[i] == 0) return true; // NUL byte check
            }
        }
        return false;
    }

    private static void addFileToZip(ZipOutputStream zos, String entryName, Path file) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, zos);
        zos.closeEntry();
    }

    /**
     * A simple "Edit Script" diff generator. 
     * In a real-world scenario, you'd use the Myers algorithm.
     */
    private static List<String> generateDiff(Path oldP, Path newP) throws IOException {
        List<String> oldLines = Files.readAllLines(oldP, StandardCharsets.UTF_8);
        List<String> newLines = Files.readAllLines(newP, StandardCharsets.UTF_8);
        List<String> diff = new ArrayList<>();

        // Simplified diff: Just marks what changed per line index
        // This is a basic "replacement" diff for demonstration.
        int max = Math.max(oldLines.size(), newLines.size());
        for (int i = 0; i < max; i++) {
            String o = i < oldLines.size() ? oldLines.get(i) : null;
            String n = i < newLines.size() ? newLines.get(i) : null;

            if (Objects.equals(o, n)) {
                diff.add(" " + o); // Unchanged
            } else {
                if (o != null) diff.add("-" + o); // Delete
                if (n != null) diff.add("+" + n); // Add
            }
        }
        return diff;
    }

    private static void applyDiff(Path target, List<String> diff) throws IOException {
        List<String> result = new ArrayList<>();
        // Note: This logic follows the simplified generateDiff above
        for (String line : diff) {
            if (line.startsWith(" ") || line.startsWith("+")) {
                result.add(line.substring(1));
            }
            // Skip "-" lines
        }
        Files.write(target, result, StandardCharsets.UTF_8);
    }

    private static Map<String, String> scanDirectory(Path rootDir) throws IOException {
        Map<String, String> fileHashes = new HashMap<>();
        if (!Files.exists(rootDir)) return fileHashes;
        Files.walk(rootDir).filter(Files::isRegularFile).forEach(file -> {
            try {
                fileHashes.put(rootDir.relativize(file).toString(), getFileSha256(file));
            } catch (IOException e) { e.printStackTrace(); }
        });
        return fileHashes;
    }

    private static String getFileSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(file);
                 DigestInputStream dis = new DigestInputStream(is, digest)) {
                byte[] buffer = new byte[8192];
                while (dis.read(buffer) != -1);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
