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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main {
    String exampleClass = "de.hype.bbsentials.server.discord.events.staticimplementations.commands.commands.commandgroups.bingoeventcommandgroup.Top100";
    String exampleQuery = "http://localhost:9090/open?project=BBsentials-Server&class=de.hype.bbsentials.server.discord.events.staticimplementations.commands.commands.commandgroups.bingoeventcommandgroup.Top100";
    String exampleResourceQuery = "http://localhost:9090/openResource?project=BBsentials-Server&path=example.html"; // will search for any "example.html" in resources.
    String exampleResourceQuery2 = "http://localhost:9090/openResource?project=BBsentials-Server&path=/templates/example.html"; // will search for any "example.html" in resources which is in a folder called templates.
    String exampleResourceQuery3 = "http://localhost:9090/openResource?project=BBsentials-Server&path=/templates/**/example.html"; // will search for any "example.html" in resources which is in a sub path of a templates folder.
    // /**/ means any subfolder structure from no sub folder to infinite many subfolders. /*/ means any sub folder (1 layer specifically).
    String exampleResourceQuery4 = "http://localhost:9090/openResource?project=BBsentials-Server&regex=.*/example.html"; //regex will be applied for the search.
    String exampleResourceQuery5 = "http://localhost:9090/openResource?project=BBsentials-Server&regex=.*\\.html"; //if a search is ambiguous it will ask you to select which one you want in a pop up.

    public Main() throws IOException {
    }

    public static void main(String[] args) throws IOException {
        try {
            UIManager.setLookAndFeel("com.sun.java.swing.plaf.gtk.GTKLookAndFeel");
        } catch (Exception e) {
            System.err.println("Failed to set GTK Look and Feel. Get blinded ig.");
        }
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
        server.createContext("/openResource", new OpenResourceHandler());
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



    static boolean isValidUrl(String url) {
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
