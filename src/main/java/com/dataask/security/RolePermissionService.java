package com.dataask.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.*;

@Service
public class RolePermissionService {
    public static final String ASK="ASK";
    public static final String CHARTS="CHARTS";
    public static final String SYSTEM_ADMIN="SYSTEM_ADMIN";
    public static final Set<String> ALL_PERMISSIONS=Set.of(ASK,CHARTS,SYSTEM_ADMIN);

    private final JdbcTemplate jdbc;
    public RolePermissionService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record Access(String code,String name,boolean cityScoped,Set<String> permissions){}

    public Access accessForRole(String code){
        if(code==null || !code.matches("[A-Z][A-Z0-9_]{1,15}"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"账号角色无效");
        List<Map<String,Object>> rows=jdbc.queryForList(
            "SELECT code,name FROM da_roles WHERE code=? AND enabled=1",code);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"账号角色已停用或不存在");
        String name=Objects.toString(rows.getFirst().get("name"),code);
        LinkedHashSet<String> permissions=new LinkedHashSet<>();
        if("ADMIN".equals(code)) permissions.addAll(List.of(ASK,CHARTS,SYSTEM_ADMIN));
        else permissions.addAll(jdbc.queryForList(
            "SELECT permission_code FROM da_role_permissions WHERE role_code=? ORDER BY permission_code",
            String.class,code));
        permissions.retainAll(ALL_PERMISSIONS);
        return new Access(code,name,!"ADMIN".equals(code),Collections.unmodifiableSet(permissions));
    }

    public Access accessForUser(String username){
        List<String> roles=jdbc.query(
            "SELECT role FROM da_users WHERE username=?",(rs,n)->rs.getString(1),username);
        if(roles.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"账号不存在");
        return accessForRole(roles.getFirst());
    }

    public boolean roleExists(String code){
        if(code==null) return false;
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM da_roles WHERE code=? AND enabled=1",Integer.class,code);
        return count!=null && count>0;
    }

    public List<GrantedAuthority> authorities(String code){
        Access access=accessForRole(code);
        List<GrantedAuthority> result=new ArrayList<>();
        result.add(new SimpleGrantedAuthority("ROLE_"+access.code()));
        for(String permission:access.permissions()) result.add(new SimpleGrantedAuthority("PERM_"+permission));
        if(access.cityScoped()) result.add(new SimpleGrantedAuthority("SCOPE_CITY"));
        return List.copyOf(result);
    }
}
