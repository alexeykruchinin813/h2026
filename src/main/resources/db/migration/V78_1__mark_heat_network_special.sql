DROP FUNCTION IF EXISTS mark_heat_network_special(UUID);
DROP FUNCTION IF EXISTS mark_heat_network_special(UUID, INT);

CREATE OR REPLACE FUNCTION mark_heat_network_special(
    p_task_id    UUID,
    p_cluster_id INT
) RETURNS INT AS $$
DECLARE
n INT := 0;
BEGIN
UPDATE visibility_edge ve
SET is_special = TRUE,
special_k  = GREATEST(COALESCE(ve.special_k, 1.0), 1.05)
FROM input_feature f
WHERE ve.task_id    = p_task_id
AND ve.cluster_id = p_cluster_id
AND f.task_id     = ve.task_id
AND f.object_type = 'heat_network'
AND f.geom_utm IS NOT NULL
AND ST_Crosses(ve.geom, f.geom_utm);
GET DIAGNOSTICS n = ROW_COUNT;
RETURN n;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION mark_heat_network_special(UUID, INT) IS
'V78.1: помечает рёбра visibility_edge указанного кластера, пересекающие
существующую теплосеть, как special с K=1.05 (разъяснение 10 ТЗ).
Параметр cluster_id — защита от гонки между параллельными потоками
в HybridVisibilityGraphService.buildHybrid.';