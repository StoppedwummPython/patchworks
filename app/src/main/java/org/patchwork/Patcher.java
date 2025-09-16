package org.patchwork;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A command-line tool to create and apply code patches from zip files.
 *
 * <h3>Usage:</h3>
 * <p>
 * <b>To compile:</b><br>
 * {@code javac Patcher.java}
 * </p>
 * <p>
 * <b>To create a patch:</b><br>
 * {@code java Patcher create <original_dir> <modified_dir> <patch_output.zip>}
 * </p>
 * <p>
 * <b>To apply a patch:</b><br>
 * {@code java Patcher apply <target_dir> <patch_to_apply.zip>}
 * </p>
 */
public class Patcher {

    private static final String MANIFEST_NAME = "patch-manifest.txt";
    private static final String DELETE_PREFIX = "DELETE:";

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            return;
        }

        String command = args[0];

        try {
            switch (command.toLowerCase()) {
                case "create":
                    if (args.length != 4) {
                        System.err.println("Error: 'create' command requires 3 arguments.");
                        printUsage();
                        return;
                    }
                    Path originalDir = Paths.get(args[1]);
                    Path modifiedDir = Paths.get(args[2]);
                    Path patchFile = Paths.get(args[3]);
                    createPatch(originalDir, modifiedDir, patchFile);
                    break;
                case "apply":
                    if (args.length != 3) {
                        System.err.println("Error: 'apply' command requires 2 arguments.");
                        printUsage();
                        return;
                    }
                    Path targetDir = Paths.get(args[1]);
                    Path patchToApply = Paths.get(args[2]);
                    applyPatch(targetDir, patchToApply);
                    break;
                default:
                    System.err.println("Error: Unknown command '" + command + "'");
                    printUsage();
            }
        } catch (IOException e) {
            System.err.println("An I/O error occurred: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("An unexpected error occurred: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printUsage() {
        System.out.println("--- Java Code Patcher ---");
        System.out.println("A tool to create and apply patches from zip files.");
        System.out.println("\nUsage:");
        System.out.println("  java Patcher create <original_dir> <modified_dir> <patch_output.zip>");
        System.out.println("    - Compares original_dir and modified_dir to create a patch file.");
        System.out.println("\n  java Patcher apply <target_dir> <patch_to_apply.zip>");
        System.out.println("    - Applies the patch to the target_dir.");
    }

    public static void createPatch(Path originalDir, Path modifiedDir, Path patchFile) throws IOException {
        System.out.println("Creating patch...");
        System.out.println("  Original: " + originalDir.toAbsolutePath());
        System.out.println("  Modified: " + modifiedDir.toAbsolutePath());
        System.out.println("  Output:   " + patchFile.toAbsolutePath());

        if (!Files.isDirectory(originalDir) || !Files.isDirectory(modifiedDir)) {
            throw new IllegalArgumentException("Original and modified paths must be directories.");
        }

        Map<String, String> originalFiles = scanDirectory(originalDir);
        Map<String, String> modifiedFiles = scanDirectory(modifiedDir);

        List<String> toAddOrUpdate = new ArrayList<>();
        List<String> toDelete = new ArrayList<>();

        for (Map.Entry<String, String> entry : modifiedFiles.entrySet()) {
            String path = entry.getKey();
            String hash = entry.getValue();
            if (!originalFiles.containsKey(path) || !originalFiles.get(path).equals(hash)) {
                toAddOrUpdate.add(path);
            }
        }

        for (String path : originalFiles.keySet()) {
            if (!modifiedFiles.containsKey(path)) {
                toDelete.add(path);
            }
        }

        if (toAddOrUpdate.isEmpty() && toDelete.isEmpty()) {
            System.out.println("No changes detected. Patch file not created.");
            return;
        }

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(patchFile)))) {
            if (!toDelete.isEmpty()) {
                System.out.println("\nFiles to be DELETED in patch:");
                ZipEntry manifestEntry = new ZipEntry(MANIFEST_NAME);
                zos.putNextEntry(manifestEntry);
                
                // ===================================================================
                // START OF FIX
                // ===================================================================
                // Do NOT use try-with-resources on the writer, as it will close the underlying zos.
                // The outer try-with-resources on zos will handle closing everything correctly.
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(zos));
                for (String path : toDelete) {
                    System.out.println("  - " + path);
                    writer.println(DELETE_PREFIX + path);
                }
                writer.flush(); // Crucially, flush the writer to push content to the stream.
                // ===================================================================
                // END OF FIX
                // ===================================================================

                zos.closeEntry();
            }

            if (!toAddOrUpdate.isEmpty()) {
                System.out.println("\nFiles to be ADDED/UPDATED in patch:");
                for (String relativePath : toAddOrUpdate) {
                    System.out.println("  - " + relativePath);
                    Path filePath = modifiedDir.resolve(relativePath);
                    ZipEntry fileEntry = new ZipEntry(relativePath.replace(File.separator, "/"));
                    zos.putNextEntry(fileEntry);
                    Files.copy(filePath, zos);
                    zos.closeEntry();
                }
            }
        }

        System.out.println("\nPatch created successfully!");
    }

    private static Map<String, String> scanDirectory(Path rootDir) throws IOException {
        Map<String, String> fileHashes = new HashMap<>();
        if (!Files.exists(rootDir)) return fileHashes;
        List<Path> files = Files.walk(rootDir)
                .filter(Files::isRegularFile)
                .collect(Collectors.toList());

        for (Path file : files) {
            String relativePath = rootDir.relativize(file).toString();
            String hash = getFileSha256(file);
            fileHashes.put(relativePath, hash);
        }
        return fileHashes;
    }

    private static String getFileSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(file);
                 DigestInputStream dis = new DigestInputStream(is, digest)) {
                byte[] buffer = new byte[8192];
                while (dis.read(buffer) != -1) ;
            }
            byte[] hashBytes = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("Could not find SHA-256 algorithm", e);
        }
    }

    public static void applyPatch(Path targetDir, Path patchFile) throws IOException {
        System.out.println("Applying patch...");
        System.out.println("  Target: " + targetDir.toAbsolutePath());
        System.out.println("  Patch:  " + patchFile.toAbsolutePath());

        if (!Files.isDirectory(targetDir)) {
            System.out.println("Target directory does not exist. It will be created.");
            Files.createDirectories(targetDir);
        }

        if (!Files.isRegularFile(patchFile)) {
            throw new FileNotFoundException("Patch file not found: " + patchFile);
        }

        List<String> filesToDelete = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(patchFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(MANIFEST_NAME)) {
                    System.out.println("\nProcessing deletions from manifest...");
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(zis))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith(DELETE_PREFIX)) {
                                String relativePath = line.substring(DELETE_PREFIX.length());
                                filesToDelete.add(relativePath);
                            }
                        }
                    }
                    break;
                }
            }
        }

        for (String relativePath : filesToDelete) {
            Path fileToDelete = targetDir.resolve(relativePath);
            System.out.println("  - Deleting: " + fileToDelete);
            Files.deleteIfExists(fileToDelete);
        }

        for (String relativePath : filesToDelete) {
            Path parent = targetDir.resolve(relativePath).getParent();
            if (parent != null && Files.isDirectory(parent)) {
                try {
                    Files.delete(parent);
                    System.out.println("  - Removed empty directory: " + parent);
                } catch (DirectoryNotEmptyException e) {
                    // Ignore, directory is not empty
                }
            }
        }

        System.out.println("\nProcessing additions/updates...");
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(patchFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(MANIFEST_NAME) || entry.isDirectory()) {
                    continue;
                }

                Path destinationFile = targetDir.resolve(entry.getName()).normalize();
                
                if (!destinationFile.startsWith(targetDir.normalize())) {
                    System.err.println("  - WARNING: Skipping potentially malicious file outside target dir: " + entry.getName());
                    continue;
                }
                
                System.out.println("  - Extracting: " + destinationFile);

                Path parentDir = destinationFile.getParent();
                if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                }

                Files.copy(zis, destinationFile, StandardCopyOption.REPLACE_EXISTING);
                zis.closeEntry();
            }
        }
        System.out.println("\nPatch applied successfully!");
    }
}