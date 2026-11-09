package com.dataask.security;

import com.dataask.config.InstallationSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean SecurityFilterChain filterChain(HttpSecurity http, JwtFilter jwt) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers("/api/health", "/api/build-info").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated().anyRequest().permitAll())
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> res.sendError(401)))
            .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    @Bean CommandLineRunner initialAdmin(JdbcTemplate jdbc, PasswordEncoder encoder,
            @Value("${dataask.admin-password:${DATAASK_ADMIN_PASSWORD:}}") String initial) {
        return args -> {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM da_users", Integer.class);
            if (count != null && count == 0) {
                boolean generated = initial == null || initial.isBlank();
                String password = generated ? InstallationSettings.randomSecret().substring(0, 20) : initial;
                if (password.length() < 10 || password.getBytes(StandardCharsets.UTF_8).length > 72)
                    throw new IllegalStateException("首次管理员密码须为至少10字符、至多72字节");
                jdbc.update("INSERT INTO da_users(username,display_name,password_hash,role) VALUES(?,?,?,?)",
                    "admin", "系统管理员", encoder.encode(password), "ADMIN");
                if (generated) org.slf4j.LoggerFactory.getLogger(SecurityConfig.class)
                    .warn("首次安装管理员账号 admin，初始密码（仅输出本次）: {}", password);
            }
        };
    }
}

@Component
class JwtTokens {
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private final ObjectMapper mapper;
    private final String secret;
    JwtTokens(ObjectMapper mapper, InstallationSettings installation) {
        this.mapper = mapper; this.secret = installation.signingKey();
    }
    String issue(String username, String role) {
        try {
            String head = ENC.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String body = ENC.encodeToString(mapper.writeValueAsBytes(Map.of(
                "sub", username, "role", role, "exp", Instant.now().plusSeconds(43200).getEpochSecond())));
            String signing = head + "." + body;
            return signing + "." + ENC.encodeToString(hmac(signing));
        } catch (Exception e) { throw new IllegalStateException("令牌签发失败", e); }
    }
    Identity read(String value) {
        try {
            if (value == null || value.length() > 4096) return null;
            String[] parts = value.split("\\.", -1);
            if (parts.length != 3) return null;
            if (!"HS256".equals(mapper.readTree(DEC.decode(parts[0])).path("alg").asText())) return null;
            if (!MessageDigest.isEqual(hmac(parts[0] + "." + parts[1]), DEC.decode(parts[2]))) return null;
            JsonNode payload = mapper.readTree(DEC.decode(parts[1]));
            String username = payload.path("sub").asText(""), role = payload.path("role").asText("");
            if (username.isBlank() || !(role.equals("USER") || role.equals("ADMIN")) ||
                payload.path("exp").asLong(0) <= Instant.now().getEpochSecond()) return null;
            return new Identity(username, role);
        } catch (Exception e) { return null; }
    }
    private byte[] hmac(String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
    }
    record Identity(String username, String role) {}
}

@Component
class JwtFilter extends OncePerRequestFilter {
    private final JwtTokens tokens;
    private final JdbcTemplate jdbc;
    JwtFilter(JwtTokens tokens, JdbcTemplate jdbc) { this.tokens = tokens; this.jdbc = jdbc; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            JwtTokens.Identity id = tokens.read(auth.substring(7));
            if (id != null) {
                List<String> roles = jdbc.query("SELECT role FROM da_users WHERE username=?", (rs, n) -> rs.getString(1), id.username());
                if (!roles.isEmpty() && roles.getFirst().equals(id.role())) {
                    Authentication principal = new UsernamePasswordAuthenticationToken(id.username(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + id.role())));
                    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(principal);
                }
            }
        }
        chain.doFilter(request, response);
    }
}

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final JwtTokens tokens;
    AuthController(JdbcTemplate jdbc, PasswordEncoder passwords, JwtTokens tokens) {
        this.jdbc = jdbc; this.passwords = passwords; this.tokens = tokens;
    }
    record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    @PostMapping("/login") Map<String,Object> login(@Valid @RequestBody LoginRequest input) {
        List<Map<String,Object>> users = jdbc.queryForList("SELECT username,display_name,password_hash,role FROM da_users WHERE username=?", input.username());
        if (users.isEmpty() || !passwords.matches(input.password(), (String) users.getFirst().get("password_hash")))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        Map<String,Object> user = users.getFirst();
        return Map.of("token", tokens.issue((String) user.get("username"), (String) user.get("role")),
            "username", user.get("username"), "displayName", user.get("display_name"), "role", user.get("role"));
    }
    @GetMapping("/me") Map<String,String> me(Authentication auth) {
        return Map.of("username", auth.getName(), "role",
            auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())) ? "ADMIN" : "USER");
    }
    record ChangePassword(@NotBlank String currentPassword, @NotBlank String newPassword) {}
    @PostMapping("/password") Map<String,Boolean> change(@Valid @RequestBody ChangePassword input, Authentication auth) {
        if (input.newPassword().length() < 10 || input.newPassword().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码须为至少10字符、至多72字节");
        String hash = jdbc.queryForObject("SELECT password_hash FROM da_users WHERE username=?", String.class, auth.getName());
        if (!passwords.matches(input.currentPassword(), hash)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前密码不正确");
        jdbc.update("UPDATE da_users SET password_hash=? WHERE username=?", passwords.encode(input.newPassword()), auth.getName());
        return Map.of("success", true);
    }
}
