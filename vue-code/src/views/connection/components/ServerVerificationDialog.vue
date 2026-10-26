<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { closeVerification, startVerification, verificationInput, verificationState, type VerificationPoint, type VerificationView } from '@/api/verification'

const props = defineProps<{ modelValue: boolean; accountId: number; accountName?: string; accountUnb?: string }>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'updated', accountId: number): void
  (e: 'manual'): void
}>()
const view = ref<VerificationView | null>(null)
const error = ref('')
const starting = ref(false)
const dragging = ref(false)
const sending = ref(false)
const pointer = ref<{ left: string; top: string } | null>(null)
const terminal = new Set(['CONNECTED', 'VERIFICATION_REQUIRED', 'FAILED', 'CLOSED', 'EXPIRED'])
const ready = computed(() => view.value?.state === 'WAITING' && !!view.value.image && !sending.value)
const busy = computed(() => starting.value || ['STARTING', 'APPLYING', 'RECONNECTING', 'CLOSING'].includes(view.value?.state || ''))
let generation = 0
let timer: number | null = null
let points: VerificationPoint[] = []
let pointerId: number | null = null
let downAt = 0
let notifiedSession = ''

const clearTimer = () => { if (timer !== null) window.clearTimeout(timer); timer = null }
const release = async (session: VerificationView | null) => {
  if (!session || terminal.has(session.state)) return
  try { await closeVerification(session.accountId, session.sessionId) } catch { /* it may already have expired */ }
}
const accept = (next: VerificationView) => {
  if (view.value?.sessionId === next.sessionId && next.revision < view.value.revision) return
  view.value = next
  if (terminal.has(next.state)) {
    clearTimer()
    dragging.value = false
    pointer.value = null
    if (next.sessionId !== notifiedSession) {
      notifiedSession = next.sessionId
      emit('updated', next.accountId)
    }
  }
}
const schedule = (current: number) => {
  clearTimer()
  if (current === generation && view.value && !terminal.has(view.value.state) && !dragging.value) {
    timer = window.setTimeout(() => void poll(current), 1000)
  }
}
const poll = async (current: number) => {
  const session = view.value
  if (!session || current !== generation) return
  try {
    const response = await verificationState(session.accountId, session.sessionId)
    if (current !== generation) return
    if ((response.code !== 0 && response.code !== 200) || !response.data) throw new Error(response.msg || '获取验证画面失败')
    accept(response.data)
    schedule(current)
  } catch (failure: unknown) {
    if (current === generation) error.value = failure instanceof Error ? failure.message : '验证服务连接中断，请重新加载'
  }
}
const start = async () => {
  const current = ++generation
  const accountId = props.accountId
  const previous = view.value
  clearTimer()
  view.value = null
  error.value = ''
  dragging.value = false
  pointer.value = null
  pointerId = null
  sending.value = false
  starting.value = true
  try {
    await release(previous)
    if (current !== generation || !props.modelValue) return
    const response = await startVerification(accountId)
    if (current !== generation || !props.modelValue) {
      if (response.data) void release(response.data)
      return
    }
    if ((response.code !== 0 && response.code !== 200) || !response.data) throw new Error(response.msg || '启动验证失败')
    accept(response.data)
    schedule(current)
  } catch (failure: unknown) {
    if (current === generation) error.value = failure instanceof Error ? failure.message : '启动验证失败'
  } finally { if (current === generation) starting.value = false }
}

const sample = (event: PointerEvent, element: HTMLElement): VerificationPoint => {
  const bounds = element.getBoundingClientRect()
  const width = view.value?.width || 1080
  const height = view.value?.height || 720
  const x = Math.max(0, Math.min(width, (event.clientX - bounds.left) * width / bounds.width))
  const y = Math.max(0, Math.min(height, (event.clientY - bounds.top) * height / bounds.height))
  pointer.value = { left: `${x / width * 100}%`, top: `${y / height * 100}%` }
  return { x, y, elapsedMs: Math.max(0, Math.round(performance.now() - downAt)) }
}
const pointerDown = (event: PointerEvent) => {
  if (!ready.value || !event.isPrimary || event.button !== 0) return
  const element = event.currentTarget as HTMLElement
  event.preventDefault()
  clearTimer()
  downAt = performance.now()
  pointerId = event.pointerId
  points = [{ ...sample(event, element), elapsedMs: 0 }]
  dragging.value = true
  element.setPointerCapture(event.pointerId)
}
const pointerMove = (event: PointerEvent) => {
  if (!dragging.value || event.pointerId !== pointerId) return
  const point = sample(event, event.currentTarget as HTMLElement)
  if (point.elapsedMs <= 8000 && point.elapsedMs - points[points.length - 1]!.elapsedMs >= 25) points.push(point)
}
const pointerCancel = () => {
  dragging.value = false
  pointer.value = null
  pointerId = null
  points = []
  schedule(generation)
}
const pointerUp = async (event: PointerEvent) => {
  if (!dragging.value || event.pointerId !== pointerId || !view.value) return
  const element = event.currentTarget as HTMLElement
  points.push(sample(event, element))
  element.releasePointerCapture(event.pointerId)
  dragging.value = false
  pointer.value = null
  pointerId = null
  if (points[points.length - 1]!.elapsedMs > 8000) {
    error.value = '单次操作超过 8 秒，请重新操作'
    schedule(generation)
    return
  }
  // Send only points actually recorded from the user's pointer; no generated movement.
  const count = Math.min(160, points.length)
  const actual = Array.from({ length: count }, (_, i) => points[Math.round(i * (points.length - 1) / (count - 1))]!)
  const session = view.value
  const current = generation
  sending.value = true
  error.value = ''
  try {
    const response = await verificationInput(session.accountId, session.sessionId, actual)
    if (current !== generation) return
    if ((response.code !== 0 && response.code !== 200) || !response.data) throw new Error(response.msg || '传递操作失败')
    accept(response.data)
  } catch (failure: unknown) {
    if (current === generation) error.value = failure instanceof Error ? failure.message : '传递操作失败，请重试'
  } finally {
    if (current === generation) { sending.value = false; schedule(current) }
  }
}
const stop = () => {
  generation++
  clearTimer()
  dragging.value = false
  pointer.value = null
  const previous = view.value
  view.value = null
  void release(previous)
}
const close = () => { stop(); emit('update:modelValue', false) }
const manual = () => { close(); emit('manual') }
watch(() => [props.modelValue, props.accountId] as const, ([visible]) => {
  if (visible && props.accountId) void start(); else stop()
}, { immediate: true })
onBeforeUnmount(stop)
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="server-verification-overlay">
      <section class="server-verification" role="dialog" aria-modal="true" aria-labelledby="server-verification-title">
        <header>
          <div><h2 id="server-verification-title">完成账号安全验证</h2><p>{{ accountName || accountUnb || `账号 ${accountId}` }}<span v-if="accountUnb"> · {{ accountUnb }}</span></p></div>
          <button type="button" aria-label="关闭安全验证" @click="close">×</button>
        </header>
        <p class="verification-hint">这是当前账号在服务器上的验证画面。请直接点击或拖动下方画面完成验证，凭证会自动保存，无需再次扫码。</p>
        <p class="verification-message" :class="{ success: view?.state === 'CONNECTED' }" role="status">{{ view?.message || (starting ? '正在启动服务器验证…' : '等待启动验证') }}</p>
        <p v-if="error" class="verification-error" role="alert">{{ error }}</p>
        <div v-if="view?.image" class="verification-screen" :class="{ disabled: !ready }"
          :aria-busy="busy || sending" @pointerdown="pointerDown" @pointermove="pointerMove"
          @pointerup="pointerUp" @pointercancel="pointerCancel">
          <img :src="view.image" alt="服务器平台验证画面，请在画面上手动操作" draggable="false" />
          <span v-if="pointer" class="verification-pointer" :style="pointer"></span>
        </div>
        <div v-else-if="busy" class="verification-loading">正在准备画面或检查连接，请稍候…</div>
        <p v-if="dragging" class="verification-hint">松开后会将这次操作传递到服务器，请等待画面更新。</p>
        <footer>
          <button type="button" class="verification-manual" @click="manual">手动导入 Cookie</button>
          <button v-if="view?.state !== 'CONNECTED'" type="button" :disabled="(busy && !error) || sending" @click="start">重新加载验证</button>
          <button type="button" class="verification-primary" @click="close">{{ view?.state === 'CONNECTED' ? '完成' : '关闭' }}</button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.server-verification-overlay { position: fixed; inset: 0; z-index: 2200; display: flex; align-items: center; justify-content: center; padding: 16px; background: #15263b66; }
.server-verification { box-sizing: border-box; width: min(100%, 980px); max-height: calc(100dvh - 32px); overflow: auto; padding: 22px; border-radius: 18px; background: white; color: #243247; box-shadow: 0 20px 60px #0003; }
header { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
h2 { margin: 0; font-size: 19px; }
header p { margin: 6px 0 0; color: #6b7789; font-size: 13px; overflow-wrap: anywhere; }
header button { border: 0; background: none; color: #6b7789; font-size: 24px; cursor: pointer; }
.verification-hint { color: #64748b; font-size: 13px; line-height: 1.7; }
.verification-message { padding: 10px 12px; background: #f0f6ff; border-radius: 8px; font-size: 14px; line-height: 1.6; }
.verification-message.success { color: #198449; background: #ecfbf1; }
.verification-error { color: #b42318; font-size: 13px; }
.verification-screen { position: relative; width: 100%; line-height: 0; touch-action: none; user-select: none; border: 1px solid #dce4ee; box-sizing: border-box; overflow: hidden; border-radius: 8px; cursor: crosshair; }
.verification-screen img { display: block; width: 100%; height: auto; pointer-events: none; }
.verification-screen.disabled { cursor: wait; opacity: .8; }
.verification-pointer { position: absolute; width: 20px; height: 20px; border: 2px solid #2563eb; border-radius: 50%; transform: translate(-50%, -50%); background: #2563eb33; pointer-events: none; }
.verification-loading { display: grid; place-items: center; min-height: 160px; color: #64748b; }
footer { display: flex; flex-wrap: wrap; justify-content: flex-end; align-items: center; gap: 10px; margin-top: 18px; }
footer button { padding: 9px 16px; border: 1px solid #dce4ee; border-radius: 8px; background: white; color: inherit; cursor: pointer; }
footer button:disabled { opacity: .5; cursor: wait; }
footer .verification-manual { margin-right: auto; border: 0; color: #2563eb; }
footer .verification-primary { background: #ffc400; border-color: #ffc400; }
@media (max-width: 600px) { .server-verification { padding: 14px; } .server-verification-overlay { padding: 8px; } }
</style>
