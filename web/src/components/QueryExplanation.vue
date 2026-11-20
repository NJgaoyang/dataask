<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { recordClientEvent } from '../audit'

const props = defineProps({ message: { type: Object, default: null }, pending: Boolean })
const elapsed = ref(0)
let ticker
onMounted(() => {
  if (!props.pending) return
  const started = Date.now()
  ticker = setInterval(() => { elapsed.value = Date.now() - started }, 500)
})
onBeforeUnmount(() => clearInterval(ticker))
const semantic = computed(() => props.message?.semantic)
const execution = computed(() => props.message?.execution)
const mappings = computed(() => [...(semantic.value?.dimensions || []),...(semantic.value?.metrics || [])].filter(field => field.expression))
const statusLabel = status => ({ completed:'已完成', needs_input:'待确认', rejected:'未通过', skipped:'未执行' })[status] || '已记录'
function duration(ms) {
  if (!Number.isFinite(ms) || ms < 0) return '未记录'
  if (ms < 1000) return `${Math.round(ms)} 毫秒`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} 秒`
  return `${Math.floor(ms / 60000)} 分 ${Math.floor(ms % 60000 / 1000)} 秒`
}
</script>

<template>
  <div class="da-query-explanation">
    <!-- Native disclosure: closed initially, keyboard-operable, independent per answer. -->
    <details v-if="pending || execution?.steps?.length" class="da-execution" @toggle="event => { if(event.target.open && message?.id) recordClientEvent('VIEW_PROCESS',message.id) }">
      <summary>
        <van-loading v-if="pending" size="14px"/>
        <van-icon v-else name="passed"/>
        <span>{{ pending ? '请求处理中' : message.unsupported ? '执行已结束' : '执行完成' }}</span>
        <span class="da-execution-time">{{ pending ? '已等待 ' + duration(elapsed) : '用时 ' + duration(execution.durationMs) }}</span>
        <van-icon name="arrow" class="da-disclosure-arrow"/>
      </summary>
      <div class="da-execution-body">
        <p v-if="pending" class="da-process-notice">正在处理…</p>
        <template v-else>
          <ol class="da-process-steps">
            <li v-for="(step, index) in execution.steps" :key="step.key + index" :class="'is-' + step.status">
              <span class="da-step-icon"><van-icon :name="step.status === 'completed' ? 'success' : step.status === 'rejected' ? 'cross' : 'minus'"/></span>
              <div><div class="da-step-heading"><b>{{ step.label }}</b><span>{{ statusLabel(step.status) }}</span><time>{{ duration(step.durationMs) }}</time></div><p>{{ step.detail }}</p></div>
            </li>
          </ol>
          <div v-if="mappings.length" class="da-process-mapping">
            <h4>字段映射与计算方式</h4>
            <dl><template v-for="field in mappings" :key="field.column"><dt>{{ field.label }}</dt><dd><code>{{ field.expression }}</code></dd></template></dl>
          </div>
          <div v-if="semantic?.filters?.length" class="da-process-filters"><h4>实际筛选条件</h4><code v-for="filter in semantic.filters" :key="filter">{{ filter }}</code></div>
        </template>
      </div>
    </details>
    <section v-if="message && !message.unsupported" class="da-semantic" aria-label="语义识别结果">
      <div class="da-semantic-heading"><van-icon name="aim"/><b>语义识别</b><small v-if="!semantic">历史记录</small></div>
      <dl v-if="semantic" class="da-semantic-fields">
        <div><dt>维度</dt><dd><span v-for="field in semantic.dimensions" :key="field.column" :title="field.expression" class="da-semantic-tag">{{ field.label }}</span><span v-if="!semantic.dimensions?.length" class="da-semantic-muted">{{ semantic.partial ? '未能完整解析' : '无分组维度' }}</span></dd></div>
        <div><dt>指标</dt><dd><span v-for="field in semantic.metrics" :key="field.column" :title="field.expression" class="da-semantic-tag is-metric">{{ field.label }}</span><span v-if="!semantic.metrics?.length" class="da-semantic-muted">未返回明确指标</span></dd></div>
        <div v-if="message.timeLabel"><dt>时间</dt><dd>{{ message.timeLabel }}</dd></div>
      </dl>
      <p v-if="!semantic" class="da-semantic-note">未记录语义</p>
      <p v-else-if="semantic.partial" class="da-semantic-note">部分语义未解析</p>
      <div class="da-semantic-source"><van-icon name="records-o"/><span>数据来源</span><span>{{ message.dataset || '未记录' }}</span></div>
    </section>
  </div>
</template>

<style scoped>
.da-query-explanation{margin:16px 0 20px;min-width:0;font-size:14px;color:var(--da-text-2)}
.da-execution{margin-bottom:18px}
.da-execution>summary{display:flex;align-items:center;gap:8px;width:fit-content;max-width:100%;cursor:pointer;list-style:none;border-radius:4px;padding:4px 0;color:var(--da-muted);font-size:13px}
.da-execution>summary::-webkit-details-marker{display:none}
.da-execution>summary:hover{color:var(--da-primary)}
.da-execution-time{font-variant-numeric:tabular-nums}
.da-disclosure-arrow{font-size:11px;transition:transform .15s}
.da-execution[open] .da-disclosure-arrow{transform:rotate(90deg)}
.da-execution-body{margin-top:12px;padding:16px 20px;background:var(--da-surface-2);border:1px solid var(--da-border);border-radius:8px}
.da-process-steps{list-style:none;margin:0;padding:0}
.da-process-steps li{display:grid;grid-template-columns:22px minmax(0,1fr);gap:10px;padding-bottom:18px;position:relative}
.da-process-steps li:last-child{padding-bottom:0}
.da-step-icon{display:flex;align-items:center;justify-content:center;width:20px;height:20px;background:var(--da-success-soft);color:var(--da-success);border-radius:50%;font-size:12px}
.is-needs_input .da-step-icon,.is-rejected .da-step-icon{background:var(--da-warning-soft);color:var(--da-warning)}
.is-skipped .da-step-icon{background:var(--da-tag-bg);color:var(--da-muted)}
.da-step-heading{display:flex;align-items:center;flex-wrap:wrap;gap:10px;line-height:20px;font-size:13px}
.da-step-heading b{font-weight:600;color:var(--da-text)}
.da-step-heading>span{font-size:12px;color:var(--da-muted)}
.da-step-heading time{margin-left:auto;color:var(--da-muted);font-size:12px;font-variant-numeric:tabular-nums}
.da-process-steps p{font-size:12px;line-height:1.7;margin:5px 0 0;overflow-wrap:anywhere;color:var(--da-muted)}
.da-process-mapping,.da-process-filters{margin-top:20px;padding-top:14px;border-top:1px solid var(--da-border)}
h4{margin:0 0 10px;font-size:12px;font-weight:600;color:var(--da-text-2)}
.da-process-mapping dl{display:grid;grid-template-columns:minmax(60px,120px) minmax(0,1fr);gap:8px 12px;margin:0;font-size:12px}
.da-process-mapping dd{margin:0;min-width:0}
code{font:12px/1.7 ui-monospace,SFMono-Regular,Consolas,monospace;white-space:pre-wrap;overflow-wrap:anywhere;color:var(--da-text-2)}
.da-process-filters code{display:block;margin:6px 0}
.da-process-notice{margin:14px 0 0;font-size:12px;color:var(--da-muted);line-height:1.7}
.da-semantic{background:var(--da-panel);border-radius:8px;padding:14px 16px}
.da-semantic-heading{display:flex;align-items:center;gap:7px;font-size:12px;color:var(--da-text-2)}
.da-semantic-heading .van-icon{color:var(--da-primary);font-size:15px}
.da-semantic-heading b{font-weight:600}
.da-semantic-heading small{margin-left:auto;font-size:11px;color:var(--da-muted)}
.da-semantic-fields{display:flex;flex-wrap:wrap;gap:12px 24px;margin:12px 0 0;font-size:13px;line-height:1.8}
.da-semantic-fields>div{display:flex;align-items:baseline;gap:10px;min-width:0;max-width:100%}
.da-semantic-fields dt{color:var(--da-muted);white-space:nowrap}
.da-semantic-fields dd{display:flex;flex-wrap:wrap;gap:4px 6px;margin:0;min-width:0;overflow-wrap:anywhere}
.da-semantic-tag{background:var(--da-tag-bg);padding:0 8px;border-radius:4px;color:var(--da-tag-text);overflow-wrap:anywhere;max-width:100%}
.da-semantic-tag.is-metric{color:var(--da-tag-primary-text);background:var(--da-tag-primary)}
.da-semantic-muted,.da-semantic-note{font-size:12px;color:var(--da-muted)}
.da-semantic-note{margin:10px 0 0;line-height:1.7}
.da-semantic-source{display:flex;align-items:center;flex-wrap:wrap;gap:6px 12px;margin-top:12px;padding-top:10px;border-top:1px solid var(--da-border);font-size:11px;color:var(--da-muted);overflow-wrap:anywhere}
@media(prefers-reduced-motion:reduce){.da-disclosure-arrow{transition:none}}
</style>
