package com.dataask.mini;

import com.dataask.chat.ChatController;
import com.dataask.data.SqlGuard;
import com.dataask.data.StarRocksGateway;
import com.dataask.security.CityPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CityPermissionBootstrapTest {
    @Test void miniappReturnsOnlyCityGrantsBelongingToAuthenticatedPhone() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        CityPermissionService permissions=mock(CityPermissionService.class);
        String phone="13800138000";
        when(jdbc.queryForMap("SELECT username,display_name,role FROM da_users WHERE username=?",phone))
            .thenReturn(Map.of("username",phone,"display_name","城市经理","role","USER"));
        when(permissions.cities(phone)).thenReturn(List.of("上海市","杭州市"));
        var controller=new MiniappController(jdbc,mock(ChatController.class),
            new ObjectMapper(),mock(SqlGuard.class),mock(StarRocksGateway.class),permissions);
        var auth=new UsernamePasswordAuthenticationToken(phone,null,
            List.of(new SimpleGrantedAuthority("ROLE_USER")));
        @SuppressWarnings("unchecked")
        Map<String,Object> user=(Map<String,Object>)controller.bootstrap(auth).get("user");
        assertThat(user.get("cities")).isEqualTo(List.of("上海市","杭州市"));
        assertThat(user.get("cityPermissionCount")).isEqualTo(2);
        verify(permissions).cities(phone);
    }
}
