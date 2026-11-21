package com.dataask.data;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.regex.*;

/** Fail-closed row scope: every physical source table must have a city column. */
@Component
public class CityRowGuard {
    private static final List<String> CITY_COLUMNS =
        List.of("city_name", "city_name_std", "city", "city_std", "manage_city", "managed_city");
    private static final Pattern SELECT_WORD=Pattern.compile("(?i)\\bselect\\b");
    // StarRocks accepts quoted and Unicode identifiers; reject unsafe characters
    // without restricting the AI to ASCII-only aliases.
    private static final Pattern SAFE_IDENTIFIER =
        Pattern.compile("[\\p{L}\\p{N}_$][\\p{L}\\p{N}_$ .-]{0,127}");

    public String scope(String sql,List<StarRocksGateway.TableMeta> metadata,
                        List<String> cities,String schema){
        if(cities==null || cities.isEmpty() || cities.size()>1000) throw forbidden("没有有效城市权限");
        try{
            String masked=sql.replaceAll("'(?:''|[^'])*'","''");
            Matcher matcher=SELECT_WORD.matcher(masked);
            int count=0;while(matcher.find())count++;
            if(count!=1)throw forbidden("嵌套 SQL、CTE 和 UNION 暂不支持城市权限校验");
            if(!(CCJSqlParserUtil.parse(sql) instanceof PlainSelect plain))
                throw forbidden("仅允许可验证城市权限的 SELECT");
            Map<String,List<StarRocksGateway.ColumnMeta>> columns=new HashMap<>();
            for(var table:metadata)columns.put(table.name().toLowerCase(Locale.ROOT),table.columns());
            List<String> constraints=new ArrayList<>();
            add(plain.getFromItem(),columns,constraints,cities,schema);
            if(plain.getJoins()!=null)for(Join j:plain.getJoins()){
                if(j.isNatural() || j.isLeft() || j.isRight() || j.isFull() ||
                   (j.getUsingColumns()!=null && !j.getUsingColumns().isEmpty()))
                    throw forbidden("当前城市权限仅支持可验证的 INNER JOIN");
                add(j.getRightItem(),columns,constraints,cities,schema);
            }
            if(constraints.isEmpty())throw forbidden("没有可校验的城市字段");
            String where=plain.getWhere()==null ? "" : "("+plain.getWhere()+") AND ";
            plain.setWhere(CCJSqlParserUtil.parseCondExpression(where+"("+String.join(" AND ",constraints)+")"));
            return plain.toString();
        }catch(ResponseStatusException ex){throw ex;}
        catch(Exception ex){throw forbidden("SQL 城市权限校验失败");}
    }
    private static void add(FromItem item,Map<String,List<StarRocksGateway.ColumnMeta>> metadata,
                            List<String> constraints,List<String> cities,String schema){
        if(!(item instanceof Table table))throw forbidden("不支持无法检查城市权限的子表");
        String full=table.getFullyQualifiedName().replace(Character.toString(96),"")
            .replace("\"","").toLowerCase(Locale.ROOT);
        if(!full.contains("."))full=schema.toLowerCase(Locale.ROOT)+"."+full;
        if("ads.ads_user_city_permission".equals(full))throw forbidden("权限表不可直接查询");
        List<StarRocksGateway.ColumnMeta> cols=metadata.get(full);
        if(cols==null)throw forbidden("表未授权或元数据不可用");
        String cityField=null;
        for(String candidate:CITY_COLUMNS){
            for(var col:cols)if(candidate.equalsIgnoreCase(col.name())){cityField=col.name();break;}
            if(cityField!=null)break;
        }
        if(cityField==null)throw forbidden("表 "+full+" 无城市字段，无法验证行权限");
        String alias=table.getAlias()==null?table.getName():table.getAlias().getName();
        String safeAlias=quoteIdentifier(alias);
        String safeCityField=quoteIdentifier(cityField);
        for(String city:cities) {
            if(city==null || !city.matches("[\\p{L}\\p{N}\\p{Zs}·（）()\\-]{1,120}"))
                throw forbidden("城市授权值包含不支持的字符");
        }
        String permitted=String.join(",",cities.stream().map(city->"'"+city+"'").toList());
        constraints.add(safeAlias+"."+safeCityField+" IN ("+permitted+")");
    }

    /**
     * Normalize an AST-validated SQL identifier for the injected city predicate.
     * JSqlParser may preserve backticks/quotes in Alias.getName()/Table.getName().
     * Quote again for StarRocks, but never interpolate raw text into the predicate.
     */
    private static String quoteIdentifier(String value) {
        if(value==null)throw forbidden("表别名不合法");
        String name=value.strip();
        if(name.length()>=2 &&
            ((name.startsWith("`")&&name.endsWith("`")) ||
             (name.startsWith("\"")&&name.endsWith("\"")))) {
            String quote=name.substring(0,1);
            name=name.substring(1,name.length()-1).replace(quote+quote,quote);
        }
        if(!SAFE_IDENTIFIER.matcher(name).matches())
            throw forbidden("表别名不合法");
        return "`"+name.replace("`","``")+"`";
    }

    private static ResponseStatusException forbidden(String message){
        return new ResponseStatusException(HttpStatus.FORBIDDEN,message);
    }
}
