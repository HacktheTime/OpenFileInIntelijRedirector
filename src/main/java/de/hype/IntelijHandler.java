package de.hype;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.regex.Pattern;

import static de.hype.Main.isValidUrl;

public class IntelijHandler {
    static void openFileInIntelliJ(HttpExchange exchange, String project, String path, String line) throws IOException {
        String redirectUrl = "jetbrains://idea/navigate/reference?project=" + project + "&path=" + path;
        if (line != null) {
            redirectUrl += ":%d".formatted(Integer.parseInt(line) - 1);
        }

        // Send response to browser
        String closeTabScript = "<html><body><script type='text/javascript'>window.open('" +
                redirectUrl + "', '_self'); window.close();</script></body></html>";
        exchange.getResponseHeaders().set("Content-Type", "text/html");
        exchange.sendResponseHeaders(200, closeTabScript.length());
        OutputStream os = exchange.getResponseBody();
        os.write(closeTabScript.getBytes());
        os.close();

        focusIntelliJWindow(project, redirectUrl);
    }

    static synchronized void focusIntelliJWindow(String projectName, String redirectUrl) {
        try {
            if (!isValidProjectName(projectName) || !isValidUrl(redirectUrl)) {
                throw new IllegalArgumentException("Invalid project name or URL");
            }

            //get the right intelij window and mark always on top as well as all the other in front windows.
            LinkedHashMap<String, Window> originalWindows = new LinkedHashMap<>();
            LinkedHashMap<String, Window> currentWindows = new LinkedHashMap<>();
            for (Window window : Window.getCurrentWindows()) {
                originalWindows.put(window.getId(), window.copy());
                currentWindows.put(window.getId(), window);
            }
            Window targetWindow = null;
            Window originalTargetWindow = null;
            for (Window window : currentWindows.values()) {
                if (window.getWindowName().matches("%s – .*".formatted(Pattern.quote(projectName)))) {
                    targetWindow = window;
                    originalTargetWindow = originalWindows.get(window.getId());
                    targetWindow.setAlwaysOnTop(true);
                } else {
                    if (window.getProgrammName().equals("intelij-idea")) {
                        window.setAlwaysOnTop(false);
                        window.setAlwaysOnBottom(true);
                    }
                }
            }
            // Open URL
            java.awt.Desktop.getDesktop().browse(new java.net.URI(redirectUrl));

            // Wait for IntelliJ to process the URI
            Thread.sleep(10000);

            if (targetWindow == null) {
                //This means Intelij will have been opened for it.
                for (Window window : Window.getCurrentWindows()) {
                    if (window.getWindowName().matches("%s - .*".formatted(Pattern.quote(projectName)))) {
                        targetWindow = window;
                        Window copy = window.copy();
                        originalWindows.put(copy.getId(), copy);
                        originalTargetWindow = copy;
                        currentWindows.put(window.getId(), window);
                        targetWindow.setAlwaysOnTop(true);
                    }
                }
                if (targetWindow == null) return; //This means Error or sth.
            }
            restoreWindowOrder(originalWindows.values());

            for (Window currentWindow : currentWindows.values()) {
                Window originalWindow = originalWindows.get(currentWindow.getId());
                currentWindow.setAlwaysOnTop(originalWindow.isAlwaysOnTop());
                currentWindow.setAlwaysOnBottom(originalWindow.isAlwaysOnBottom());
            }

            targetWindow.setAsFocus();
            targetWindow.setAlwaysOnTop(originalTargetWindow.isAlwaysOnTop());
            targetWindow.setAlwaysOnBottom(originalTargetWindow.isAlwaysOnBottom());


        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void restoreWindowOrder(Collection<Window> desiredOrder) {
        for (Window windowId : desiredOrder) {
            try {
                // Bring the window to the top of the stack
                ProcessBuilder pb = new ProcessBuilder("wmctrl", "-r", windowId.getId(), "-b", "add,above");
                Process process = pb.start();
                process.waitFor();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static boolean isValidProjectName(String projectName) {
        return projectName != null && projectName.matches("^[a-zA-Z0-9_-]+$");
    }


}
