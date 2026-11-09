<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  message: { type: Object, required: true },
  chartTypeOverride: { type: String, default: '' },
  desktopExpanded: { type: Boolean, default: false }
})
// Desktop results can use their full content width and display up to 60 returned
// categories instead of silently truncating a 21-city ranking at 12.
const resolvedType = computed(() =>
  ['bar','line','pie'].includes(props.chartTypeOverride)
    ? props.chartTypeOverride : (props.message.chartType || 'table'))
const maxVisibleRows = computed(() => props.desktopExpanded
  ? (resolvedType.value === 'pie' ? 12 : 60)
  : (resolvedType.value === 'pie' ? 8 : 12))
const chartHeight = computed(() => {
  if (!props.desktopExpanded) return undefined
  const count = Math.min(props.message.rows?.length || 0, maxVisibleRows.value)
  if (resolvedType.value === 'bar' && count > 5) return Math.min(1900, Math.max(430, count * 34 + 100)) + 'px'
  return '430px'
})
const el = ref(null)
let chart = null
let observer = null
const palette = ['#3478ec', '#12b8a6', '#f49b4e', '#8467d8', '#e86992', '#24a6cf', '#dbc34d', '#62bc80']

function renderChart() {
  if (!el.value) return
  const { columns = [], rows = [], columnLabels = {} } = props.message
  const chartType = ['bar','line','pie'].includes(props.chartTypeOverride) ? props.chartTypeOverride : (props.message.chartType || 'table')
  const numericColumns = columns.filter(key => rows.some(row =>
    row[key] !== null && row[key] !== '' && row[key] !== undefined &&
    typeof row[key] !== 'boolean' && Number.isFinite(Number(row[key]))))
  const measures = numericColumns.filter(key => /count|cnt|amount|orders|revenue|total|sum|num|income|sales|金额|收入|数量|订单/i.test(key))
  const measure = measures[0] || numericColumns[numericColumns.length - 1]
  const dimension = columns.find(key => key !== measure && rows.some(row =>
    row[key] !== null && row[key] !== undefined && !Number.isFinite(Number(row[key])))) ||
    columns.find(key => key !== measure)
  if (!measure || !dimension || !rows.length) {
    if (chart) { chart.dispose(); chart = null }
    return
  }
  const limit = maxVisibleRows.value
  const values = rows.slice(0, limit).map(row => ({
    name: String(row[dimension] ?? '未分类'),
    value: Number(row[measure]) || 0
  }))
  const labels = values.map(item => item.name)
  const numbers = values.map(item => item.value)
  const basicLabels = {
    city:'城市', city_name:'城市', city_name_std:'城市', province:'省份',
    store_name:'门店名称', dt:'业务日期', biz_date:'业务日期', month:'月份',
    order_cnt:'订单量', order_count:'订单数', total_orders:'订单总数',
    user_cnt:'用户数', pay_amount:'实付金额', total_amount:'总金额',
    revenue:'营业额', total_revenue:'营业额', net_revenue:'净收入',
    refund_amount:'退款金额'
  }
  const valueName = columnLabels[measure] || basicLabels[measure] || '指标数值'
  const dimName = columnLabels[dimension] || basicLabels[dimension] || '分析维度'
  const paletteColor = value => ({
    type: 'linear',
    x: 0, y: 0, x2: 1, y2: 0,
    colorStops: [{ offset: 0, color: value }, { offset: 1, color: value }]
  })
  const type = ['bar', 'line', 'pie'].includes(chartType) ? chartType : 'bar'
  if (!chart) chart = echarts.init(el.value, null, { renderer: 'canvas' })
  const common = {
    color: palette,
    animationDuration: 450,
    textStyle: { fontFamily: 'PingFang SC, Microsoft YaHei, sans-serif', color: '#496079' },
    tooltip: {
      trigger: type === 'pie' ? 'item' : 'axis',
      backgroundColor: '#fff',
      borderColor: '#e2eaf5',
      padding: [10, 12],
      textStyle: { color: '#263e5b', fontSize: 12 },
      confine: true,
      valueFormatter: value => Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
    }
  }
  if (type === 'pie' && numbers.every(v => v >= 0) && numbers.some(v => v > 0)) {
    chart.setOption({
      ...common,
      legend: {
        bottom: 3, left: 'center', type: 'scroll', itemWidth: 10, itemHeight: 10,
        textStyle: { color: '#71829a', fontSize: 11 }, pageButtonItemGap: 4
      },
      series: [{
        name: valueName, type: 'pie',
        radius: ['43%', '69%'], center: ['50%', '43%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 7, borderWidth: 3, borderColor: '#fff' },
        label: { show: false }, emphasis: { label: { show: true, fontWeight: 600 } },
        data: values
      }]
    }, { notMerge: true })
  } else if (type === 'line') {
    chart.setOption({
      ...common,
      grid: { left: 10, right: 15, top: 27, bottom: 52, containLabel: true },
      xAxis: { type: 'category', data: labels, axisTick: { show: false },
        axisLabel: { color: '#8190a6', fontSize: 10, rotate: labels.length > 7 ? 30 : 0 },
        axisLine: { lineStyle: { color: '#e5ebf5' } } },
      yAxis: { type: 'value', name: valueName, nameTextStyle: { color: '#8b99ab', fontSize: 10 },
        axisLabel: { color: '#8190a6', fontSize: 10 },
        splitLine: { lineStyle: { color: '#e9eff8', type: 'dashed' } } },
      series: [{ name: valueName, type: 'line', smooth: 0.32,
        data: numbers, lineStyle: { width: 3, color: palette[0] },
        symbol: 'circle', symbolSize: 7,
        itemStyle: { color: palette[0], borderColor: '#fff', borderWidth: 2 },
        areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: '#b8d6ff' }, { offset: 1, color: 'rgba(231,242,255,0.08)' }
        ]) } }]
    }, { notMerge: true })
  } else {
    const horizontal = labels.length > 5
    const colors = numbers.map((_, i) => palette[i % palette.length])
    chart.setOption({
      ...common,
      grid: { left: 12, right: horizontal ? 45 : 15, top: 24, bottom: horizontal ? 20 : 60, containLabel: true },
      xAxis: horizontal
        ? { type: 'value', axisLabel: { color: '#8391a5', fontSize: 10 },
            splitLine: { lineStyle: { color: '#e8eef7', type: 'dashed' } } }
        : { type: 'category', data: labels, axisLabel: { color: '#7185a1', fontSize: 11,
            rotate: labels.length > 4 ? 25 : 0 }, axisTick: { show: false }, axisLine: { lineStyle: { color: '#e2eaf4' } } },
      yAxis: horizontal
        ? { type: 'category', inverse: true, data: labels, axisTick: { show: false },
            axisLine: { show: false }, axisLabel: { color: '#536a85', fontSize: 11, width: 70, overflow: 'truncate' } }
        : { type: 'value', axisLabel: { color: '#8391a5', fontSize: 10 },
            splitLine: { lineStyle: { color: '#e8eef7', type: 'dashed' } } },
      series: [{
        name: valueName, type: 'bar', data: numbers.map((v, i) => ({
          value: v, itemStyle: { color: paletteColor(colors[i]), borderRadius: horizontal ? [0, 6, 6, 0] : [6, 6, 0, 0] }
        })), barMaxWidth: horizontal ? 18 : 34, barCategoryGap: '35%',
        label: horizontal ? { show: true, position: 'right', color: '#526782', fontSize: 10,
          formatter: p => Number(p.value).toLocaleString('zh-CN', { maximumFractionDigits: 1 }) } : { show: false }
      }]
    }, { notMerge: true })
  }
}

onMounted(() => {
  nextTick(() => {
    renderChart()
    if (el.value && typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(() => chart?.resize())
      observer.observe(el.value)
    }
  })
})
watch(() => props.message, () => nextTick(renderChart), { deep: true })
watch(() => props.chartTypeOverride, () => nextTick(renderChart))
watch(() => props.desktopExpanded, () => nextTick(renderChart))
onBeforeUnmount(() => { observer?.disconnect(); if (chart) chart.dispose() })
</script>

<template>
  <div class="chart-shell" :class="{ 'chart-expanded': desktopExpanded }">
    <div class="chart-caption">
      <span>经营数据图表</span><small>基于 StarRocks 实际查询结果</small>
    </div>
    <div ref="el" class="chart-view" :style="desktopExpanded ? {height:chartHeight} : undefined"></div>
    <p v-if="message.rows?.length > maxVisibleRows" class="chart-limit">
      图表展示前 {{ maxVisibleRows }} 项，切换至表格可查看本次返回的全部 {{ message.rows.length }} 行
    </p>
  </div>
</template>
