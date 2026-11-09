package com.dataask.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class BuildInfoController {
    private final Map<String,Object> build;
    public BuildInfoController(ObjectMapper mapper) {
        Map<String,Object> value = new LinkedHashMap<>();
        try (var input = new ClassPathResource("static/build-info.json").getInputStream()) {
            var json = mapper.readTree(input);
            for (String key : new String[]{"buildSha", "builtAt", "uiLayout", "configStorage", "catalogVersion"})
                value.put(key, json.path(key).asText("unknown"));
        } catch (Exception ex) {
            value.put("buildSha", "unpackaged");
            value.put("uiLayout", "unknown");
        }
        value.put("pid", ProcessHandle.current().pid());
        value.put("startedAt", ProcessHandle.current().info().startInstant().map(Object::toString).orElse("unknown"));
        build = Map.copyOf(value);
    }
    @GetMapping("/api/build-info")
    public ResponseEntity<Map<String,Object>> info() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(build);
    }
}
