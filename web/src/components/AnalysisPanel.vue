<script setup>
import { computed } from 'vue'
import AnswerChart from './AnswerChart.vue'

const props = defineProps({
  message: { type: Object, default: null },
  pending: Boolean,
  question: { type: String, default: '' }
})
const emit = defineEmits(['close'])

const semantic = computed(() => props.message?.semantic || null)
const execution = computed(() => props.message?.execution || null)
const steps = computed(() => execution.value?.steps || [])
const currentQuestion = computed(() => props.message?.question || props.question || '正在分析当前问题')
const canPlot = computed(() => {
  const message = props.message
  return !!message?.rows?.length && ['bar', 'line', 'pie'].includes(message.chartType)
})
const previewRows = computed(() => (props.message?.rows || []).slice(0, 5))

function duration(ms) {
  if (!Number.isFinite(ms) || ms < 0) return ''
  if (ms < 1000) return `${Math.round(ms)} 毫秒`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} 秒`
  return `${Math.floor(ms / 60000)} 分 ${Math.floor(ms % 60000 / 1000)} 秒`
}
function statusIcon(status) {
  if (status === 'completed') return 'success'
  if (status === 'rejected') return 'cross'
  if (status === 'needs_input') return 'question-o'
  return 'minus'
}
function pretty(value) {
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'number') return value.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
  return String(value)
}
</script>

<template>
  <aside class="da-analysis-panel" aria-label="分析过程">
    <header class="da-analysis-panel-header">
      <div><span class="da-analysis-header-mark"><van-icon name="cluster-o"/></span><strong>分析过程</strong></div>
      <button type="button" aria-label="关闭分析过程" title="关闭" @click="emit('close')"><van-icon name="cross"/></button>
    </header>

    <div class="da-analysis-panel-scroll">
      <section class="da-analysis-question">
        <small>当前问题</small>
        <p>{{ currentQuestion }}</p>
      </section>

      <section v-if="pending" class="da-analysis-running" aria-live="polite">
        <span class="da-analysis-spinner"><van-loading size="16px"/></span>
        <div><strong>正在分析</strong><p>正在理解问题并查询已授权的经营数据，请稍候。</p></div>
      </section>

      <template v-else-if="message">
        <ol v-if="steps.length" class="da-analysis-steps">
          <li v-for="(step, index) in steps" :key="step.key + index" :class="'is-' + step.status">
            <span class="da-analysis-step-icon"><van-icon :name="statusIcon(step.status)"/></span>
            <div class="da-analysis-step-copy">
              <div><strong>{{ step.label }}</strong><time v-if="duration(step.durationMs)">{{ duration(step.durationMs) }}</time></div>
              <p>{{ step.detail }}</p>
            </div>
          </li>
        </ol>

        <section v-if="semantic" class="da-analysis-card">
          <h3><van-icon name="aim"/> 语义理解</h3>
          <dl>
            <div><dt>分析维度</dt><dd><span v-for="field in semantic.dimensions" :key="field.column">{{ field.label }}</span><em v-if="!semantic.dimensions?.length">无分组维度</em></dd></div>
            <div><dt>计算指标</dt><dd><span v-for="field in semantic.metrics" :key="field.column">{{ field.label }}</span><em v-if="!semantic.metrics?.length">未识别明确指标</em></dd></div>
            <div v-if="message.timeLabel"><dt>时间范围</dt><dd>{{ message.timeLabel }}</dd></div>
            <div v-if="semantic.filters?.length"><dt>筛选条件</dt><dd><code v-for="filter in semantic.filters" :key="filter">{{ filter }}</code></dd></div>
          </dl>
        </section>

        <section v-if="message.sql" class="da-analysis-card da-analysis-sql">
          <h3><van-icon name="description"/> 执行 SQL</h3>
          <pre>{{ message.sql }}</pre>
        </section>

        <section v-if="message.rows?.length" class="da-analysis-card da-analysis-result">
          <h3><van-icon name="chart-trending-o"/> 查询结果 <span>{{ message.rows.length }} 行</span></h3>
          <AnswerChart v-if="canPlot" :message="message" :chart-type-override="message.chartType"/>
          <div v-else class="da-analysis-mini-table">
            <table>
              <thead><tr><th v-for="column in message.columns" :key="column">{{ message.columnLabels?.[column] || column }}</th></tr></thead>
              <tbody><tr v-for="(row,index) in previewRows" :key="index"><td v-for="column in message.columns" :key="column">{{ pretty(row[column]) }}</td></tr></tbody>
            </table>
          </div>
        </section>

        <section class="da-analysis-finished" :class="{ 'is-warning': message.unsupported }">
          <van-icon :name="message.unsupported ? 'warning-o' : 'passed'"/>
          <div><strong>{{ message.unsupported ? '分析已结束' : '分析完成' }}</strong><p>{{ message.unsupported ? '本次没有执行有效数据查询。' : `已完成查询${execution ? '，总用时 ' + duration(execution.durationMs) : ''}。` }}</p></div>
        </section>
      </template>
    </div>
  </aside>
</template>

<style scoped>
.da-analysis-panel{grid-area:analysis;min-width:0;min-height:0;display:flex;flex-direction:column;background:var(--da-surface);border-left:1px solid var(--da-border);color:var(--da-text);box-shadow:none;z-index:8}
.da-analysis-panel-header{height:52px;flex:0 0 52px;display:flex;align-items:center;justify-content:space-between;padding:0 12px;border-bottom:1px solid var(--da-border)}
.da-analysis-panel-header>div{display:flex;align-items:center;gap:9px}.da-analysis-header-mark{width:28px;height:28px;border-radius:8px;display:flex;align-items:center;justify-content:center;background:var(--da-primary-soft);color:var(--da-primary)}
.da-analysis-panel-header strong{font-size:15px;font-weight:600}.da-analysis-panel-header button{width:30px;height:30px;border:0;border-radius:6px;background:transparent;color:var(--da-text-3);font-size:16px}.da-analysis-panel-header button:hover{background:var(--da-surface-2);color:var(--da-text)}
.da-analysis-panel-scroll{min-height:0;overflow-y:auto;padding:12px 0 20px;scrollbar-width:thin;scrollbar-color:var(--da-border-strong) transparent}
.da-analysis-question{padding:12px 14px;border:1px solid var(--da-border);border-left:0;border-right:0;border-radius:0;background:var(--da-panel)}
.da-analysis-question small{font-size:11px;color:var(--da-text-3)}.da-analysis-question p{margin:6px 0 0;font-size:13px;line-height:1.65;color:var(--da-text);overflow-wrap:anywhere}
.da-analysis-running{display:flex;align-items:flex-start;gap:11px;margin-top:18px;padding:14px;border:1px solid var(--da-primary-border);border-left:0;border-right:0;border-radius:0;background:var(--da-primary-soft)}
.da-analysis-spinner,.da-analysis-step-icon{display:flex;align-items:center;justify-content:center;flex-shrink:0;width:22px;height:22px;border-radius:50%;background:var(--da-success-soft);color:var(--da-success)}
.da-analysis-running strong,.da-analysis-finished strong{font-size:13px;font-weight:600}.da-analysis-running p,.da-analysis-finished p{margin:4px 0 0;font-size:12px;line-height:1.6;color:var(--da-text-2)}
.da-analysis-steps{list-style:none;margin:20px 14px 0}.da-analysis-steps li{position:relative;display:grid;grid-template-columns:24px minmax(0,1fr);gap:10px;padding-bottom:18px}
.da-analysis-steps li:not(:last-child)::after{content:"";position:absolute;left:10px;top:25px;bottom:3px;width:1px;background:var(--da-border-strong)}
.da-analysis-step-icon{font-size:12px}.da-analysis-steps .is-rejected .da-analysis-step-icon,.da-analysis-steps .is-needs_input .da-analysis-step-icon{background:var(--da-warning-soft);color:var(--da-warning)}.da-analysis-steps .is-skipped .da-analysis-step-icon{background:var(--da-tag-bg);color:var(--da-muted)}
.da-analysis-step-copy>div{display:flex;align-items:center;gap:8px;min-height:22px}.da-analysis-step-copy strong{font-size:13px;font-weight:600;color:var(--da-text)}.da-analysis-step-copy time{margin-left:auto;font-size:10px;color:var(--da-text-3);font-variant-numeric:tabular-nums}.da-analysis-step-copy p{margin:4px 0 0;font-size:12px;line-height:1.6;color:var(--da-text-3);overflow-wrap:anywhere}
.da-analysis-card{margin-top:14px;padding:14px;border:1px solid var(--da-border);border-left:0;border-right:0;border-radius:0;background:var(--da-surface)}.da-analysis-card h3{display:flex;align-items:center;gap:7px;margin:0 0 12px;font-size:13px;font-weight:600;color:var(--da-text)}.da-analysis-card h3>.van-icon{color:var(--da-primary);font-size:15px}.da-analysis-card h3>span{margin-left:auto;font-size:10px;font-weight:400;color:var(--da-text-3)}
.da-analysis-card dl{display:grid;gap:10px;margin:0}.da-analysis-card dl>div{display:grid;grid-template-columns:64px minmax(0,1fr);gap:10px;font-size:12px;line-height:1.65}.da-analysis-card dt{color:var(--da-text-3)}.da-analysis-card dd{display:flex;flex-wrap:wrap;gap:5px;margin:0;color:var(--da-text-2);min-width:0}.da-analysis-card dd span{padding:1px 7px;border-radius:4px;background:var(--da-primary-soft);color:var(--da-primary)}.da-analysis-card dd em{font-style:normal;color:var(--da-text-3)}.da-analysis-card code{width:100%;font:11px/1.65 ui-monospace,SFMono-Regular,Consolas,monospace;overflow-wrap:anywhere;color:var(--da-text-2)}
.da-analysis-sql pre{max-height:220px;margin:0;padding:12px;overflow:auto;border-radius:0;background:var(--da-panel);font:11px/1.7 ui-monospace,SFMono-Regular,Consolas,monospace;color:var(--da-text-2);white-space:pre-wrap;overflow-wrap:anywhere}
.da-analysis-result :deep(.chart-shell){margin:0;padding:0;border:0;box-shadow:none}.da-analysis-result :deep(.chart-caption){display:none}.da-analysis-result :deep(.chart-view){height:220px!important;min-height:220px}.da-analysis-result :deep(.chart-limit){display:none}
.da-analysis-mini-table{overflow:auto;max-height:220px}.da-analysis-mini-table table{width:100%;border-collapse:collapse;font-size:11px}.da-analysis-mini-table th,.da-analysis-mini-table td{padding:8px;border-bottom:1px solid var(--da-border);text-align:left;white-space:nowrap}.da-analysis-mini-table th{color:var(--da-text-3);font-weight:500;background:var(--da-panel)}.da-analysis-mini-table td{color:var(--da-text-2);font-variant-numeric:tabular-nums}
.da-analysis-finished{display:flex;gap:10px;margin-top:16px;padding:13px 14px;border-radius:0;background:var(--da-success-soft);color:var(--da-success)}.da-analysis-finished>.van-icon{margin-top:2px;font-size:16px;flex-shrink:0}.da-analysis-finished.is-warning{background:var(--da-warning-soft);color:var(--da-warning)}
@media(max-width:1280px){.da-analysis-panel{position:fixed;right:0;top:48px;bottom:0;width:min(390px,calc(100vw - 240px));box-shadow:none}}
@media(prefers-reduced-motion:reduce){.da-analysis-panel *{transition:none!important}}
</style>
