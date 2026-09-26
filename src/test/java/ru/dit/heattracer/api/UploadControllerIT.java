package ru.dit.heattracer.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import ru.dit.heattracer.service.BasePostgresIntegrationTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("REST API UploadController (HTTP)")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadControllerIT extends BasePostgresIntegrationTest {

    private static final long TIMEOUT_MS = 180_000;
    private static final long POLL_INTERVAL_MS = 1_000;
    private static final String TEST_DATASET_RESOURCE = "first_dataset.geojson";

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("POST /api/upload → DONE → GET /api/task/{id}/result возвращает GeoJSON §7")
    void fullPipelineOverHttp() throws Exception {
        ClassPathResource resource = new ClassPathResource(TEST_DATASET_RESOURCE);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<Map> uploadResp = rest.postForEntity(
                "/api/upload", new HttpEntity<>(body, headers), Map.class);

        assertEquals(HttpStatus.OK, uploadResp.getStatusCode());
        assertNotNull(uploadResp.getBody());
        String taskId = String.valueOf(uploadResp.getBody().get("taskId"));
        assertNotEquals("null", taskId);
        System.out.printf("[HTTP-TEST] Загружено, taskId=%s%n", taskId);

        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        String finalStatus = null;
        String lastStage = null;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<Map> statusResp = rest.getForEntity(
                    "/api/task/" + taskId, Map.class);
            assertEquals(HttpStatus.OK, statusResp.getStatusCode());
            finalStatus = (String) statusResp.getBody().get("status");
            lastStage = (String) statusResp.getBody().get("stage");
            if ("DONE".equals(finalStatus) || "FAILED".equals(finalStatus)) break;
            Thread.sleep(POLL_INTERVAL_MS);
        }
        assertEquals("DONE", finalStatus,
                "Задача должна завершиться DONE. Последний stage: " + lastStage);
        System.out.printf("[HTTP-TEST] DONE, stage=%s%n", lastStage);

        ResponseEntity<String> resultResp = rest.getForEntity(
                "/api/task/" + taskId + "/result", String.class);
        assertEquals(HttpStatus.OK, resultResp.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, resultResp.getHeaders().getContentType());

        String geoJson = resultResp.getBody();
        assertNotNull(geoJson);

        assertTrue(geoJson.contains("\"FeatureCollection\""));
        assertTrue(geoJson.contains("\"heat_network\""));
        assertTrue(geoJson.contains("\"heat_chamber\""));
        assertTrue(geoJson.contains("\"variant_summary\""));
        assertTrue(geoJson.contains("\"variant_id\""));

        // ТЗ 2.8: «основной + до двух содержательно отличающихся» (1..3 после
        // дедупликации). На плотном first_dataset.geojson обычно остаётся
        // 2 варианта (v1 и v2); v3 вырождается в v2 из-за правила 10 м.
        boolean hasVariant = geoJson.contains("\"v1\"")
                || geoJson.contains("\"v2\"")
                || geoJson.contains("\"v3\"");
        assertTrue(hasVariant, "Должен быть хотя бы один вариант (v1/v2/v3)");

        assertTrue(geoJson.contains("\"start_node_id\""));
        assertTrue(geoJson.contains("\"end_node_id\""));
        assertTrue(geoJson.contains("\"flow_tph\""));
        assertTrue(geoJson.contains("\"diameter\""));
        assertTrue(geoJson.contains("\"laying_method\""));
        assertTrue(geoJson.contains("\"cost\""));
        assertTrue(geoJson.contains("\"rank\""));
        assertTrue(geoJson.contains("\"score\""));
        assertTrue(geoJson.contains("\"calculated_cost\""));
        assertTrue(geoJson.contains("\"unconnected_oks_ids\""));

        System.out.printf("[HTTP-TEST] Результат: %d символов%n", geoJson.length());
    }
}