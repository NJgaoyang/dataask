package com.dataask.security;

import com.dataask.config.RuntimeConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/**
 * Resolve current city grants by matching ads.ads_user_city_permission.username_en
 * to the authenticated phone-number login (da_users.username).
 * The phone field of the ADS permission table is deliberately not used.
 */
@Service
public class CityPermissionService {
    static final String SQL =
        "SELECT DISTINCT city_name FROM ads.ads_user_city_permission " +
        "WHERE username_en=? AND is_del=0 AND city_name IS NOT NULL " +
        "AND TRIM(city_name)<>'' ORDER BY city_name LIMIT 1001";

    private final RuntimeConfigService settings;
    public CityPermissionService(RuntimeConfigService settings) { this.settings=settings; }

    public List<String> cities(String username) {
        if(username==null || !username.matches("1[3-9][0-9]{9}")) return List.of();
        var cfg=settings.starRocks();
        if(!cfg.configured()) throw unavailable();
        Properties p=new Properties();
        p.setProperty("user",cfg.username());p.setProperty("password",cfg.password());
        p.setProperty("connectTimeout","5000");p.setProperty("socketTimeout","15000");
        p.setProperty("allowMultiQueries","false");p.setProperty("allowLoadLocalInfile","false");
        SortedSet<String> found=new TreeSet<>();
        try(Connection c=DriverManager.getConnection(cfg.jdbcUrl(),p);
            PreparedStatement ps=c.prepareStatement(SQL)) {
            ps.setString(1,username);
            ps.setQueryTimeout(10);
            try(ResultSet rs=ps.executeQuery()) {
                while(rs.next()){
                    String name=rs.getString(1);
                    if(name!=null&&!name.isBlank()&&name.length()<=255) found.add(name.strip());
                    if(found.size()>1000) throw unavailable();
                }
            }
        } catch(SQLException e){
            org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                "City permission lookup error: SQLState={} code={}",e.getSQLState(),e.getErrorCode());
            throw unavailable();
        }
        return List.copyOf(found);
    }
    public List<String> requireCities(String username){
        List<String> found=cities(username);
        if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "该登录手机号没有有效的管理城市权限");
        return found;
    }
    public String fingerprint(String username) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(
                (username+"\n"+String.join("\n",requireCities(username))).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static ResponseStatusException unavailable(){
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "无法读取 ads.ads_user_city_permission，请检查 StarRocks 数据源及权限");
    }
}
