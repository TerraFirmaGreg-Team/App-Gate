package team.terrafirmagreg.appgate.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class AppEntry {
    private String jar;
    private String mainClass;
    private String xms;
    private String xmx;
    private List<String> jvmArgs;
    private List<String> args;
    private Boolean restart;
    private Long restartDelayMs;
    private Long restartMaxDelayMs;
    private Integer restartMaxAttempts;
    private Long restartResetAfterMs;
    private Integer javaVersion;

    public String jar() {
        return jar;
    }

    public AppEntry jar(String jar) {
        this.jar = jar;
        return this;
    }

    public String mainClass() {
        return mainClass;
    }

    public AppEntry mainClass(String mainClass) {
        this.mainClass = mainClass;
        return this;
    }

    public String xms() {
        return xms;
    }

    public AppEntry xms(String xms) {
        this.xms = xms;
        return this;
    }

    public String xmx() {
        return xmx;
    }

    public AppEntry xmx(String xmx) {
        this.xmx = xmx;
        return this;
    }

    public List<String> jvmArgs() {
        return jvmArgs;
    }

    public AppEntry jvmArgs(List<String> jvmArgs) {
        this.jvmArgs = jvmArgs;
        return this;
    }

    public List<String> args() {
        return args;
    }

    public AppEntry args(List<String> args) {
        this.args = args;
        return this;
    }

    public Boolean restart() {
        return restart;
    }

    public AppEntry restart(Boolean restart) {
        this.restart = restart;
        return this;
    }

    public Long restartDelayMs() {
        return restartDelayMs;
    }

    public AppEntry restartDelayMs(Long restartDelayMs) {
        this.restartDelayMs = restartDelayMs;
        return this;
    }

    public Long restartMaxDelayMs() {
        return restartMaxDelayMs;
    }

    public AppEntry restartMaxDelayMs(Long restartMaxDelayMs) {
        this.restartMaxDelayMs = restartMaxDelayMs;
        return this;
    }

    public Integer restartMaxAttempts() {
        return restartMaxAttempts;
    }

    public AppEntry restartMaxAttempts(Integer restartMaxAttempts) {
        this.restartMaxAttempts = restartMaxAttempts;
        return this;
    }

    public Long restartResetAfterMs() {
        return restartResetAfterMs;
    }

    public AppEntry restartResetAfterMs(Long restartResetAfterMs) {
        this.restartResetAfterMs = restartResetAfterMs;
        return this;
    }

    public Integer javaVersion() {
        return javaVersion;
    }

    public AppEntry javaVersion(Integer javaVersion) {
        this.javaVersion = javaVersion;
        return this;
    }

    void normalize() {
        if (xms == null || xms.isBlank()) {
            xms = "64M";
        }
        if (xmx == null || xmx.isBlank()) {
            xmx = "512M";
        }
        if (jvmArgs == null) {
            jvmArgs = List.of();
        }
        if (args == null) {
            args = List.of();
        }
        if (restart == null) {
            restart = true;
        }
        if (restartDelayMs == null || restartDelayMs < 0) {
            restartDelayMs = 1000L;
        }
        if (restartMaxDelayMs == null || restartMaxDelayMs < restartDelayMs) {
            restartMaxDelayMs = 60000L;
        }
        if (restartMaxAttempts == null || restartMaxAttempts < 0) {
            restartMaxAttempts = 0;
        }
        if (restartResetAfterMs == null || restartResetAfterMs < 0) {
            restartResetAfterMs = 60000L;
        }
    }

    void normalizePartial() {
        if (jvmArgs == null) {
            jvmArgs = List.of();
        }
        if (args == null) {
            args = List.of();
        }
        if (jar != null && jar.isBlank()) {
            jar = null;
        }
        if (mainClass != null && mainClass.isBlank()) {
            mainClass = null;
        }
    }
}
