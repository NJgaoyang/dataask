package com.dataask.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
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
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtFilter jwt) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers("/api/health").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .exceptionHandling(e -> e.authenticationEntryPoint(
                (request, response, exception) -> response.sendError(401)))
            .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    CommandLineRunner initialAdmin(JdbcTemplate jdbc, PasswordEncoder encoder,
            @Value("${dataask.admin-password:}") String password) {
        return args -> {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM da_users", Integer.class);
            if (count != null && count == 0) {
                if (password == null || password.length() < 10)
                    throw new IllegalStateException("首次启动必须设置不少于10位的 DATAASK_ADMIN_PASSWORD");
                jdbc.update("INSERT INTO da_users(username,display_name,password_hash,role) VALUES(?,?,?,?)",
                    "admin", "系统管理员", encoder.encode(password), "ADMIN");
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

    JwtTokens(ObjectMapper mapper, @Value("${dataask.jwt-secret:}") String secret) {
        this.mapper = mapper;
        this.secret = secret;
    }

    @PostConstruct
    void check() {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("必须设置不少于32字节的 DATAASK_JWT_SECRET");
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
            JsonNode header = mapper.readTree(DEC.decode(parts[0]));
            if (!"HS256".equals(header.path("alg").asText())) return null;
            if (!MessageDigest.isEqual(hmac(parts[0] + "." + parts[1]), DEC.decode(parts[2]))) return null;
            JsonNode payload = mapper.readTree(DEC.decode(parts[1]));
            String username = payload.path("sub").asText("");
            String role = payload.path("role").asText("");
            if (username.isBlank() || !(role.equals("USER") || role.equals("ADMIN"))
                || payload.path("exp").asLong(0) <= Instant.now().getEpochSecond()) return null;
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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            JwtTokens.Identity id = tokens.read(auth.substring(7));
            if (id != null) {
                List<String> roles = jdbc.query("SELECT role FROM da_users WHERE username=?",
                    (rs, n) -> rs.getString(1), id.username());
                if (!roles.isEmpty() && roles.getFirst().equals(id.role())) {
                    Authentication principal = new UsernamePasswordAuthenticationToken(
                        id.username(), null, List.of(new SimpleGrantedAuthority("ROLE_" + id.role())));
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

    @PostMapping("/login")
    Map<String, Object> login(@Valid @RequestBody LoginRequest input) {
        List<Map<String, Object>> users = jdbc.queryForList(
            "SELECT username,display_name,password_hash,role FROM da_users WHERE username=?", input.username());
        if (users.isEmpty() || !passwords.matches(input.password(), (String) users.getFirst().get("password_hash")))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        Map<String, Object> user = users.getFirst();
        return Map.of("token", tokens.issue((String) user.get("username"), (String) user.get("role")),
            "username", user.get("username"), "displayName", user.get("display_name"), "role", user.get("role"));
    }

    @GetMapping("/me")
    Map<String, String> me(Authentication auth) {
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return Map.of("username", auth.getName(), "role", admin ? "ADMIN" : "USER");
    }
}
