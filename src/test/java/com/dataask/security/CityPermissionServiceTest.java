package com.dataask.security;

import com.dataask.config.RuntimeConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CityPermissionServiceTest {
    private final CityPermissionService service=new CityPermissionService(mock(RuntimeConfigService.class));

    @Test void usesUsernameEnAndActiveGrantsOnly() {
        assertThat(CityPermissionService.SQL).contains("FROM ads.ads_user_city_permission")
            .contains("username_en=?").contains("is_del=0").contains("DISTINCT city_name")
            .doesNotContain("WHERE phone=");
    }
    @Test void noImplicitGrantsForOtherLoginIdentifiers() {
        assertThat(service.cities("admin")).isEmpty();
        assertThatThrownBy(()->service.requireCities("admin"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e->assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }
    @Test void endpointQueriesOnlyAuthenticatedPrincipal() {
        CityPermissionService cities=mock(CityPermissionService.class);
        when(cities.cities("13800138000")).thenReturn(List.of("北京市","上海市"));
        var auth=new UsernamePasswordAuthenticationToken("13800138000",null,
            List.of(new SimpleGrantedAuthority("ROLE_USER")));
        AuthController controller=new AuthController(mock(org.springframework.jdbc.core.JdbcTemplate.class),
            mock(org.springframework.security.crypto.password.PasswordEncoder.class),mock(JwtTokens.class),cities);
        Map<String,Object> data=controller.cityPermissions(auth);
        assertThat(data.get("cities")).isEqualTo(List.of("北京市","上海市"));
        assertThat(data.get("count")).isEqualTo(2);
        verify(cities).cities("13800138000");
    }
}
