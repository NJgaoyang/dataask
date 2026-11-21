package com.dataask.admin;

import com.dataask.audit.AuditContext;
import com.dataask.security.CityPermissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasAuthority('PERM_SYSTEM_ADMIN')")
public class AdminCityPermissionController {
    private final JdbcTemplate jdbc;
    private final CityPermissionService cities;

    public AdminCityPermissionController(JdbcTemplate jdbc,CityPermissionService cities) {
        this.jdbc=jdbc;this.cities=cities;
    }

    record CitySelectionRequest(@NotNull @Size(max=1000) List<String> selectedCities) {}

    @GetMapping("/{username}/city-permissions")
    Map<String,Object> cityPermissions(@PathVariable String username,
            @RequestParam(required=false) String role) {
        requireUsername(username);
        String effectiveRole=(role==null || role.isBlank())
            ? storedRole(username) : role.strip().toUpperCase(Locale.ROOT);
        if("ADMIN".equals(effectiveRole))
            return response(username,effectiveRole,true,List.of(),List.of());

        CityPermissionService.CityGrant grant=cities.grant(username);
        return response(username,effectiveRole,false,grant.availableCities(),grant.selectedCities());
    }

    @PutMapping("/{username}/city-permissions")
    @Transactional
    Map<String,Object> updateCityPermissions(@PathVariable String username,
            @Valid @RequestBody CitySelectionRequest input) {
        requireUsername(username);
        String role=storedRole(username);
        if("ADMIN".equals(role))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"管理员默认拥有全部城市，不配置城市筛选");

        CityPermissionService.CityGrant grant=cities.saveSelectedCities(username,input.selectedCities());
        AuditContext.set("target","用户："+username);
        AuditContext.set("detail","城市权限："+grant.selectedCities().size()+"/"+grant.availableCities().size()+" 个城市");
        return response(username,role,false,grant.availableCities(),grant.selectedCities());
    }

    private String storedRole(String username) {
        List<String> found=jdbc.query(
            "SELECT role FROM da_users WHERE username=?",(rs,n)->rs.getString(1),username);
        if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"用户不存在");
        return found.getFirst();
    }

    private static Map<String,Object> response(String username,String role,boolean allCities,
            List<String> available,List<String> selected) {
        return Map.of("username",username,"role",role,"allCities",allCities,
            "scope",allCities?"ALL":"CITY","availableCities",available,
            "selectedCities",selected,"availableCount",available.size(),"selectedCount",selected.size(),
            "source",allCities?"role":"ads.ads_user_city_permission");
    }

    private static void requireUsername(String username) {
        if(username==null || !username.matches("[A-Za-z0-9_-]{3,64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"账号格式不正确");
    }
}
