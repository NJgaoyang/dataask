package com.dataask.admin;

import com.dataask.security.CityPermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminCityPermissionControllerTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final CityPermissionService cities=mock(CityPermissionService.class);
    private final AdminCityPermissionController controller=
        new AdminCityPermissionController(jdbc,cities);

    @Test void adminPreviewNeverReadsAdsCityPermissions() {
        Map<String,Object> result=controller.cityPermissions("admin","ADMIN");
        assertThat(result.get("allCities")).isEqualTo(true);
        assertThat(result.get("scope")).isEqualTo("ALL");
        verifyNoInteractions(cities,jdbc);
    }

    @Test void nonAdminPreviewReturnsDistinctAvailableAndSelectedCities() {
        when(cities.grant("13800138000")).thenReturn(new CityPermissionService.CityGrant(
            List.of("上海市","武汉市","郑州市"),List.of("武汉市","郑州市")));
        Map<String,Object> result=controller.cityPermissions("13800138000","USER");
        assertThat(result.get("availableCities")).isEqualTo(List.of("上海市","武汉市","郑州市"));
        assertThat(result.get("selectedCities")).isEqualTo(List.of("武汉市","郑州市"));
        assertThat(result.get("scope")).isEqualTo("CITY");
    }

    @Test void savesOnlyForExistingNonAdminUser() {
        when(jdbc.query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),eq("13800138000")))
            .thenReturn(List.of("CITY_MANAGER"));
        when(cities.saveSelectedCities("13800138000",List.of("武汉市")))
            .thenReturn(new CityPermissionService.CityGrant(List.of("武汉市","郑州市"),List.of("武汉市")));
        Map<String,Object> result=controller.updateCityPermissions("13800138000",
            new AdminCityPermissionController.CitySelectionRequest(List.of("武汉市")));
        assertThat(result.get("selectedCount")).isEqualTo(1);
        verify(cities).saveSelectedCities("13800138000",List.of("武汉市"));
    }

    @Test void administratorCannotPersistCitySelections() {
        when(jdbc.query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),eq("admin")))
            .thenReturn(List.of("ADMIN"));
        assertThatThrownBy(() -> controller.updateCityPermissions("admin",
            new AdminCityPermissionController.CitySelectionRequest(List.of())))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(cities);
    }
}
