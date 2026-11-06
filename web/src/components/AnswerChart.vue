<script setup>
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({ message: { type: Object, required: true } })
const el = ref(null)
let instance = null

function renderChart() {
  if (!el.value) return
  const { columns = [], rows = [], chartType = 'table' } = props.message
  const measure = columns.find(c => rows.some(r => r[c] !== null && r[c] !== '' && Number.isFinite(Number(r[c]))))
  const dimension = columns.find(c => c !== measure)
  if (!measure || !dimension || !rows.length) {
    if (instance) { instance.dispose(); instance = null }
    return
  }
  if (!instance) instance = echarts.init(el.value)
  const labels = rows.slice(0, 20).map(r => String(r[dimension] ?? ''))
  const values = rows.slice(0, 20).map(r => Number(r[measure] || 0))
  const type = ['bar', 'line', 'pie'].includes(chartType) ? chartType : 'bar'
  instance.setOption({
    color: ['#2568c9', '#73a6ef', '#99c4f2', '#c1d8f6'],
    tooltip: { trigger: type === 'pie' ? 'item' : 'axis' },
    grid: { left: 8, right: 14, bottom: 50, top: 22, containLabel: true },
    xAxis: type === 'pie' ? undefined : { type: 'category', data: labels, axisLabel: { rotate: labels.length > 6 ? 35 : 0, color: '#7f8da0' } },
    yAxis: type === 'pie' ? undefined : { type: 'value', axisLabel: { color: '#7f8da0' } },
    series: type === 'pie'
      ? [{ type: 'pie', radius: ['35%', '64%'], data: labels.map((name, i) => ({ name, value: values[i] })) }]
      : [{ type, smooth: true, data: values, barMaxWidth: 36, areaStyle: type === 'line' ? { opacity: 0.08 } : undefined }]
  }, { notMerge: true })
}

onMounted(() => nextTick(renderChart))
watch(() => props.message, () => nextTick(renderChart), { deep: true })
onBeforeUnmount(() => { if (instance) instance.dispose() })
</script>

<template>
  <div ref="el" class="chart-view"></div>
</template>
