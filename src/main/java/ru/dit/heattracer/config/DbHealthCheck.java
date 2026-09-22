package ru.dit.heattracer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DbHealthCheck {

    private static final Logger log = LoggerFactory.getLogger(DbHealthCheck.class);

    private final JdbcTemplate jdbc;

    public DbHealthCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        try {
            String pgVersion = jdbc.queryForObject("SELECT version()", String.class);
            String postgisVersion = jdbc.queryForObject("SELECT PostGIS_Version()", String.class);
            String pgRoutingVersion = jdbc.queryForObject(
                    "SELECT pgr_version()", String.class);

            log.info("=== DB CONNECTION OK ===");
            log.info("PostgreSQL : {}", shortVersion(pgVersion));
            log.info("PostGIS    : {}", postgisVersion);
            log.info("pgRouting  : {}", pgRoutingVersion);
            log.info("========================");
        } catch (Exception e) {
            log.error("=== DB CONNECTION FAILED ===");
            log.error("Error: {}", e.getMessage());
            log.error("Проверьте, что контейнер db запущен и расширения созданы.");
        }
    }

    private String shortVersion(String full) {
        if (full == null) return "unknown";
        int comma = full.indexOf(',');
        return comma > 0 ? full.substring(0, comma) : full;
    }
}