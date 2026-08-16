package team.terrafirmagreg.appgate.config;

import java.util.List;

public record ResolvedApp(
        String appId,
        String jar,
        String mainClass,
        String xms,
        String xmx,
        List<String> jvmArgs,
        List<String> args,
        boolean restart,
        long restartDelayMs,
        long restartMaxDelayMs,
        int restartMaxAttempts,
        long restartResetAfterMs,
        Integer javaVersion
) {
    public static ResolvedApp merge(String appId, AppEntry defaults, AppEntry override) {
        String jar = override != null ? override.jar() : null;
        String mainClass = override != null ? override.mainClass() : null;
        if (override == null) {
            return new ResolvedApp(
                    appId,
                    jar,
                    mainClass,
                    defaults.xms(),
                    defaults.xmx(),
                    List.copyOf(defaults.jvmArgs()),
                    List.copyOf(defaults.args()),
                    Boolean.TRUE.equals(defaults.restart()),
                    defaults.restartDelayMs(),
                    defaults.restartMaxDelayMs(),
                    defaults.restartMaxAttempts(),
                    defaults.restartResetAfterMs(),
                    defaults.javaVersion()
            );
        }
        return new ResolvedApp(
                appId,
                jar,
                mainClass,
                firstNonBlank(override.xms(), defaults.xms()),
                firstNonBlank(override.xmx(), defaults.xmx()),
                override.jvmArgs() != null && !override.jvmArgs().isEmpty()
                        ? List.copyOf(override.jvmArgs())
                        : List.copyOf(defaults.jvmArgs()),
                override.args() != null && !override.args().isEmpty()
                        ? List.copyOf(override.args())
                        : List.copyOf(defaults.args()),
                override.restart() != null ? override.restart() : Boolean.TRUE.equals(defaults.restart()),
                override.restartDelayMs() != null ? override.restartDelayMs() : defaults.restartDelayMs(),
                override.restartMaxDelayMs() != null ? override.restartMaxDelayMs() : defaults.restartMaxDelayMs(),
                override.restartMaxAttempts() != null ? override.restartMaxAttempts() : defaults.restartMaxAttempts(),
                override.restartResetAfterMs() != null ? override.restartResetAfterMs() : defaults.restartResetAfterMs(),
                override.javaVersion() != null ? override.javaVersion() : defaults.javaVersion()
        );
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }
}
