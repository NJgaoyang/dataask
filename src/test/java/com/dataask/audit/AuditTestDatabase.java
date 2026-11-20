package com.dataask.audit;

import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.UUID;

public final class AuditTestDatabase {
    private AuditTestDatabase() {}
    public static DataSource create() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:audit_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        // Upgrades must be idempotent as Spring executes this schema on each boot.
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        return ds;
    }
}
