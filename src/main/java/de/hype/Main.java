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
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main {
    String exampleClass = "de.hype.bbsentials.server.discord.events.staticimplementations.commands.commands.commandgroups.bingoeventcommandgroup.Top100";
    String exampleQuery = "http://localhost:9090/open?project=BBsentials-Server&class=de.hype.bbsentials.server.discord.events.staticimplementations.commands.commands.commandgroups.bingoeventcommandgroup.Top100";

    public Main() throws IOException {
    }

    public static void main(String[] args) throws IOException {
        String tools = checkRequiredTools();
        addAutostartEntry();
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

    static class OpenHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String remoteAddress = exchange.getRemoteAddress().getAddress().getHostAddress();
            if (!"127.0.0.1".equals(remoteAddress) && !"::1".equals(remoteAddress)) {
                String errorResponse = "Access denied";
                exchange.sendResponseHeaders(403, errorResponse.getBytes().length);
                OutputStream os = exchange.getResponseBody();
                os.write(errorResponse.getBytes());
                os.close();
                showErrorPopup("Access denied from " + remoteAddress);
                return;
            }
            String query = exchange.getRequestURI().getQuery();
            String[] params = query.split("&");
            String className = null;
            String project = null;
            String line = null;

            for (String param : params) {
                if (param.startsWith("class=")) {
                    className = param.substring(6);
                } else if (param.startsWith("project=")) {
                    project = param.substring(8);
                } else if (param.startsWith("line=")) {
                    line = param.substring(5);
                }
            }
            String projectPath = "~/IdeaProjects/" + project + "/";

            if (className != null && projectPath != null) {
                String response = searchClassFile(className, Paths.get(projectPath));
                if (response != null) {
                    String redirectUrl = "jetbrains://idea/navigate/reference?project=" + project + "&path=" + response;
                    if (line != null) {
                        redirectUrl += ":%d".formatted(Integer.parseInt(line)-1);
                    }

                    // Send a response to the browser to close the tab
                    String closeTabScript = "<html><body><script type='text/javascript'>window.open('" + redirectUrl + "', '_self'); window.close();</script></body></html>";
                    exchange.getResponseHeaders().set("Content-Type", "text/html");
                    exchange.sendResponseHeaders(200, closeTabScript.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(closeTabScript.getBytes());
                    os.close();
                    String finalRedirectUrl = redirectUrl;
                    focusIntelliJWindow(project, finalRedirectUrl);
                } else {
                    String errorResponse = "Class file not found";
                    exchange.sendResponseHeaders(404, errorResponse.getBytes().length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(errorResponse.getBytes());
                    os.close();
                }
            } else {
                String errorResponse = "Invalid parameters";
                exchange.sendResponseHeaders(400, errorResponse.getBytes().length);
                OutputStream os = exchange.getResponseBody();
                os.write(errorResponse.getBytes());
                os.close();
            }
        }

        private static String searchClassFile(String className, Path projectPath) {
            projectPath = Paths.get(projectPath.toString().replaceFirst("^~", System.getProperty("user.home"))).toAbsolutePath().normalize();
            String classFileName = className.substring(className.lastIndexOf('.') + 1) + ".java";
            String packageName = className.substring(0, className.lastIndexOf('.')).replace('.', '/');

            try (Stream<Path> paths = Files.walk(projectPath)) {
                List<Path> matchingFiles = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(classFileName))
                        .collect(Collectors.toList());

                for (Path file : matchingFiles) {
                    if (isCorrectPackage(file, packageName)) {
                        return projectPath.relativize(file).toString().replace(File.separator, "/");
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }

        private static boolean isCorrectPackage(Path file, String packageName) {
            try {
                List<String> lines = Files.readAllLines(file);
                for (String line : lines) {
                    if (line.startsWith("package ")) {
                        String filePackage = line.substring(8, line.indexOf(';')).trim().replace('.', '/');
                        return filePackage.equals(packageName);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return false;
        }
    }

    private static void focusIntelliJWindow(String projectName, String redirectUrl) {
        try {
            // Validate and sanitize inputs
            if (!isValidProjectName(projectName) || !isValidUrl(redirectUrl)) {
                throw new IllegalArgumentException("Invalid project name or URL");
            }

            // Search for windows with the class 'idea' and get their IDs
            ProcessBuilder searchBuilder = new ProcessBuilder("bash", "-c", "wmctrl -lx | grep 'idea'");
            Process searchProcess = searchBuilder.start();
            BufferedReader searchReader = new BufferedReader(new InputStreamReader(searchProcess.getInputStream()));
            String line;
            Set<String> windowIds = new HashSet<>();
            while ((line = searchReader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length > 0) {
                    windowIds.add(parts[0]);
                }
            }
            searchProcess.waitFor();

            // Store the original "always on top" state of all IntelliJ windows
            Map<String, Boolean> originalStates = new HashMap<>();
            for (String id : windowIds) {
                ProcessBuilder stateBuilder = new ProcessBuilder("bash", "-c", "xprop -id " + id + " | grep '_NET_WM_STATE_ABOVE'");
                Process stateProcess = stateBuilder.start();
                BufferedReader stateReader = new BufferedReader(new InputStreamReader(stateProcess.getInputStream()));
                boolean isAlwaysOnTop = stateReader.readLine() != null;
                originalStates.put(id, isAlwaysOnTop);
                stateProcess.waitFor();
            }

            // Remove "always on top" state from all IntelliJ windows
            for (String id : windowIds) {
                new ProcessBuilder("bash", "-c", "wmctrl -i -r " + id + " -b remove,above").start();
            }

            // Set the target window to "always on top" and activate it
            String projectWindowId = null;
            for (String id : windowIds) {
                ProcessBuilder nameBuilder = new ProcessBuilder("bash", "-c", "wmctrl -l -G -p -x | grep " + id);
                Process nameProcess = nameBuilder.start();
                BufferedReader nameReader = new BufferedReader(new InputStreamReader(nameProcess.getInputStream()));
                String windowName = nameReader.readLine();
                if (windowName != null && windowName.contains(projectName)) {
                    projectWindowId = id;
                    new ProcessBuilder("bash", "-c", "wmctrl -i -r " + id + " -b add,above").start();
                    new ProcessBuilder("bash", "-c", "wmctrl -i -a " + id).start();
                    break;
                }
                nameProcess.waitFor();
            }

            // Open the file link
            java.awt.Desktop.getDesktop().browse(new java.net.URI(redirectUrl));

            // Close the new tab after a delay
            if (projectWindowId != null)
                new ProcessBuilder("bash", "-c", "wmctrl -i -a " + projectWindowId).start();

            Thread.sleep(2000); // Adjust the delay as needed
            // Restore the original "always on top" state of all IntelliJ windows
            for (Map.Entry<String, Boolean> entry : originalStates.entrySet()) {
                if (entry.getValue()) {
                    new ProcessBuilder("bash", "-c", "wmctrl -i -r " + entry.getKey() + " -b add,above").start();
                } else {
                    new ProcessBuilder("bash", "-c", "wmctrl -i -r " + entry.getKey() + " -b remove,above").start();
                }
            }
            if (projectWindowId != null)
                new ProcessBuilder("bash", "-c", "wmctrl -i -a " + projectWindowId).start();

        } catch (Exception e) {
            e.printStackTrace();
        }
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

            String content = "[Desktop Entry]\n" +
                    "Type=Application\n" +
                    "Name=Hypes Intellij File Opener\n" +
                    "Exec=java -jar " + jarPath + "\n" +
                    "X-GNOME-Autostart-enabled=true\n";

            Files.write(desktopFile, content.getBytes());
            System.out.println("Autostart entry created at " + desktopFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void showErrorPopup(String message) {
        JOptionPane.showMessageDialog(null, message, "Error", JOptionPane.ERROR_MESSAGE);
    }
}
