package com.dataask.security;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RolePermissionServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final RolePermissionService service=new RolePermissionService(jdbc);

    @Test void cityManagerIsTheOnlyBuiltInCityScopedRole() {
        when(jdbc.queryForList("SELECT code,name FROM da_roles WHERE code=? AND enabled=1","CITY_MANAGER"))
            .thenReturn(List.of(Map.of("code","CITY_MANAGER","name","城市经理")));
        when(jdbc.queryForList(
            "SELECT permission_code FROM da_role_permissions WHERE role_code=? ORDER BY permission_code",
            String.class,"CITY_MANAGER")).thenReturn(List.of("ASK","CHARTS"));
        var authorities=service.authorities("CITY_MANAGER");
        assertThat(authorities).extracting(Object::toString)
            .contains("ROLE_CITY_MANAGER","PERM_ASK","PERM_CHARTS","SCOPE_CITY");
    }

    @Test void administratorAlwaysReceivesAllPermissionsAndNoCityScope() {
        when(jdbc.queryForList("SELECT code,name FROM da_roles WHERE code=? AND enabled=1","ADMIN"))
            .thenReturn(List.of(Map.of("code","ADMIN","name","管理员")));
        var access=service.accessForRole("ADMIN");
        assertThat(access.cityScoped()).isFalse();
        assertThat(access.permissions()).containsExactlyInAnyOrder("ASK","CHARTS","SYSTEM_ADMIN");
        assertThat(service.authorities("ADMIN")).extracting(Object::toString)
            .contains("PERM_SYSTEM_ADMIN").doesNotContain("SCOPE_CITY");
    }

    @Test void customRoleUsesConfiguredPermissionsAndAllCityScope() {
        when(jdbc.queryForList("SELECT code,name FROM da_roles WHERE code=? AND enabled=1","FINANCE"))
            .thenReturn(List.of(Map.of("code","FINANCE","name","财务")));
        when(jdbc.queryForList(
            "SELECT permission_code FROM da_role_permissions WHERE role_code=? ORDER BY permission_code",
            String.class,"FINANCE")).thenReturn(List.of("CHARTS"));
        var access=service.accessForRole("FINANCE");
        assertThat(access.cityScoped()).isFalse();
        assertThat(access.permissions()).containsExactly("CHARTS");
    }
}
