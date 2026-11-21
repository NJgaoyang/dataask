package com.dataask.security;

import com.dataask.config.RuntimeConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

@Service
public class CityPermissionService {
    static final String SQL =
        "SELECT DISTINCT city_name FROM ads.ads_user_city_permission " +
        "WHERE username_en=? AND is_del=0 AND city_name IS NOT NULL " +
        "AND TRIM(city_name)<>'' ORDER BY city_name LIMIT 1001";

    private final RuntimeConfigService settings;
    private final JdbcTemplate jdbc;

    @Autowired
    public CityPermissionService(RuntimeConfigService settings, JdbcTemplate jdbc) {
        this.settings=settings;this.jdbc=jdbc;
    }
    public CityPermissionService(RuntimeConfigService settings) {
        this(settings,null);
    }

    public record CityGrant(List<String> availableCities,List<String> selectedCities) {}

    public List<String> availableCities(String username) {
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

    public CityGrant grant(String username) {
        List<String> available=availableCities(username);
        if(jdbc==null || available.isEmpty()) return new CityGrant(available,available);
        Set<String> excluded=new HashSet<>(jdbc.queryForList(
            "SELECT city_name FROM da_user_city_exclusions WHERE username=?",
            String.class,username));
        List<String> selected=available.stream().filter(city->!excluded.contains(city)).toList();
        return new CityGrant(available,selected);
    }

    public List<String> cities(String username) {
        return grant(username).selectedCities();
    }

    public List<String> requireCities(String username){
        List<String> found=cities(username);
        if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "当前账号没有有效的城市数据权限");
        return found;
    }

    @Transactional
    public CityGrant saveSelectedCities(String username,Collection<String> selectedCities) {
        if(jdbc==null) throw new IllegalStateException("城市选择持久化不可用");
        List<String> available=availableCities(username);
        LinkedHashSet<String> selected=new LinkedHashSet<>();
        if(selectedCities!=null) {
            for(String value:selectedCities) {
                String city=value==null?"":value.strip();
                if(!city.isEmpty()) selected.add(city);
            }
        }
        if(!new HashSet<>(available).containsAll(selected))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"选择中包含 ADS 未授权的城市");

        jdbc.update("DELETE FROM da_user_city_exclusions WHERE username=?",username);
        for(String city:available) {
            if(!selected.contains(city))
                jdbc.update("INSERT INTO da_user_city_exclusions(username,city_name) VALUES(?,?)",username,city);
        }
        return new CityGrant(available,available.stream().filter(selected::contains).toList());
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
