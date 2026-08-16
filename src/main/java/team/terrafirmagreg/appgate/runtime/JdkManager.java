package team.terrafirmagreg.appgate.runtime;

import team.terrafirmagreg.appgate.ui.Log;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public final class JdkManager {
    public static final Set<Integer> STANDARD_VERSIONS = Set.of(8, 17, 21, 25);
    private static final List<Integer> STANDARD_ORDER = List.of(8, 17, 21, 25);

    private final Path jdkDir;
    private final String eggJavaBinary;
    private final TemurinDownload download;

    public JdkManager(Path jdkDir) {
        this.jdkDir = jdkDir;
        this.eggJavaBinary = resolveEggJava();
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.download = new TemurinDownload(http);
    }

    public static int minimumStandardFor(int featureVersion) {
        for (int version : STANDARD_ORDER) {
            if (version >= featureVersion) {
                return version;
            }
        }
        return STANDARD_ORDER.getLast();
    }

    public Path jdkDir() {
        return jdkDir;
    }

    public void ensureVersions(Set<Integer> versions) throws IOException {
        Files.createDirectories(jdkDir);
        LinkedHashSet<Integer> ordered = new LinkedHashSet<>();
        for (int v : new int[]{8, 17, 21, 25}) {
            if (versions.contains(v)) {
                ordered.add(v);
            }
        }
        for (Integer version : ordered) {
            ensureVersion(version);
        }
    }

    public synchronized void ensureVersion(int version) throws IOException {
        if (!STANDARD_VERSIONS.contains(version)) {
            throw new IOException("Unsupported javaVersion " + version
                    + "; allowed: " + STANDARD_VERSIONS);
        }
        Path home = jdkDir.resolve(String.valueOf(version));
        Path javaBin = javaBinaryIn(home);
        if (Files.isRegularFile(javaBin)) {
            return;
        }

        Log.info("Downloading Temurin JDK " + version + " into " + home.toAbsolutePath());
        Path staging = jdkDir.resolve(".download-" + version);
        deleteRecursive(staging);
        Files.createDirectories(staging);

        boolean windows = isWindows();
        String archiveName = windows ? "jdk-" + version + ".zip" : "jdk-" + version + ".tar.gz";
        Path archive = staging.resolve(archiveName);
        download.fetch(version, archive);

        Path extractDir = staging.resolve("extract");
        Files.createDirectories(extractDir);
        if (windows) {
            TemurinDownload.unzip(archive, extractDir);
        } else {
            TemurinDownload.untarGz(archive, extractDir);
        }

        Path extractedHome = findJdkHome(extractDir);
        if (extractedHome == null) {
            throw new IOException("Could not locate JDK home after extracting " + archive);
        }

        deleteRecursive(home);
        Files.createDirectories(home.getParent());
        moveDirectory(extractedHome, home);

        javaBin = javaBinaryIn(home);
        if (!Files.isRegularFile(javaBin)) {
            throw new IOException("JDK " + version + " extract missing java at " + javaBin);
        }
        maybeMakeExecutable(javaBin);
        deleteRecursive(staging);
        Log.info("JDK " + version + " installed at " + home.toAbsolutePath());
    }

    public String resolveJavaBinary(Integer javaVersion) throws IOException {
        if (javaVersion == null) {
            return eggJavaBinary;
        }
        ensureVersion(javaVersion);
        Path javaBin = javaBinaryIn(jdkDir.resolve(String.valueOf(javaVersion)));
        if (!Files.isRegularFile(javaBin)) {
            throw new IOException("java binary not found for JDK " + javaVersion + ": " + javaBin);
        }
        maybeMakeExecutable(javaBin);
        return javaBin.toAbsolutePath().toString();
    }

    private static Path findJdkHome(Path extractDir) throws IOException {
        Path direct = javaBinaryIn(extractDir);
        if (Files.isRegularFile(direct)) {
            return extractDir;
        }
        try (Stream<Path> stream = Files.list(extractDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .filter(dir -> Files.isRegularFile(javaBinaryIn(dir)))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static Path javaBinaryIn(Path javaHome) {
        return javaHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target);
        } catch (IOException _) {
            Files.createDirectories(target);
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path rel = target.resolve(source.relativize(dir).toString());
                    Files.createDirectories(rel);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path rel = target.resolve(source.relativize(file).toString());
                    Files.createDirectories(rel.getParent());
                    Files.copy(file, rel, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
            deleteRecursive(source);
        }
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void maybeMakeExecutable(Path javaBin) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE
            );
            Files.setPosixFilePermissions(javaBin, perms);
        } catch (UnsupportedOperationException | IOException _) {
        }
    }

    private static String resolveEggJava() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path java = Path.of(javaHome, "bin", isWindows() ? "java.exe" : "java");
            if (Files.isRegularFile(java)) {
                return java.toAbsolutePath().toString();
            }
        }
        return "java";
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
