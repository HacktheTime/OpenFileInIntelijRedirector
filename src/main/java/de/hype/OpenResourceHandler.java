package de.hype;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static de.hype.IntelijHandler.openFileInIntelliJ;
import static de.hype.Main.showErrorPopup;

class OpenResourceHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Security check
        String remoteAddress = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (!"127.0.0.1".equals(remoteAddress) && !"::1".equals(remoteAddress)) {
            sendError(exchange, 403, "Access denied");
            showErrorPopup("Access denied from " + remoteAddress);
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        String[] params = query.split("&");
        String project = null;
        String path = null;
        String regex = null;
        String line = null;
        boolean focusTestResource = false;

        for (String param : params) {
            if (param.startsWith("project=")) {
                project = param.substring(8);
            } else if (param.startsWith("path=")) {
                path = param.substring(5);
            } else if (param.startsWith("regex=")) {
                regex = param.substring(6);
            } else if (param.startsWith("line=")) {
                line = param.substring(5);
            } else if (param.equals("focustestresource")) {
                focusTestResource = true;
            }
        }

        if (project == null || (path == null && regex == null)) {
            sendError(exchange, 400, "Missing required parameters");
            return;
        }

        String projectPath = "~/IdeaProjects/" + project + "/";
        Set<ResourceMatch> matches;

        try {
            if (regex != null) {
                matches = findResourcesByRegex(regex, Paths.get(projectPath), focusTestResource);
            } else {
                matches = findResourcesByPath(path, Paths.get(projectPath), focusTestResource);
            }

            if (matches.isEmpty()) {
                sendError(exchange, 404, "Resource not found");
                return;
            } else if (matches.size() == 1) {
                ResourceMatch match = matches.stream().toList().get(0);
                String relativePath = match.getRelativePath();
                openFileInIntelliJ(exchange, project, relativePath, line);
            } else {
                // Multiple matches found, show selection dialog
                showResourceSelectionDialog(exchange, matches, project, line);
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Error processing request: " + e.getMessage());
        }
    }

    private static class ResourceMatch {
        private final Path projectPath;
        private final Path filePath;
        private final ResourceType type;

        public ResourceMatch(Path projectPath, Path filePath, ResourceType type) {
            this.projectPath = projectPath;
            this.filePath = filePath;
            this.type = type;
        }

        public Path getFilePath() {
            return filePath;
        }

        public ResourceType getType() {
            return type;
        }

        public String getRelativePath() {
            return projectPath.relativize(filePath).toString().replace(File.separator, "/");
        }

        public String getDisplayPath() {
            return getRelativePath() + " [" + type + "]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ResourceMatch that = (ResourceMatch) o;
            return Objects.equals(getRelativePath(), that.getRelativePath());
        }

        @Override
        public int hashCode() {
            return Objects.hash(getRelativePath());
        }
    }

    private enum ResourceType {
        MAIN_RESOURCE("Main"),
        GENERATED_RESOURCE("Generated"),
        TEST_RESOURCE("Test");

        private final String displayName;

        ResourceType(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private Set<ResourceMatch> findResourcesByPath(String pathPattern, Path projectPath, boolean focusTestResource) throws IOException {
        projectPath = resolveProjectPath(projectPath);
        Set<ResourceMatch> allMatches = new HashSet<>();

        // Define resource directories to search
        Map<ResourceType, List<Path>> resourceDirs = getResourceDirectories(projectPath);
        List<ResourceType> searchOrder = getSearchOrder(focusTestResource);

        // Clean up path pattern
        if (pathPattern.startsWith("/")) {
            pathPattern = pathPattern.substring(1);
        }

        // Check if the path contains wildcards
        boolean hasWildcards = pathPattern.contains("**") || pathPattern.contains("*");
        final String finalPattern = pathPattern;

        for (ResourceType resourceType : searchOrder) {
            for (Path resourceDir : resourceDirs.get(resourceType)) {
                if (!Files.exists(resourceDir)) {
                    continue;
                }

                try (Stream<Path> walk = Files.walk(resourceDir)) {
                    List<Path> matches;

                    if (hasWildcards) {
                        // Convert path pattern with wildcards to glob pattern
                        String globPattern = convertToGlobPattern(finalPattern);
                        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);

                        // Search with glob pattern
                        matches = walk
                                .filter(Files::isRegularFile)
                                .filter(p -> {
                                    Path relativePath = resourceDir.relativize(p);
                                    return matcher.matches(relativePath);
                                })
                                .collect(Collectors.toList());
                    } else {
                        // First try direct path match
                        Path targetPath = resourceDir.resolve(finalPattern);
                        if (Files.exists(targetPath) && Files.isRegularFile(targetPath)) {
                            matches = Collections.singletonList(targetPath);
                        } else {
                            // Then try more flexible matching strategies
                            String fileName = Paths.get(finalPattern).getFileName().toString();

                            matches = walk
                                    .filter(Files::isRegularFile)
                                    .filter(p -> {
                                        // Match by exact filename
                                        if (p.getFileName().toString().equals(fileName)) {
                                            return true;
                                        }

                                        // Match by path ending
                                        String relativePath = resourceDir.relativize(p).toString().replace(File.separator, "/");
                                        // Check if the path ends with the requested path or path without leading slash
                                        return relativePath.endsWith(finalPattern) ||
                                                (finalPattern.contains("/") && relativePath.endsWith(finalPattern)) ||
                                                // Also check partial path matching
                                                (finalPattern.contains("/") && relativePath.contains(finalPattern));
                                    })
                                    .collect(Collectors.toList());
                        }
                    }

                    // Add matches with their type
                    for (Path match : matches) {
                        allMatches.add(new ResourceMatch(projectPath, match, resourceType));
                    }
                }
            }
        }

        // Debug info when no matches found
        if (allMatches.isEmpty()) {
            System.out.println("No matches found for: " + finalPattern);
            System.out.println("Searched directories:");
            for (ResourceType type : searchOrder) {
                for (Path dir : resourceDirs.get(type)) {
                    System.out.println(" - " + dir + " (exists: " + Files.exists(dir) + ")");
                }
            }
        }

        return allMatches;
    }

    private String convertToGlobPattern(String pattern) {
        // Handle empty pattern
        if (pattern == null || pattern.isEmpty()) {
            return "**";
        }

        // Handle pattern with leading/trailing slashes
        pattern = pattern.replaceAll("^/+", "").replaceAll("/+$", "");

        // Replace ** wildcards (any number of directories)
        if (pattern.contains("**")) {
            pattern = pattern.replace("**/", "**/");
            pattern = pattern.replace("/**", "/**");
        }

        // Handle single * wildcards (within a directory)
        if (!pattern.contains("**") && pattern.contains("*")) {
            pattern = pattern.replace("/*/", "/?*/");
        }

        // If pattern is just a filename without wildcards, allow it to be found anywhere
        if (!pattern.contains("/") && !pattern.contains("*")) {
            pattern = "**/" + pattern;
        }
        // If pattern is a path without wildcards, also try finding it anywhere
        else if (!pattern.contains("*")) {
            // Keep original pattern but also look everywhere
            pattern = "**/" + pattern;
        }

        return pattern;
    }

    private Set<ResourceMatch> findResourcesByRegex(String regex, Path projectPath, boolean focusTestResource) throws IOException {
        projectPath = resolveProjectPath(projectPath);
        Set<ResourceMatch> allMatches = new HashSet<>();

        // Define resource directories to search
        Map<ResourceType, List<Path>> resourceDirs = getResourceDirectories(projectPath);
        List<ResourceType> searchOrder = getSearchOrder(focusTestResource);

        // Compile regex pattern
        Pattern pattern = Pattern.compile(regex);

        for (ResourceType resourceType : searchOrder) {
            for (Path resourceDir : resourceDirs.get(resourceType)) {
                if (!Files.exists(resourceDir)) {
                    continue;
                }

                List<Path> matches = Files.walk(resourceDir)
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String relativePath = resourceDir.relativize(p).toString().replace(File.separator, "/");
                            return pattern.matcher(relativePath).matches();
                        })
                        .toList();

                // Add matches with their type
                for (Path match : matches) {
                    allMatches.add(new ResourceMatch(projectPath, match, resourceType));
                }
            }
        }

        return allMatches;
    }

    private Path resolveProjectPath(Path projectPath) {
        return Paths.get(projectPath.toString().replaceFirst("^~", System.getProperty("user.home")))
                .toAbsolutePath().normalize();
    }

    private Map<ResourceType, List<Path>> getResourceDirectories(Path projectPath) {
        Map<ResourceType, List<Path>> resourceDirs = new HashMap<>();
        List<Path> moduleRoots = findAllModuleRoots(projectPath);

        // Common resource directories for all Java project types
        List<Path> mainResDirs = new ArrayList<>();
        List<Path> genResDirs = new ArrayList<>();
        List<Path> testResDirs = new ArrayList<>();

        for (Path moduleRoot : moduleRoots) {
            // Main resources - standard patterns across build systems
            mainResDirs.add(moduleRoot.resolve("src/main/resources"));
            mainResDirs.add(moduleRoot.resolve("src/resources"));
            mainResDirs.add(moduleRoot.resolve("resources"));
            mainResDirs.add(moduleRoot.resolve("WebContent/WEB-INF/classes"));
            mainResDirs.add(moduleRoot.resolve("web/WEB-INF/classes"));

            // Generated resources only (not built copies of source resources)
            genResDirs.add(moduleRoot.resolve("build/generated/resources"));
            genResDirs.add(moduleRoot.resolve("out/production/generated-resources"));
            genResDirs.add(moduleRoot.resolve("target/generated-resources"));

            // Test resources
            testResDirs.add(moduleRoot.resolve("src/test/resources"));
            testResDirs.add(moduleRoot.resolve("test/resources"));
            testResDirs.add(moduleRoot.resolve("build/generated/resources/test"));
            testResDirs.add(moduleRoot.resolve("out/test/resources"));
            testResDirs.add(moduleRoot.resolve("target/test-classes"));
        }

        // Add resource directories found by general search
        try {
            findAllResourceDirectories(projectPath, 4).forEach(dir -> {
                String path = dir.toString().toLowerCase();
                if (path.contains("test")) {
                    testResDirs.add(dir);
                } else if (path.contains("build") || path.contains("target") || path.contains("out")) {
                    genResDirs.add(dir);
                } else {
                    mainResDirs.add(dir);
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }

        resourceDirs.put(ResourceType.MAIN_RESOURCE, mainResDirs);
        resourceDirs.put(ResourceType.GENERATED_RESOURCE, genResDirs);
        resourceDirs.put(ResourceType.TEST_RESOURCE, testResDirs);

        return resourceDirs;
    }

    private List<Path> findAllResourceDirectories(Path projectPath, int maxDepth) throws IOException {
        List<Path> resourceDirs = new ArrayList<>();

        // Find all directories named "resources"
        try (Stream<Path> paths = Files.walk(projectPath, maxDepth)) {
            paths.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase("resources") ||
                            p.getFileName().toString().endsWith("Resources"))
                    .forEach(resourceDirs::add);
        }

        // Also check for "webapp" directories which often contain web resources
        try (Stream<Path> paths = Files.walk(projectPath, maxDepth)) {
            paths.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase("webapp") ||
                            p.getFileName().toString().equalsIgnoreCase("WebContent"))
                    .forEach(resourceDirs::add);
        }

        return resourceDirs;
    }

    private List<Path> findAllModuleRoots(Path projectPath) {
        List<Path> moduleRoots = new ArrayList<>();

        // Always include the project root
        moduleRoots.add(projectPath);

        try {
            // Find module roots based on common project files (Gradle, Maven, IntelliJ)
            try (Stream<Path> paths = Files.walk(projectPath, 3)) { // Increased depth to catch more modules
                paths.filter(Files::isRegularFile)
                        .filter(p -> {
                            String fileName = p.getFileName().toString();
                            return fileName.equals("build.gradle") ||
                                    fileName.equals("build.gradle.kts") ||
                                    fileName.equals("pom.xml") ||
                                    fileName.endsWith(".iml");
                        })
                        .map(Path::getParent)
                        .filter(path -> !path.equals(projectPath)) // Skip the root project we already added
                        .forEach(moduleRoots::add);
            }

            // Check for settings files to find included projects
            checkSettingsFile(projectPath, "settings.gradle", moduleRoots);
            checkSettingsFile(projectPath, "settings.gradle.kts", moduleRoots);

            // Check for Maven modules
            Path mavenModules = projectPath.resolve("pom.xml");
            if (Files.exists(mavenModules)) {
                List<String> lines = Files.readAllLines(mavenModules);
                boolean inModules = false;
                for (String line : lines) {
                    if (line.contains("<modules>")) inModules = true;
                    else if (line.contains("</modules>")) inModules = false;
                    else if (inModules && line.contains("<module>")) {
                        String module = line.replaceAll(".*<module>(.*)</module>.*", "$1").trim();
                        Path moduleRoot = projectPath.resolve(module);
                        if (Files.isDirectory(moduleRoot)) {
                            moduleRoots.add(moduleRoot);
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return moduleRoots;
    }

    private void checkSettingsFile(Path projectPath, String filename, List<Path> moduleRoots) {
        Path settingsFile = projectPath.resolve(filename);
        if (Files.exists(settingsFile)) {
            try {
                List<String> lines = Files.readAllLines(settingsFile);
                for (String line : lines) {
                    line = line.trim();
                    if (line.contains("include") && line.contains("\"")) {
                        // Extract project name from include statement
                        int startIndex = line.indexOf("\"") + 1;
                        int endIndex = line.indexOf("\"", startIndex);
                        if (startIndex > 0 && endIndex > startIndex) {
                            String projectName = line.substring(startIndex, endIndex);
                            // Convert project path format to directory
                            if (projectName.startsWith(":")) {
                                projectName = projectName.substring(1);
                            }
                            projectName = projectName.replace(":", "/");
                            Path moduleRoot = projectPath.resolve(projectName);
                            if (Files.isDirectory(moduleRoot)) {
                                moduleRoots.add(moduleRoot);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private List<ResourceType> getSearchOrder(boolean focusTestResource) {
        if (focusTestResource) {
            return Arrays.asList(
                    ResourceType.TEST_RESOURCE,
                    ResourceType.MAIN_RESOURCE,
                    ResourceType.GENERATED_RESOURCE
            );
        } else {
            return Arrays.asList(
                    ResourceType.MAIN_RESOURCE,
                    ResourceType.GENERATED_RESOURCE,
                    ResourceType.TEST_RESOURCE
            );
        }
    }

    private void showResourceSelectionDialog(HttpExchange exchange, Set<ResourceMatch> matches,
                                             String project, String line) {
        try {
            // Set system look and feel

            JFrame frame = new JFrame("Select Resource File");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setSize(700, 400);

            JPanel panel = new JPanel();
            panel.setLayout(new BorderLayout());

            JLabel label = new JLabel("Multiple resource files found. Please select one:");
            panel.add(label, BorderLayout.NORTH);

            JPanel buttonsPanel = new JPanel();
            buttonsPanel.setLayout(new BoxLayout(buttonsPanel, BoxLayout.Y_AXIS));

            for (ResourceMatch match : matches) {
                JButton button = new JButton(match.getDisplayPath());
                button.setAlignmentX(Component.LEFT_ALIGNMENT);
                button.setHorizontalAlignment(SwingConstants.LEFT);

                button.addActionListener(e -> {
                    try {
                        openFileInIntelliJ(exchange, project, match.getRelativePath(), line);
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                    frame.dispose();
                });

                buttonsPanel.add(button);
                buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
            }

            JScrollPane scrollPane = new JScrollPane(buttonsPanel);
            scrollPane.getVerticalScrollBar().setUnitIncrement(6);
            panel.add(scrollPane, BorderLayout.CENTER);

            frame.add(panel);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        } catch (Exception e) {
            e.printStackTrace();
            try {
                sendError(exchange, 500, "Error displaying file selection dialog");
            } catch (IOException ioEx) {
                ioEx.printStackTrace();
            }
        }
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        exchange.sendResponseHeaders(code, message.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(message.getBytes());
        os.close();
    }
}