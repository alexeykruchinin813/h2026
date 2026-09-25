package ru.dit.heattracer.service;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Диагностический тест: проверяет, какой Docker API отвечает на tcp://localhost:2375.
 *
 * <p>Намеренно НЕ использует Testcontainers/docker-java — только чистый JDK HttpURLConnection,
 * чтобы изолированно показать, что именно возвращает сервер по разным версиям API.
 * Запуск: {@code mvn test -Dtest=DockerApiProbeTest}
 */
class DockerApiProbeTest {

    private static String probe(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        int code = c.getResponseCode();
        String body;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                code >= 400 ? c.getErrorStream() : c.getInputStream()))) {
            body = r.lines().collect(Collectors.joining(" "));
        }
        if (body.length() > 300) body = body.substring(0, 300) + "...";
        return "HTTP " + code + " | " + body;
    }

    @Test
    void printDockerApiResponses() throws Exception {
        Path homeProps = Path.of(System.getProperty("user.home"), ".testcontainers.properties");
        System.out.println("=== DOCKER API PROBE ===");
        System.out.println("~/.testcontainers.properties существует: " + Files.exists(homeProps));
        if (Files.exists(homeProps)) {
            System.out.println("Содержимое:\n" + Files.readString(homeProps));
        }
        System.out.println("DOCKER_HOST=" + System.getenv("DOCKER_HOST"));
        for (String u : new String[]{
                "http://localhost:2375/version",
                "http://localhost:2375/_ping",
                "http://localhost:2375/v1.32/info",
                "http://localhost:2375/v1.44/info"}) {
            try {
                System.out.println(u + "  ->  " + probe(u));
            } catch (Exception e) {
                System.out.println(u + "  ->  EXCEPTION: " + e);
            }
        }
        System.out.println("========================");
        assumeTrue(false, "Диагностика: см. вывод выше (тест помечен skipped намеренно)");
    }
}
