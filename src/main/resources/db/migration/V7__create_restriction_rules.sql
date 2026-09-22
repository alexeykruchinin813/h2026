CREATE TABLE restriction_rules (
    type                   TEXT PRIMARY KEY,
    crossing_forbidden     BOOLEAN NOT NULL,
    special_pass_allowed   BOOLEAN NOT NULL,
    min_horizontal_dist    NUMERIC(6,2),
    crossing_angle_max     NUMERIC(6,2),
    special_k              NUMERIC(6,3),
    special_zone_buffer    NUMERIC(6,2),
    depth_rule             TEXT
);

INSERT INTO restriction_rules VALUES
('road',          FALSE, TRUE,  1.5, 45, 1.60, 3.0, 'under_1.0m'),
('tram_tracks',   FALSE, TRUE,  1.5, 45, 1.75, 3.0, 'under_1.2m'),
('gas_pipeline',  FALSE, TRUE,  2.0, NULL, 1.25, 2.0, 'above_or_below_0.2m'),
('power_cable',   FALSE, TRUE,  2.0, NULL, 1.15, 2.0, 'above_or_below_0.5m'),
('heat_network',  FALSE, TRUE,  1.0, NULL, 1.05, 2.0, 'above_or_below_0.5m'),
('water',         TRUE,  FALSE, 1.0, NULL, NULL, NULL, NULL),
('park',          TRUE,  FALSE, 1.0, NULL, NULL, NULL, NULL),
('social_area',   TRUE,  FALSE, 1.0, NULL, NULL, NULL, NULL),
('prohibited_site', TRUE, FALSE, 1.0, NULL, NULL, NULL, NULL),
('oks_existing',  TRUE,  FALSE, 5.0, NULL, NULL, NULL, NULL);