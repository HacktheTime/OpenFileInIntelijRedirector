package de.hype;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static de.hype.IntelijHandler.focusIntelliJWindow;
import static de.hype.Main.showErrorPopup;

public class OpenHandler implements HttpHandler {
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
                className = param.substring(6).split("\\$")[0];
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
                    redirectUrl += ":%d".formatted(Integer.parseInt(line) - 1);
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
        String classFileNameRegex = ".*%s\\.(java|kt)$".formatted(Pattern.quote(className.substring(className.lastIndexOf('.') + 1)));
        String packageName = className.substring(0, className.lastIndexOf('.')).replace('.', '/');

        try (Stream<Path> paths = Files.walk(projectPath).parallel()) {
            List<Path> matchingFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String pathString = path.toString();
                        if (!pathString.matches(classFileNameRegex)) return false;
                        if (pathString.matches("(?!.*/resources/).*/build/tmp/.*")) return false;
                        return true;
                    })
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
                    String filePackage = line.substring(8).replace(";","").trim().replace('.', '/');
                    return filePackage.equals(packageName);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }
}