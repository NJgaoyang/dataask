package com.dataask.chat;

import com.dataask.data.StarRocksGateway;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic clarification of metric-less questions. Suggestions are derived
 * only from numeric columns of currently authorized ADS tables; never assume
 * a business metric exists just because a name sounds familiar.
 */
public final class QuestionClarificationService {
    private static final Pattern NUMERIC = Pattern.compile(
        "(?i).*(?:int|decimal|numeric|double|float|number|real).*");
    private static final Pattern ORDERS = Pattern.compile(
        "(?i)(?:订单(?:量|数|总数)|下单(?:量|数)|order_(?:cnt|count|num)|total_orders|orders_(?:cnt|count|total))");
    private static final Pattern USERS = Pattern.compile(
        "(?i)(?:用户(?:数|量|总数)|新增用户|活跃用户|user_(?:cnt|count|num)|(?:total|active|new)_users|users_(?:cnt|count))");
    private static final Pattern REVENUE = Pattern.compile(
        "(?i)(?:营业额|营收|销售额|销售收入|交易金额|revenue|gmv|sales_(?:amount|total)|sale_amount)");
    private static final Pattern PAYMENTS = Pattern.compile(
        "(?i)(?:支付金额|实付金额|付款金额|pay_amount|paid_amount|payment_amount)");
    private static final Pattern KNOWN_METRIC = Pattern.compile(
        "(?i)(?:订单|下单|用户|活跃|新增|留存|复购|销售|营业额|营收|金额|收入|利润|成本|库存|退款|客单|访客|客流|GMV|转化率|转化量|数量|单量|订单数|订单量|金额数|收入额|revenue|order|sales|user|roi)");
    private static final Pattern VAGUE_END = Pattern.compile(
        "(?:的)?(?:(?:数据)?(?:趋势|走势|排行|排名|变化|对比|统计|情况|表现|分析)|数据|怎么样|如何|多少|哪些)(?:[?？!！。\\s]*)$");
    private static final Pattern CONTEXT_ONLY = Pattern.compile(
        "^(?:(?:帮我|请|给我|我想|想|查询|查一下|查|查看|看看|看|分析|统计|展示|了解|一下|的|按|各|在|关于|近|最近|过去|前|上|今|昨天|昨日|今天|今日|前天|天|日|月|本|周|年|城市|门店|地区|区域|省份|全国|西安市|西安|本市)|\\d+|[\\s?？！!。的])+$");
    private static final Pattern TRAILING = Pattern.compile("[?？!！。\\s]+$");
    private static final Pattern ENDING = Pattern.compile(
        "(?:的)?(?:(?:数据)?(趋势|走势|排行|排名|变化|对比|情况|表现|分析)|数据|怎么样|如何|多少)(?:[?？!！。\\s]*)$");

    public boolean metricMissing(String question, String previousQuestion) {
        if (question == null || question.isBlank() || question.length()>200) return false;
        if (previousQuestion!=null && KNOWN_METRIC.matcher(previousQuestion).find()) return false;
        String compact=question.replaceAll("\\s+","");
        if (KNOWN_METRIC.matcher(compact).find()) return false;
        // Only interpret a short, explicitly general analytic question as an
        // unknown metric. Questions about other subjects retain normal handling.
        Matcher end=VAGUE_END.matcher(compact);
        if (!end.find()) return false;
        String prefix=compact.substring(0,end.start());
        if (prefix.length()>38) return false;
        // Dates, locations and verbs are acceptable qualifiers. Avoid turning
        // an arbitrary unknown business subject into an invented measure.
        prefix=prefix.replaceAll("(?:近|最近|过去)\\d{1,3}天|\\d{4}-\\d{1,2}-\\d{1,2}","");
        prefix=prefix.replaceAll("[\\p{IsHan}]{2,8}市|[\\p{IsHan}]{2,8}省","");
        return prefix.length()<12 && (prefix.isEmpty() || CONTEXT_ONLY.matcher(prefix).matches());
    }

    public boolean isMetricClarification(String requested) {
        return requested!=null && Pattern.compile(
            "指标|订单|用户数|营业额|销售额|统计口径|哪项数据|哪个业务量|哪个数").matcher(requested).find();
    }

    /**
     * Generate at most three question options and preserve the original time
     * scope and geographic filtering phrases verbatim. Only recognized numeric
     * ADS columns from currently approved tables can enable an option.
     */
    public List<String> choices(String question, Set<String> granted,
                                List<StarRocksGateway.TableMeta> discovered) {
        if(question==null || question.isBlank() || discovered==null || granted==null) return List.of();
        Set<String> available=new LinkedHashSet<>();
        for(var table:discovered) {
            if(table==null || table.name()==null || !granted.contains(table.name()) ||
               table.columns()==null) continue;
            for(var col:table.columns()) {
                if(col==null || col.type()==null || col.name()==null ||
                   !NUMERIC.matcher(col.type()).matches()) continue;
                String descriptor=col.name()+" "+Objects.toString(col.comment(),"");
                if(ORDERS.matcher(descriptor).find()) available.add("订单量");
                if(USERS.matcher(descriptor).find()) available.add("用户数");
                if(REVENUE.matcher(descriptor).find()) available.add("营业额");
                if(PAYMENTS.matcher(descriptor).find()) available.add("支付金额");
            }
        }
        if(available.isEmpty()) return List.of();
        List<String> choices=new ArrayList<>(3);
        for(String metric:available) {
            if(choices.size()>=3) break;
            String suggested=addMetric(question,metric);
            if(suggested.length()<=150) choices.add(suggested);
        }
        return List.copyOf(choices);
    }

    private String addMetric(String question, String metric) {
        String normalized=TRAILING.matcher(question.trim()).replaceAll("");
        Matcher last=ENDING.matcher(normalized);
        if(last.find()) {
            String mode=last.group(1);
            String suffix=mode==null || mode.isBlank() ? "趋势" : mode;
            // '查看近7天趋势' => '查看近7天订单量趋势'
            return normalized.substring(0,last.start()) + metric + suffix;
        }
        return normalized + "的" + metric;
    }
}
