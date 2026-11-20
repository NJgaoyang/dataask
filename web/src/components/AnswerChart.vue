<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

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
  if (resolvedType.value === 'bar') {
    const rowHeight = count > 5 ? 34 : 42
    return Math.max(220, count * rowHeight + 100) + 'px'
  }
  if (resolvedType.value === 'pie') return Math.max(220, Math.min(320, count * 20 + 180)) + 'px'
  return Math.max(220, Math.min(320, count * 18 + 180)) + 'px'
})
const el = ref(null)
let chart = null
let observer = null
const palette = ['#0064C8', '#2FA36B', '#E8A33D', '#7A5AF8', '#D9534F', '#3BA7C9', '#C2B33D', '#5A8F5C']

function renderChart() {
  if (!el.value) return
  const styles = getComputedStyle(el.value)
  const themed = props.desktopExpanded
  const ink = themed ? styles.getPropertyValue('--da-text-2').trim() || '#496079' : '#496079'
  const muted = themed ? styles.getPropertyValue('--da-muted').trim() || '#8190a6' : '#8190a6'
  const gridColor = themed ? styles.getPropertyValue('--da-border').trim() || '#e9eff8' : '#e9eff8'
  const surface = themed ? styles.getPropertyValue('--da-surface').trim() || '#fff' : '#fff'
  const { columns = [], rows = [], columnLabels = {} } = props.message
  const chartType = ['bar','line','pie'].includes(props.chartTypeOverride) ? props.chartTypeOverride : (props.message.chartType || 'table')
  const numericColumns = columns.filter(key => rows.some(row =>
    row[key] !== null && row[key] !== '' && row[key] !== undefined &&
    typeof row[key] !== 'boolean' && Number.isFinite(Number(row[key]))))
  const measures = numericColumns.filter(key => /count|cnt|amount|orders|revenue|total|sum|num|income|sales|金额|收入|数量|订单/i.test(key))
  const recognizedMeasure = props.message.semantic?.metrics?.find(field => numericColumns.includes(field.column))?.column
  const measure = recognizedMeasure || measures[0] || numericColumns[numericColumns.length - 1]
  const dimension = props.message.semantic?.dimensions?.find(field => columns.includes(field.column) && field.column !== measure)?.column || columns.find(key => key !== measure && rows.some(row =>
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
    animationDuration: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 450,
    textStyle: { fontFamily: 'PingFang SC, Microsoft YaHei, sans-serif', color: ink },
    tooltip: {
      trigger: type === 'pie' ? 'item' : 'axis',
      backgroundColor: surface,
      borderColor: gridColor,
      padding: [10, 12],
      textStyle: { color: ink, fontSize: 12 },
      confine: true,
      valueFormatter: value => Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
    }
  }
  if (type === 'pie' && numbers.every(v => v >= 0) && numbers.some(v => v > 0)) {
    chart.setOption({
      ...common,
      legend: {
        bottom: 3, left: 'center', type: 'scroll', itemWidth: 10, itemHeight: 10,
        textStyle: { color: muted, fontSize: 11 }, pageButtonItemGap: 4
      },
      series: [{
        name: valueName, type: 'pie',
        radius: ['43%', '69%'], center: ['50%', '43%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 4, borderWidth: 2, borderColor: surface },
        label: { show: false }, emphasis: { label: { show: true, fontWeight: 600 } },
        data: values
      }]
    }, { notMerge: true })
  } else if (type === 'line') {
    chart.setOption({
      ...common,
      grid: { left: 10, right: 15, top: 27, bottom: 52, containLabel: true },
      xAxis: { type: 'category', data: labels, axisTick: { show: false },
        axisLabel: { color: muted, fontSize: 10, rotate: labels.length > 7 ? 30 : 0 },
        axisLine: { lineStyle: { color: gridColor } } },
      yAxis: { type: 'value', name: valueName, nameTextStyle: { color: muted, fontSize: 10 },
        axisLabel: { color: muted, fontSize: 10 },
        splitLine: { lineStyle: { color: gridColor, type: 'dashed' } } },
      series: [{ name: valueName, type: 'line', smooth: 0.32,
        data: numbers, lineStyle: { width: 2, color: palette[0] },
        symbol: 'circle', symbolSize: 6,
        itemStyle: { color: palette[0], borderColor: surface, borderWidth: 2 },
        areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(0,100,200,0.16)' }, { offset: 1, color: 'rgba(0,100,200,0.02)' }
        ]) } }]
    }, { notMerge: true })
  } else {
    const horizontal = labels.length > 5
    // 排行柱状图按类目循环企业序列色，PC 与移动端保持一致的彩色口径。
    const colors = numbers.map((_, i) => palette[i % palette.length])
    chart.setOption({
      ...common,
      grid: { left: 12, right: horizontal ? 45 : 15, top: 24, bottom: horizontal ? 20 : 60, containLabel: true },
      xAxis: horizontal
        ? { type: 'value', axisLabel: { color: muted, fontSize: 10 },
            splitLine: { lineStyle: { color: gridColor, type: 'dashed' } } }
        : { type: 'category', data: labels, axisLabel: { color: muted, fontSize: props.desktopExpanded ? 12 : 11,
            rotate: labels.length > (props.desktopExpanded ? 7 : 4) ? 25 : 0 }, axisTick: { show: false }, axisLine: { lineStyle: { color: gridColor } } },
      yAxis: horizontal
        ? { type: 'category', inverse: true, data: labels, axisTick: { show: false },
            axisLine: { show: false }, axisLabel: { color: ink, fontSize: 11, width: 70, overflow: 'truncate' } }
        : { type: 'value', axisLabel: { color: muted, fontSize: 10 },
            splitLine: { lineStyle: { color: gridColor, type: 'dashed' } } },
      series: [{
        name: valueName, type: 'bar', data: numbers.map((v, i) => ({
          value: v, itemStyle: { color: paletteColor(colors[i]), borderRadius: horizontal ? [0, 3, 3, 0] : [3, 3, 0, 0] }
        })), barMaxWidth: horizontal ? 18 : 34, barCategoryGap: '35%',
        label: horizontal ? { show: true, position: 'right', color: ink, fontSize: 10,
          formatter: p => Number(p.value).toLocaleString('zh-CN', { maximumFractionDigits: 1 }) } : {
            show: props.desktopExpanded, position:'top', color:ink, fontSize:12,
            formatter:p => Number(p.value).toLocaleString('zh-CN', { maximumFractionDigits:1 }) }
      }]
    }, { notMerge: true })
  }
}

onMounted(() => {
  window.addEventListener('dataask-themechange', renderChart)
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
onBeforeUnmount(() => { window.removeEventListener('dataask-themechange', renderChart); observer?.disconnect(); if (chart) chart.dispose() })
</script>

<template>
  <div class="chart-shell" :class="{ 'chart-expanded': desktopExpanded, 'chart-scrollable': desktopExpanded && (message.rows?.length || 0) > 10 }">
    <div class="chart-caption">
      <span>经营数据图表</span><small>大数据团队提供服务支持</small>
    </div>
    <div ref="el" class="chart-view" :style="desktopExpanded ? {height:chartHeight} : undefined"></div>
    <p v-if="message.rows?.length > maxVisibleRows" class="chart-limit">
      图表展示前 {{ maxVisibleRows }} 项，切换至表格可查看本次返回的全部 {{ message.rows.length }} 行
    </p>
  </div>
</template>
