package team.terrafirmagreg.appgate.ui;

import team.terrafirmagreg.appgate.runtime.Supervisor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConsoleRouter {
    private final Supervisor supervisor;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public ConsoleRouter(Supervisor supervisor) {
        this.supervisor = supervisor;
    }

    public void runBlocking() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while (running.get() && !supervisor.isShuttingDown()) {
                line = reader.readLine();
                if (line == null) {
                    break;
                }
                handleLine(line);
            }
        }
    }

    public void stop() {
        running.set(false);
    }

    private void handleLine(String line) {
        String trimmed = line.trim();
        if ("/help".equalsIgnoreCase(trimmed) || "help".equalsIgnoreCase(trimmed)) {
            printHelp();
            return;
        }
        if (line.startsWith("!!")) {
            supervisor.writeToConsole(line.substring(1));
            return;
        }
        if (line.startsWith("!")) {
            handleCommand(line.substring(1).trim());
            return;
        }
        supervisor.writeToConsole(line);
    }

    private void handleCommand(String commandLine) {
        if (commandLine.isEmpty()) {
            return;
        }
        String[] parts = commandLine.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (command) {
            case "stop" -> {
                running.set(false);
                supervisor.shutdown();
                System.exit(0);
            }
            case "list" -> {
                List<String> status = supervisor.listStatus();
                if (status.isEmpty()) {
                    Log.info("No managed apps");
                } else {
                    for (String row : status) {
                        Log.info(row);
                    }
                }
            }
            case "attach" -> {
                if (arg.isEmpty()) {
                    Log.warn("Usage: !attach <name>");
                    return;
                }
                supervisor.attach(arg);
            }
            case "help" -> printHelp();
            default -> Log.warn("Unknown command: !" + command + " (try !help)");
        }
    }

    private void printHelp() {
        Log.info("Commands:");
        Log.info("  !attach <name>   route stdin to that app");
        Log.info("  !list            list managed processes");
        Log.info("  !stop            stop all apps and exit");
        Log.info("  !help / /help    show this help");
        Log.info("  !!text           send !text to the attached app");
    }
}
