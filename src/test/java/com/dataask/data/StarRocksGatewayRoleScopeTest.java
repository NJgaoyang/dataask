package com.dataask.data;

import com.dataask.config.RuntimeConfigService;
import com.dataask.security.CityPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StarRocksGatewayRoleScopeTest {
    private final RuntimeConfigService settings=mock(RuntimeConfigService.class);
    private final CityPermissionService cities=mock(CityPermissionService.class);
    private final CityRowGuard rowGuard=mock(CityRowGuard.class);
    private final StarRocksGateway gateway=new StarRocksGateway(settings,cities,rowGuard);

    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void administratorDoesNotReadCityPermissionTable() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "admin",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("PERM_SYSTEM_ADMIN"))));
        assertThat(gateway.cityPermissionFingerprint("admin")).isNull();
        assertThat(gateway.managedCities("admin")).isEmpty();
        verifyNoInteractions(cities);
    }

    @Test void cityManagerUsesCityPermissionFingerprintAndManagedCities() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "13800138000",null,List.of(new SimpleGrantedAuthority("ROLE_CITY_MANAGER"),
                new SimpleGrantedAuthority("SCOPE_CITY"))));
        when(cities.fingerprint("13800138000")).thenReturn("city-hash");
        when(cities.cities("13800138000")).thenReturn(List.of("杭州市","武汉市"));
        assertThat(gateway.cityPermissionFingerprint("13800138000")).isEqualTo("city-hash");
        assertThat(gateway.managedCities("13800138000")).containsExactly("杭州市","武汉市");
    }

    @Test void ordinaryUserAlsoUsesCityPermissionScope() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "13900139000",null,List.of(new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("SCOPE_CITY"))));
        when(cities.fingerprint("13900139000")).thenReturn("user-city-hash");
        assertThat(gateway.cityPermissionFingerprint("13900139000")).isEqualTo("user-city-hash");
        verify(cities).fingerprint("13900139000");
    }
}
