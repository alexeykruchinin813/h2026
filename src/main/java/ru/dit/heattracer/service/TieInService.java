package ru.dit.heattracer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.dit.heattracer.model.OksCluster;
import ru.dit.heattracer.model.TieInCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TieInService {

    private static final Logger log = LoggerFactory.getLogger(TieInService.class);

    private static final double DEFAULT_RADIUS_M = 1000.0;
    private static final int MAX_CANDIDATES_PER_CLUSTER = 20;

    private final JdbcTemplate jdbc;

    @Autowired
    public TieInService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TieInCandidate> findCandidates(UUID taskId, OksCluster cluster) {
        return findCandidates(taskId, cluster, DEFAULT_RADIUS_M);
    }

    public List<TieInCandidate> findCandidates(UUID taskId, OksCluster cluster, double radiusM) {
        long start = System.currentTimeMillis();

        Double centroidLon = cluster.getCentroidLon();
        Double centroidLat = cluster.getCentroidLat();

        // 1. Удаляем старых кандидатов для этого кластера
        jdbc.update(
                "DELETE FROM tie_in_candidate WHERE task_id = ? AND cluster_id = ?",
                taskId, cluster.getClusterId());

        // 2. Ищем кандидатов
        List<TieInCandidate> candidates = new ArrayList<>();

        jdbc.query(
                "WITH centroid AS ( " +
                        "  SELECT ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 32637) AS geom " +
                        ") " +
                        "SELECT c.candidate_type, c.existing_object_id, c.distance_m, " +
                        "       c.existing_diameter, c.required_diameter, " +
                        "       ST_X(ST_Transform(c.geom, 4326)) AS lon, " +
                        "       ST_Y(ST_Transform(c.geom, 4326)) AS lat, " +
                        "       ST_AsText(c.geom) AS wkt_utm " +
                        "FROM centroid, find_tie_in_candidates(?, centroid.geom, ?) c " +
                        "LIMIT ?",
                rs -> {
                    TieInCandidate c = new TieInCandidate();
                    c.setCandidateType(rs.getString("candidate_type"));
                    c.setExistingObjectId(rs.getString("existing_object_id"));
                    c.setDistanceM(rs.getDouble("distance_m"));
                    c.setExistingDiameter(rs.getInt("existing_diameter"));
                    c.setRequiredDiameter(rs.getInt("required_diameter"));
                    c.setLon(rs.getDouble("lon"));
                    c.setLat(rs.getDouble("lat"));
                    candidates.add(c);
                },
                centroidLon, centroidLat,
                taskId, radiusM,
                MAX_CANDIDATES_PER_CLUSTER
        );

        // 3. Сохраняем кандидатов в БД
        for (TieInCandidate c : candidates) {
            jdbc.update(
                    "INSERT INTO tie_in_candidate " +
                            "  (task_id, cluster_id, existing_object_id, existing_object_type, " +
                            "   geom, required_diameter) " +
                            "VALUES (?, ?, ?, ?, " +
                            "        ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 32637), 0)",
                    taskId,
                    cluster.getClusterId(),
                    c.getExistingObjectId(),
                    c.getCandidateType(),
                    c.getLon(),
                    c.getLat());
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[{}] Cluster {}: {} tie-in candidates persisted (radius {}m, {} ms)",
                taskId, cluster.getClusterId(), candidates.size(), radiusM, elapsed);

        return candidates;
    }
}