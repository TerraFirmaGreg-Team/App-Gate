package team.terrafirmagreg.appgate.runtime;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JarInspector {
    private static final Pattern MULTI_RELEASE = Pattern.compile("^META-INF/versions/(\\d+)/");
    private static final int MAX_CLASS_SAMPLES = 400;

    private JarInspector() {
    }

    public record DiscoveredApp(
            String jarFileName,
            String mainClass,
            String shortName,
            Integer detectedJavaVersion
    ) {
    }

    public static Optional<DiscoveredApp> inspect(Path jarPath) throws IOException {
        String jarFileName = jarPath.getFileName().toString();
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            String mainClass = readMainClass(jarFile);
            if (mainClass == null || mainClass.isBlank()) {
                return Optional.empty();
            }
            mainClass = mainClass.trim().replace('/', '.');
            Integer javaVersion = detectRequiredJavaVersion(jarFile);
            return Optional.of(new DiscoveredApp(
                    jarFileName,
                    mainClass,
                    simpleName(mainClass),
                    javaVersion
            ));
        }
    }

    public static Integer detectRequiredJavaVersion(Path jarPath) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            return detectRequiredJavaVersion(jarFile);
        }
    }

    public static Integer detectRequiredJavaVersion(JarFile jarFile) throws IOException {
        int maxFeature = 8;
        int sampled = 0;
        Enumeration<JarEntry> entries = jarFile.entries();
        for (JarEntry entry : Collections.list(entries)) {
            String name = entry.getName();
            Matcher mr = MULTI_RELEASE.matcher(name);
            if (mr.find()) {
                try {
                    maxFeature = Math.max(maxFeature, Integer.parseInt(mr.group(1)));
                } catch (NumberFormatException _) {
                }
            }
            if (!name.endsWith(".class") || entry.isDirectory() || name.contains("module-info")) {
                continue;
            }
            if (sampled >= MAX_CLASS_SAMPLES) {
                continue;
            }
            try (InputStream in = jarFile.getInputStream(entry)) {
                int major = readClassMajor(in);
                if (major > 0) {
                    maxFeature = Math.max(maxFeature, classMajorToJavaFeature(major));
                    sampled++;
                }
            } catch (IOException _) {
            }
        }
        return JdkManager.minimumStandardFor(maxFeature);
    }

    public static String simpleName(String mainClass) {
        int idx = mainClass.lastIndexOf('.');
        return idx >= 0 ? mainClass.substring(idx + 1) : mainClass;
    }

    public static int classMajorToJavaFeature(int major) {
        if (major <= 52) {
            return 8;
        }
        return major - 44;
    }

    private static String readMainClass(JarFile jarFile) throws IOException {
        Manifest manifest = jarFile.getManifest();
        if (manifest == null) {
            return null;
        }
        return manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
    }

    private static int readClassMajor(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int magic = data.readInt();
        if (magic != 0xCAFEBABE) {
            return -1;
        }
        data.readUnsignedShort(); // minor
        return data.readUnsignedShort(); // major
    }
}
