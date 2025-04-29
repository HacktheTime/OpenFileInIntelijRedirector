package de.hype;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Window {
    private String id;
    private final String windowName;
    private final String programmName;
    private boolean alwaysOnTop;
    private boolean alwaysOnBottom;

    private Window(String id, String windowName, String programmName, boolean alwaysOnTop, boolean alwaysOnBottom) {
        this.id = id;
        this.windowName = windowName;
        this.programmName = programmName;
        this.alwaysOnTop = alwaysOnTop;
        this.alwaysOnBottom = alwaysOnBottom;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Window window = (Window) obj;
        return id.equals(window.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    public String getId() {
        return id;
    }

    public String getWindowName() {
        return windowName;
    }

    public boolean isAlwaysOnTop() {
        return alwaysOnTop;
    }

    public boolean isAlwaysOnBottom() {
        return alwaysOnBottom;
    }

    public void setAlwaysOnTop(boolean alwaysOnTop) {
        if (this.alwaysOnTop == alwaysOnTop) return;
        this.alwaysOnTop = alwaysOnTop;
        try {
            if (alwaysOnTop) {
                new ProcessBuilder("wmctrl", "-i", "-r", id, "-b", "add,above").start().waitFor();
            } else {
                new ProcessBuilder("wmctrl", "-i", "-r", id, "-b", "remove,above").start().waitFor();
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void setAlwaysOnBottom(boolean alwaysOnBottom) {
        if (this.alwaysOnBottom == alwaysOnBottom) return;
        this.alwaysOnBottom = alwaysOnBottom;
        try {
            if (alwaysOnBottom) {
                new ProcessBuilder("wmctrl", "-i", "-r", id, "-b", "add,below").start().waitFor();
            } else {
                new ProcessBuilder("wmctrl", "-i", "-r", id, "-b", "remove,below").start().waitFor();
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void setAsFocus() {
        try {
            new ProcessBuilder("wmctrl", "-i", "-a", id).start().waitFor();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    public String getProgrammName() {
        return programmName;
    }

    public static List<Window> getCurrentWindows() {
        List<Window> windows = new ArrayList<>();
        List<String> windowIds = getWindowIdsFromNetClientListStacking();

        for (String winId : windowIds) {
            try {
                // Get window details using xprop
                String[] xpropCommand = {"xprop", "-id", winId, "WM_NAME", "WM_CLASS", "_NET_WM_STATE"};
                ProcessBuilder pb = new ProcessBuilder(xpropCommand);
                Process process = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                String windowName = "";
                String programName = "";
                List<String> wmStates = new ArrayList<>();
                boolean isAbove = false;
                boolean isBelow = false;

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("WM_NAME")) {
                        windowName = line.split("= ", 2)[1].replace("\"", "").trim();
                    } else if (line.startsWith("WM_CLASS")) {
                        programName = line.split("= ", 2)[1].replace("\"", "").trim().split(",")[0]; // Get the first part
                    } else if (line.startsWith("_NET_WM_STATE")) {
                        String[] states = line.split("= ", 2)[1].trim().split(", ");
                        for (String state : states) {
                            String cleanState = state.replace("\"", "").trim();
                            wmStates.add(cleanState);
                            if (cleanState.equals("_NET_WM_STATE_ABOVE")) {
                                isAbove = true;
                            } else if (cleanState.equals("_NET_WM_STATE_BELOW")) {
                                isBelow = true;
                            }
                        }
                    }
                }
                process.waitFor();

                windows.add(new Window(winId, windowName, programName, isAbove, isBelow));

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        Collections.reverse(windows);
        return windows;
    }

    private static List<String> getWindowIdsFromNetClientListStacking() {
        List<String> windowIds = new ArrayList<>();
        // Replace this with the actual command to get the _NET_CLIENT_LIST_STACKING
        // For example, using xprop or another method to retrieve the window IDs
        String command = "xprop -root _NET_CLIENT_LIST_STACKING"; // Example command
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            Process process = pb.start();
            process.waitFor();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            line = line.replaceFirst(".*#","").trim();
            return Arrays.stream(line.split(",")).map(String::trim).toList();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return windowIds;
    }

    @Override
    public String toString() {
        return "`%s` (%s)".formatted(windowName, id);
    }

    public Window copy() {
        return new Window(id, windowName, programmName, alwaysOnTop, alwaysOnBottom);
    }
}