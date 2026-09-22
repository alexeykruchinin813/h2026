package ru.dit.heattracer.io;

import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;

@Service
public class CopyService {

    private static final Logger log = LoggerFactory.getLogger(CopyService.class);

    private final DataSource dataSource;

    public CopyService(DataSource dataSource) {
        this.dataSource = dataSource;
    }
    public long copyIn(String tableName, String columns, Reader reader) {
        long start = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection()) {
            BaseConnection pgConn = conn.unwrap(BaseConnection.class);
            CopyManager copyManager = new CopyManager(pgConn);

            // TEXT-формат: поля через \t, строки через \n, NULL = \N
            String sql = "COPY " + tableName + " " + columns + " FROM STDIN " +
                    "WITH (FORMAT text)";

            long rows = copyManager.copyIn(sql, reader);
            long elapsed = System.currentTimeMillis() - start;
            log.info("COPY {} rows into {} in {} ms", rows, tableName, elapsed);
            return rows;
        } catch (SQLException | java.io.IOException e) {
            throw new RuntimeException("COPY failed for table " + tableName, e);
        }
    }
}