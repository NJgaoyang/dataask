// Synthetic data for browser verification only; never imported by application code.
export const analysisAnswer = {
  id:'msg-001', sessionId:'s2', question:'昨天各城市的订单量排行', title:'各城市订单量排行',
  summary:'本次查询返回 5 行数据，按城市展示。\n在返回数据中，西安市的订单量最高，为 188。\n以上仅描述本次返回结果（最多 200 行），不代表全部数据，也不推断业务原因。',
  chartType:'bar', columns:['city','order_cnt'], columnLabels:{ city:'城市', order_cnt:'订单量' },
  rows:[{city:'西安市',order_cnt:188},{city:'苏州市',order_cnt:156},{city:'武汉市',order_cnt:105},{city:'成都市',order_cnt:98},{city:'杭州市',order_cnt:76}],
  unsupported:false, followUps:['按门店看', '查看近7天趋势'], metricLabel:'订单量', timeLabel:'昨天', dataset:'ads.city_orders',
  semantic:{ dimensions:[{column:'city',label:'城市',expression:'city'}], metrics:[{column:'order_cnt',label:'订单量',expression:'SUM(order_cnt)'}],
    filters:["dt = '2026-11-09'"], tables:['ads.city_orders'], partial:false },
  sql:"SELECT city, SUM(order_cnt) AS order_cnt\nFROM ads.city_orders\nWHERE dt = '2026-11-09'\nGROUP BY city\nORDER BY order_cnt DESC\nLIMIT 200",
  execution:{ durationMs:8200, steps:[
    {key:'metadata',label:'数据准备',status:'completed',durationMs:120,detail:'已验证会话归属并读取当前授权 ADS 表结构'},
    {key:'semantic',label:'语义检索',status:'completed',durationMs:730,detail:'已完成业务口径与实体匹配；已返回向量检索参考'},
    {key:'planning',label:'查询规划',status:'completed',durationMs:6100,detail:'已生成结构化查询计划'},
    {key:'validation',label:'查询校验',status:'completed',durationMs:50,detail:'只读 SELECT、授权表范围与指定日期校验通过'},
    {key:'query',label:'数据查询',status:'completed',durationMs:1050,detail:'StarRocks 实际返回 5 行（最多 200 行）'},
    {key:'result',label:'结果整理',status:'completed',durationMs:150,detail:'根据实际查询解析维度、指标和筛选条件，并整理返回数据摘要'}
  ]}
}
