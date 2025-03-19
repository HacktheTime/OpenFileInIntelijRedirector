package de.hype;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.swing.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main {
    String exampleClass = "de.hype.bbsentials.server.discord.events.staticimplementations.commands.commands.commandgroups.bingoeventcommandgroup.Top100";
    String exampleQuery = "http://localhost:9090/open?project=BBsentials-Server&class=de.hype.bbsentials.server.discord.events.staticimplementations.commands.commands.commandgroups.bingoeventcommandgroup.Top100";

    public Main() throws IOException {
    }

    public static void main(String[] args) throws IOException {
        killPrevious();
        String tools = checkRequiredTools();
        addAutostartEntry();
        setDefaultHttpHandler();
        if (tools != null) {
            System.err.println(tools);
            System.exit(1);
            return;
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(9090), 0);
        System.out.println("Hype Intellij Server started on port 9090");
        server.createContext("/open", new OpenHandler());
        server.setExecutor(null);
        server.start();
    }

    private static void killPrevious() {
        String processName = "HypeIntelliJServer";
        long currentPid = ProcessHandle.current().pid();

        // Kill any existing processes with the same name, excluding the current process
        try {
            Process searchProcess = new ProcessBuilder("bash", "-c", "pgrep -f " + processName).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(searchProcess.getInputStream()));
            String pid;
            while ((pid = reader.readLine()) != null) {
                if (!pid.equals(String.valueOf(currentPid))) {
                    new ProcessBuilder("bash", "-c", "kill -9 " + pid).start();
                    System.out.println("Terminated existing instance with PID: " + pid);
                }
            }
            searchProcess.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void focusIntelliJWindow(String projectName, String redirectUrl) {
        try {
            if (!isValidProjectName(projectName) || !isValidUrl(redirectUrl)) {
                throw new IllegalArgumentException("Invalid project name or URL");
            }

            // Find the target IntelliJ window
            String targetWindowId = null;
            Process wmctrlProcess = new ProcessBuilder("bash", "-c", "wmctrl -l").start();
            List<String> windowLines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(wmctrlProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    windowLines.add(line);
                }
            }
            wmctrlProcess.waitFor();

            // Try exact project name match
            for (String line : windowLines) {
                if (line.contains(projectName)) {
                    targetWindowId = line.split("\\s+")[0];
                    System.out.println("Found window with project name: " + line);
                    break;
                }
            }

            // Fall back to any IntelliJ window
            if (targetWindowId == null) {
                for (String line : windowLines) {
                    if (line.contains("[") && (line.contains(".java") || line.contains(".kt"))) {
                        targetWindowId = line.split("\\s+")[0];
                        System.out.println("Found IntelliJ-like window: " + line);
                        break;
                    }
                }
            }

            if (targetWindowId == null) {
                System.err.println("Could not find IntelliJ window for project: " + projectName);
                return;
            }

            // Save original window state
            WindowState originalState = getWindowState(targetWindowId);

            // Lock the window to the front
            setWindowState(targetWindowId, true, false);

            // Open URL
            java.awt.Desktop.getDesktop().browse(new java.net.URI(redirectUrl));

            // Wait for IntelliJ to process the URI
            Thread.sleep(500);

            // Focus again with direct X11 command
            new ProcessBuilder("bash", "-c", "xdotool windowactivate --sync " + targetWindowId).start().waitFor();

            // Restore original window state
            setWindowState(targetWindowId, originalState.isAbove, originalState.isBelow);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private static class WindowState {
        boolean isAbove;
        boolean isBelow;

        WindowState(boolean isAbove, boolean isBelow) {
            this.isAbove = isAbove;
            this.isBelow = isBelow;
        }
    }

    private static WindowState getWindowState(String windowId) throws IOException, InterruptedException {
        Process aboveProcess = new ProcessBuilder("bash", "-c",
                "xprop -id " + windowId + " _NET_WM_STATE 2>/dev/null | grep '_NET_WM_STATE_ABOVE'").start();
        boolean isAbove = new BufferedReader(new InputStreamReader(aboveProcess.getInputStream())).readLine() != null;
        aboveProcess.waitFor();

        Process belowProcess = new ProcessBuilder("bash", "-c",
                "xprop -id " + windowId + " _NET_WM_STATE 2>/dev/null | grep '_NET_WM_STATE_BELOW'").start();
        boolean isBelow = new BufferedReader(new InputStreamReader(belowProcess.getInputStream())).readLine() != null;
        belowProcess.waitFor();

        return new WindowState(isAbove, isBelow);
    }

    private static void setWindowState(String windowId, boolean above, boolean below) throws IOException, InterruptedException {
        new ProcessBuilder("bash", "-c", "wmctrl -i -r " + windowId + " -b remove,above").start().waitFor();
        new ProcessBuilder("bash", "-c", "wmctrl -i -r " + windowId + " -b remove,below").start().waitFor();

        if (above) new ProcessBuilder("bash", "-c", "wmctrl -i -r " + windowId + " -b add,above").start().waitFor();
        if (below) new ProcessBuilder("bash", "-c", "wmctrl -i -r " + windowId + " -b add,below").start().waitFor();
    }

    private static void focusWindow(String windowId) throws IOException, InterruptedException {
        new ProcessBuilder("bash", "-c", "wmctrl -i -a " + windowId).start().waitFor();
        Thread.sleep(100);
    }

    private static boolean isValidProjectName(String projectName) {
        return projectName != null && projectName.matches("^[a-zA-Z0-9_-]+$");
    }

    private static boolean isValidUrl(String url) {
        try {
            new java.net.URI(url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String checkRequiredTools() {
        String[] tools = {"xdotool", "wmctrl"};
        List<String> missingTools = new ArrayList<>();

        for (String tool : tools) {
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"bash", "-c", "command -v " + tool});
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    missingTools.add(tool);
                }
            } catch (Exception e) {
                e.printStackTrace();
                return "An error occurred while checking for required tools.";
            }
        }

        if (!missingTools.isEmpty()) {
            return String.format("This program requires %s to be installed. Install them using: sudo apt install %s",
                    String.join(", ", missingTools), String.join(" ", missingTools));
        }

        return null;
    }

    private static void addAutostartEntry() {
        String userHome = System.getProperty("user.home");
        Path autostartDir = Paths.get(userHome, ".config", "autostart");
        Path desktopFile = autostartDir.resolve("jboflistener.desktop");

        try {
            if (!Files.exists(autostartDir)) {
                Files.createDirectories(autostartDir);
            }

            // Detect the location of the currently running JAR file
            String jarPath = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
            if (!jarPath.endsWith(".jar")) {
                System.out.println("Not running from a JAR file, skipping autostart entry creation. (assuming development environment and not wanting to brick the location for next startup)");
                return;
            }
            System.out.println("JAR path: " + jarPath);

            String content = """
                    [Desktop Entry]
                    Type=Application
                    Name=Hypes Intellij File Opener
                    Exec=java -Dprogram.name=HypeIntelliJServer -jar %s
                    X-GNOME-Autostart-enabled=true
                    X-KDE-autostart-after=panel
                    StartupNotify=false
                    Terminal=false
                    """.formatted(jarPath);

            Files.write(desktopFile, content.getBytes());
            Files.setPosixFilePermissions(desktopFile, new HashSet<>(Arrays.asList(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE
            )));
            System.out.println("Autostart entry created at " + desktopFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void showErrorPopup(String message) {
        JOptionPane.showMessageDialog(null, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private static void setDefaultHttpHandler() {
        try {
            System.out.println("Injecting myself into xdg mime default for http to intercept port 9090 localhost requests");
            // Get the current default handler for HTTP
            Process getDefaultHandlerProcess = new ProcessBuilder("xdg-mime", "query", "default", "x-scheme-handler/http").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(getDefaultHandlerProcess.getInputStream()));
            String previousDefaultHandler = reader.readLine();
            getDefaultHandlerProcess.waitFor();
            String injectorDesktopName = "http-handler.desktop";
            if (previousDefaultHandler.equals(injectorDesktopName)) {
                System.out.println("Already injected into xdg mime default for http. Skipping.");
                return;
            }

            // Extract the executable path from the .desktop entry
            Path desktopFilePath = Paths.get("/usr/share/applications", previousDefaultHandler);
            if (!Files.exists(desktopFilePath)) {
                desktopFilePath = Paths.get(System.getProperty("user.home"), ".local", "share", "applications", previousDefaultHandler);
            }
            List<String> desktopFileLines = Files.readAllLines(desktopFilePath);
            String execLine = desktopFileLines.stream()
                    .filter(line -> line.startsWith("Exec="))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Exec line not found in .desktop file"));
            String originalExec = execLine.substring(5).split(" ")[0];

            // Create a script to handle HTTP URLs
            String scriptContent = """
                    #!/bin/bash
                    if [[ "$1" == "http://localhost:9090"* ]]; then
                        curl "$1" > /dev/null
                    else
                        %s "$1"
                    fi
                    """.formatted(originalExec);
            Path scriptPath = Paths.get(System.getProperty("user.home"), ".local", "bin", "http-handler.sh");
            Files.createDirectories(scriptPath.getParent());
            Files.write(scriptPath, scriptContent.getBytes());
            Files.setPosixFilePermissions(scriptPath, new HashSet<>(Arrays.asList(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE
            )));

            // Create a .desktop file to point to the script
            String desktopFileContent = """
                    [Desktop Entry]
                    Name=Hypes HTTP Handler (IntelliJ File Opener Injection)
                    Exec=%s %%u
                    Type=Application
                    MimeType=x-scheme-handler/http;x-scheme-handler/https;
                    """.formatted(scriptPath.toString());
            Path newDesktopFilePath = Paths.get(System.getProperty("user.home"), ".local", "share", "applications", injectorDesktopName);
            Files.createDirectories(newDesktopFilePath.getParent());
            Files.write(newDesktopFilePath, desktopFileContent.getBytes());

            // Set the .desktop file as the default handler for HTTP
            ProcessBuilder processBuilder = new ProcessBuilder("xdg-mime", "default", newDesktopFilePath.toString(), "x-scheme-handler/http", "x-scheme-handler/https");
            Process process = processBuilder.start();
            process.waitFor();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
