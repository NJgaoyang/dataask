package com.dataask.chat;

import com.dataask.data.StarRocksGateway;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class ColumnLabelService {
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
    private static final Map<String,String> COMMON = Map.ofEntries(
        Map.entry("city","城市"), Map.entry("city_name","城市名称"), Map.entry("city_name_std","城市"),
        Map.entry("province","省份"), Map.entry("province_name","省份"),
        Map.entry("district","区县"), Map.entry("district_name","区县名称"),
        Map.entry("region","区域"), Map.entry("region_name","区域名称"),
        Map.entry("store","门店"), Map.entry("store_name","门店名称"), Map.entry("store_id","门店编号"),
        Map.entry("dt","业务日期"), Map.entry("date","日期"), Map.entry("biz_date","业务日期"),
        Map.entry("month","月份"), Map.entry("month_dt","统计月份"), Map.entry("year","年份"),
        Map.entry("order_cnt","订单量"), Map.entry("order_count","订单数"),
        Map.entry("total_orders","订单总数"), Map.entry("total_order","订单总数"),
        Map.entry("paid_orders","支付订单数"), Map.entry("pay_order_cnt","支付订单数"),
        Map.entry("user_cnt","用户数"), Map.entry("user_count","用户数"),
        Map.entry("total_users","用户总数"), Map.entry("new_users","新增用户数"),
        Map.entry("pay_amount","实付金额"), Map.entry("payment_amount","支付金额"),
        Map.entry("amount","金额"), Map.entry("total_amount","总金额"),
        Map.entry("revenue","营业额"), Map.entry("total_revenue","营业额"),
        Map.entry("net_revenue","净收入"), Map.entry("gross_revenue","总收入"),
        Map.entry("income","收入"), Map.entry("total_income","收入合计"),
        Map.entry("refund_amount","退款金额"), Map.entry("refund_cnt","退款订单数"),
        Map.entry("avg_amount","平均金额"), Map.entry("average_amount","平均金额"),
        Map.entry("sales","销售额"), Map.entry("total_sales","销售总额"),
        Map.entry("growth_rate","增长率"), Map.entry("change_rate","变化率"),
        Map.entry("rate","比率"), Map.entry("ratio","占比"),
        Map.entry("percentage","百分比"), Map.entry("ranking","排名"), Map.entry("rank","排名"),
        Map.entry("count","数量"), Map.entry("total","合计"), Map.entry("value","数值")
    );

    public Map<String,String> labels(List<String> columns, JsonNode candidate,
            Set<String> allowed, List<StarRocksGateway.TableMeta> metadata) {
        Map<String,String> comments = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (StarRocksGateway.TableMeta table : metadata) {
            if (!allowed.contains(table.name())) continue;
            for (StarRocksGateway.ColumnMeta col : table.columns()) {
                String key = col.name().toLowerCase(Locale.ROOT);
                String label = simplifyComment(col.comment());
                if (label.isBlank()) continue;
                String prior = comments.putIfAbsent(key, label);
                if (prior != null && !prior.equals(label)) ambiguous.add(key);
            }
        }
        ambiguous.forEach(comments::remove);
        Map<String,String> out = new LinkedHashMap<>();
        for (int i=0;i<columns.size();i++) {
            String column=columns.get(i);
            String key=column.replace("`","").replace("\"","").toLowerCase(Locale.ROOT).trim();
            String answer = "";
            if (candidate != null && candidate.isObject()) {
                String provided = candidate.path(column).asText("").trim();
                if (provided.length()<=30 && HAN.matcher(provided).find()) answer=provided;
            }
            if (answer.isEmpty() && HAN.matcher(column).find()) answer=column;
            if (answer.isEmpty()) answer=comments.getOrDefault(key,"");
            if (answer.isEmpty()) answer=COMMON.getOrDefault(key,"");
            if (answer.isEmpty() && key.startsWith("total_")) {
                String suffix=key.substring(6);
                String meaning=COMMON.getOrDefault(suffix,"");
                if (!meaning.isBlank()) answer=meaning.endsWith("数") ? meaning.replace("数","总数") : meaning+"合计";
            }
            if (answer.isEmpty() && (key.endsWith("_cnt") || key.endsWith("_count"))) {
                String root=key.replaceAll("_(cnt|count)$","");
                String rootName=COMMON.getOrDefault(root,"");
                if(!rootName.isBlank()) answer=rootName+"数量";
            }
            if (answer.isEmpty()) answer="字段"+(i+1);
            out.put(column,answer);
        }
        return out;
    }

    private static String simplifyComment(String raw) {
        if (raw==null || raw.isBlank() || !HAN.matcher(raw).find()) return "";
        String first=raw.trim().split("[，。；;\\n（(]",2)[0].trim();
        if (first.length()>30 || first.length()<2) return "";
        return first;
    }
}
