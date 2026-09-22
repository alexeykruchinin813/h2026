-- Обновляем таблицу правил ограничений в соответствии с Таблицей 2 актуального ТЗ.

-- 1. Удаляем устаревшее правило (тип oks_existing больше не используется)
DELETE FROM restriction_rules WHERE type = 'oks_existing';

-- 2. Добавляем колонки для правил с зависимостью от ДУ
ALTER TABLE restriction_rules
ADD COLUMN IF NOT EXISTS min_dist_lt_500   NUMERIC(6,2),
ADD COLUMN IF NOT EXISTS min_dist_500_800  NUMERIC(6,2),
ADD COLUMN IF NOT EXISTS min_dist_ge_900   NUMERIC(6,2);

-- 3. Обновляем существующие правила (если изменились)
UPDATE restriction_rules SET
crossing_forbidden   = FALSE,
special_pass_allowed = TRUE,
min_horizontal_dist  = 1.5,
crossing_angle_max   = 45,
special_k            = 1.60,
special_zone_buffer  = 3.0,
depth_rule           = 'under_1.0m'
WHERE type = 'road';

UPDATE restriction_rules SET
crossing_forbidden   = FALSE,
special_pass_allowed = TRUE,
min_horizontal_dist  = 1.5,
crossing_angle_max   = 45,
special_k            = 1.75,
special_zone_buffer  = 3.0,
depth_rule           = 'under_1.2m'
WHERE type = 'tram_tracks';

UPDATE restriction_rules SET
crossing_forbidden   = FALSE,
special_pass_allowed = TRUE,
min_horizontal_dist  = 2.0,
crossing_angle_max   = NULL,
special_k            = 1.25,
special_zone_buffer  = 2.0,
depth_rule           = 'above_or_below_0.2m'
WHERE type = 'gas_pipeline';

UPDATE restriction_rules SET
crossing_forbidden   = FALSE,
special_pass_allowed = TRUE,
min_horizontal_dist  = 2.0,
crossing_angle_max   = NULL,
special_k            = 1.15,
special_zone_buffer  = 2.0,
depth_rule           = 'above_or_below_0.5m'
WHERE type = 'power_cable';

UPDATE restriction_rules SET
crossing_forbidden   = FALSE,
special_pass_allowed = TRUE,
min_horizontal_dist  = 1.0,
crossing_angle_max   = NULL,
special_k            = 1.05,
special_zone_buffer  = 2.0,
depth_rule           = 'above_or_below_0.5m'
WHERE type = 'heat_network';

UPDATE restriction_rules SET
crossing_forbidden   = TRUE,
special_pass_allowed = FALSE,
min_horizontal_dist  = 1.0,
crossing_angle_max   = NULL,
special_k            = NULL,
special_zone_buffer  = NULL,
depth_rule           = NULL
WHERE type IN ('water', 'park', 'social_area', 'prohibited_site', 'railway');

-- 4. Добавляем новые правила (если их нет)

-- park
INSERT INTO restriction_rules (
    type, crossing_forbidden, special_pass_allowed,
    min_horizontal_dist, crossing_angle_max, special_k, special_zone_buffer, depth_rule
) VALUES (
    'park', TRUE, FALSE, 1.0, NULL, NULL, NULL, NULL
) ON CONFLICT (type) DO NOTHING;

-- social_area
INSERT INTO restriction_rules (
    type, crossing_forbidden, special_pass_allowed,
    min_horizontal_dist, crossing_angle_max, special_k, special_zone_buffer, depth_rule
) VALUES (
    'social_area', TRUE, FALSE, 1.0, NULL, NULL, NULL, NULL
) ON CONFLICT (type) DO NOTHING;

-- prohibited_site
INSERT INTO restriction_rules (
    type, crossing_forbidden, special_pass_allowed,
    min_horizontal_dist, crossing_angle_max, special_k, special_zone_buffer, depth_rule
) VALUES (
    'prohibited_site', TRUE, FALSE, 1.0, NULL, NULL, NULL, NULL
) ON CONFLICT (type) DO NOTHING;

-- railway
INSERT INTO restriction_rules (
    type, crossing_forbidden, special_pass_allowed,
    min_horizontal_dist, crossing_angle_max, special_k, special_zone_buffer, depth_rule
) VALUES (
    'railway', TRUE, FALSE, 1.0, NULL, NULL, NULL, NULL
) ON CONFLICT (type) DO NOTHING;

-- oks — особый случай: отступ зависит от ДУ новой сети
-- min_horizontal_dist здесь NULL, потому что значение выбирается динамически
INSERT INTO restriction_rules (
    type, crossing_forbidden, special_pass_allowed,
    min_horizontal_dist, crossing_angle_max, special_k, special_zone_buffer, depth_rule,
    min_dist_lt_500, min_dist_500_800, min_dist_ge_900
) VALUES (
    'oks', TRUE, FALSE, NULL, NULL, NULL, NULL, NULL,
    5.0, 7.0, 9.0
) ON CONFLICT (type) DO UPDATE SET
crossing_forbidden   = EXCLUDED.crossing_forbidden,
special_pass_allowed = EXCLUDED.special_pass_allowed,
min_dist_lt_500      = EXCLUDED.min_dist_lt_500,
min_dist_500_800     = EXCLUDED.min_dist_500_800,
min_dist_ge_900      = EXCLUDED.min_dist_ge_900;

-- 5. Убеждаемся, что water осталась с правильными значениями
INSERT INTO restriction_rules (
    type, crossing_forbidden, special_pass_allowed,
    min_horizontal_dist, crossing_angle_max, special_k, special_zone_buffer, depth_rule
) VALUES (
    'water', TRUE, FALSE, 1.0, NULL, NULL, NULL, NULL
) ON CONFLICT (type) DO UPDATE SET
crossing_forbidden   = EXCLUDED.crossing_forbidden,
special_pass_allowed = EXCLUDED.special_pass_allowed,
min_horizontal_dist  = EXCLUDED.min_horizontal_dist;