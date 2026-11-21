package com.dataask.admin;

import com.dataask.audit.AuditTestDatabase;
import com.dataask.config.RuntimeConfigService;
import com.dataask.data.StarRocksGateway;
import com.dataask.semantic.EmbeddingSemanticService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminRolesQueryTest {
    @Test
    void builtInAndCustomRolesLoadWithPermissionsAndCorrectUserCounts() {
        JdbcTemplate jdbc=new JdbcTemplate(AuditTestDatabase.create());
        jdbc.update("INSERT INTO da_users(username,display_name,password_hash,role) VALUES(?,?,?,?)",
            "admin","管理员","hash","ADMIN");
        jdbc.update("INSERT INTO da_users(username,display_name,password_hash,role) VALUES(?,?,?,?)",
            "alice","Alice","hash","USER");
        jdbc.update("INSERT INTO da_roles(code,name,built_in,enabled) VALUES('FINANCE','财务',0,1)");
        jdbc.update("INSERT INTO da_role_permissions(role_code,permission_code) VALUES('FINANCE','CHARTS')");
        AdminController controller=new AdminController(mock(StarRocksGateway.class),
            mock(RuntimeConfigService.class),jdbc,mock(PasswordEncoder.class),
            new ObjectMapper(),mock(EmbeddingSemanticService.class));
        List<Map<String,Object>> results=controller.roles();
        assertThat(results).extracting(row->row.get("code"))
            .contains("ADMIN","CITY_MANAGER","USER","FINANCE");
        Map<String,Object> admin=results.stream().filter(row->row.get("code").equals("ADMIN"))
            .findFirst().orElseThrow();
        assertThat(admin.get("userCount")).isEqualTo(1L);
        assertThat(admin.get("cityScoped")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        var finance=(Map<String,Object>)results.stream()
            .filter(row->row.get("code").equals("FINANCE")).findFirst().orElseThrow();
        assertThat(finance.get("permissions")).isEqualTo(java.util.Set.of("CHARTS"));
        assertThat(finance.get("userCount")).isEqualTo(0L);
    }
}
