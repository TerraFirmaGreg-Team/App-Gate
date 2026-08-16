package team.terrafirmagreg.appgate.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class TemurinDownload {
    private final HttpClient http;

    TemurinDownload(HttpClient http) {
        this.http = http;
    }

    void fetch(int version, Path target) throws IOException {
        String os = detectOs();
        String arch = detectArch();
        String url = "https://api.adoptium.net/v3/binary/latest/"
                + version
                + "/ga/"
                + os
                + "/"
                + arch
                + "/jdk/hotspot/normal/eclipse?project=jdk";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "AppGate")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Adoptium download failed HTTP " + response.statusCode() + " for JDK " + version);
            }
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target)) {
                in.transferTo(out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted for JDK " + version, e);
        }
        if (!Files.isRegularFile(target) || Files.size(target) < 1_000_000) {
            throw new IOException("Downloaded JDK archive looks too small: " + target);
        }
    }

    static void unzip(Path zip, Path dest) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = dest.resolve(entry.getName()).normalize();
                if (!out.startsWith(dest)) {
                    throw new IOException("Zip slip rejected: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static void untarGz(Path archive, Path dest) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "tar",
                "-xzf",
                archive.toAbsolutePath().toString(),
                "-C",
                dest.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes());
        }
        try {
            int code = process.waitFor();
            if (code != 0) {
                throw new IOException("tar failed (" + code + "): " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tar interrupted", e);
        }
    }

    private static String detectOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "mac";
        }
        return "linux";
    }

    private static String detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "aarch64";
            case "x86", "i386", "i686" -> "x86";
            default -> "x64";
        };
    }
}
