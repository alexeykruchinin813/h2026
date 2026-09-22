package ru.dit.heattracer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.dit.heattracer.model.OksCluster;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ClusterService {

    private static final Logger log = LoggerFactory.getLogger(ClusterService.class);

    private static final double DEFAULT_EPS_METERS = 150.0;

    private final JdbcTemplate jdbc;

    public ClusterService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Кластеризует oks_connection_point для задачи.
     * Возвращает список кластеров.
     */
    public List<OksCluster> cluster(UUID taskId) {
        return cluster(taskId, DEFAULT_EPS_METERS);
    }

    public List<OksCluster> cluster(UUID taskId, double epsMeters) {
        long start = System.currentTimeMillis();

        Map<Integer, OksCluster> byId = new LinkedHashMap<>();

        jdbc.query(
                "SELECT cluster_id, feature_id, flow_tph, cluster_lon, cluster_lat " +
                        "FROM cluster_oks(?, ?) " +
                        "ORDER BY cluster_id, feature_id",
                rs -> {
                    int cid = rs.getInt("cluster_id");
                    String fid = rs.getString("feature_id");
                    double flow = rs.getDouble("flow_tph");
                    double lon = rs.getDouble("cluster_lon");
                    double lat = rs.getDouble("cluster_lat");

                    OksCluster c = byId.computeIfAbsent(cid, OksCluster::new);
                    c.addPoint(fid, flow, lon, lat);
                },
                taskId, epsMeters
        );

        List<OksCluster> clusters = new ArrayList<>(byId.values());

        long elapsed = System.currentTimeMillis() - start;
        int totalPoints = clusters.stream().mapToInt(OksCluster::size).sum();
        double totalFlow = clusters.stream().mapToDouble(OksCluster::getTotalFlow).sum();

        log.info("[{}] OKS clustering: {} clusters from {} points, total_flow={} ({} ms)",
                taskId, clusters.size(), totalPoints, totalFlow, elapsed);

        for (OksCluster c : clusters) {
            log.info("[{}]   {}", taskId, c);
        }

        return clusters;
    }
}