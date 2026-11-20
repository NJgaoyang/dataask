package com.dataask.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

public record ListPage(int page,int size) {
    public ListPage {
        if (page<1 || page>1000000 || size<1 || size>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"分页参数不正确");
    }
    public int offset() { return (page-1)*size; }
    public Map<String,Object> result(List<?> items,Long total) { return Map.of("items",items,"total",total,"page",page,"pageSize",size); }
    public static String pattern(String keyword) {
        String value=Objects.toString(keyword,"").trim();
        if (value.length()>180) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"搜索内容不能超过180字");
        return "%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
    }
}
