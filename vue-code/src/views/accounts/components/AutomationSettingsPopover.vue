<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { updateAccount } from '@/api/account'
import { getItemPolishOverview, saveItemPolishConfig } from '@/api/item-polish'
import { showSuccess } from '@/utils'
import type { Account } from '@/types'

const props = defineProps<{
  account: Account
  section: 'rate' | 'flower' | 'polish'
  anchor: HTMLElement
}>()
const emit = defineEmits<{
  (e: 'close'): void
  (e: 'success'): void
  (e: 'busy', value: boolean): void
}>()

const title = computed(() => ({ rate: '自动评价', flower: '自动求花', polish: '每日擦亮' })[props.section])
const enabled = ref((props.section === 'rate' ? props.account.autoRateEnabled
  : props.section === 'flower' ? props.account.autoAskFlower : props.account.itemPolishEnabled) === 1)
const rateText = ref(props.account.autoRateText || '')
const scheduleTime = ref(props.account.itemPolishScheduleTime || '09:00')
const loading = ref(false)
const loadError = ref('')
const saveError = ref('')
const saving = ref(false)
const panel = ref<HTMLElement | null>(null)
const position = ref({ left: '12px', top: '12px' })
let observer: ResizeObserver | null = null
let disposed = false

const errorText = (error: unknown) => error instanceof Error ? error.message : '请稍后重试'

const updatePosition = () => {
  if (!panel.value) return
  if (!props.anchor.isConnected) {
    close()
    return
  }
  const anchor = props.anchor.getBoundingClientRect()
  const { width, height } = panel.value.getBoundingClientRect()
  const margin = 12
  const below = anchor.bottom + 8
  const top = below + height <= window.innerHeight - margin ? below : anchor.top - height - 8
  position.value = {
    left: `${Math.max(margin, Math.min(anchor.left, window.innerWidth - width - margin))}px`,
    top: `${Math.max(margin, Math.min(top, window.innerHeight - height - margin))}px`
  }
}

const close = (restoreFocus = false) => {
  if (saving.value) return
  if (restoreFocus && props.anchor.isConnected) props.anchor.focus()
  emit('close')
}

const onOutsideClick = (event: PointerEvent) => {
  if (event.target instanceof Node && !panel.value?.contains(event.target)
      && !props.anchor.contains(event.target)) close()
}

const onKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Escape') {
    event.preventDefault()
    close(true)
  }
}

const loadPolish = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const response = await getItemPolishOverview(props.account.id, 1)
    if (response.code !== 0 && response.code !== 200) throw new Error(response.msg || '加载失败')
    if (disposed) return
    enabled.value = response.data?.config?.enabled === 1
    scheduleTime.value = response.data?.config?.scheduleTime || '09:00'
  } catch (error: unknown) {
    if (!disposed) loadError.value = `加载擦亮设置失败：${errorText(error)}`
  } finally {
    if (!disposed) loading.value = false
  }
}

const save = async () => {
  if (saving.value || loading.value || loadError.value) return
  if (props.section === 'polish' && !/^([01]\d|2[0-3]):[0-5]\d$/.test(scheduleTime.value)) {
    saveError.value = '请选择有效的每日执行时间'
    return
  }
  saving.value = true
  emit('busy', true)
  saveError.value = ''
  try {
    // 只保存当前功能，避免覆盖账号备注、其他开关或未加载的擦亮配置。
    const response = props.section === 'polish'
      ? await saveItemPolishConfig({ accountId: props.account.id, enabled: enabled.value ? 1 : 0, scheduleTime: scheduleTime.value })
      : await updateAccount(props.section === 'rate'
        ? { id: props.account.id, autoRateEnabled: enabled.value ? 1 : 0, autoRateText: rateText.value }
        : { id: props.account.id, autoAskFlower: enabled.value ? 1 : 0 })
    if (response.code !== 0 && response.code !== 200) throw new Error(response.msg || '保存失败')
    if (disposed) return
    showSuccess(`${title.value}设置已保存`)
    props.anchor.focus()
    emit('close')
    emit('success')
  } catch (error: unknown) {
    if (!disposed) saveError.value = `保存失败：${errorText(error)}`
  } finally {
    emit('busy', false)
    if (!disposed) saving.value = false
  }
}

watch(() => props.anchor, () => nextTick(updatePosition))

onMounted(async () => {
  if (props.section === 'polish') void loadPolish()
  document.addEventListener('pointerdown', onOutsideClick)
  document.addEventListener('keydown', onKeydown)
  window.addEventListener('resize', updatePosition)
  window.addEventListener('scroll', updatePosition, true)
  observer = new ResizeObserver(updatePosition)
  if (panel.value) observer.observe(panel.value)
  await nextTick()
  if (disposed) return
  updatePosition()
  panel.value?.focus()
})

onUnmounted(() => {
  disposed = true
  observer?.disconnect()
  document.removeEventListener('pointerdown', onOutsideClick)
  document.removeEventListener('keydown', onKeydown)
  window.removeEventListener('resize', updatePosition)
  window.removeEventListener('scroll', updatePosition, true)
})
</script>

<template>
  <Teleport to="body">
    <section ref="panel" class="automation-popover" :style="position" role="dialog"
      aria-labelledby="automation-popover-title" :aria-busy="loading || saving" tabindex="-1">
      <header class="automation-popover__header">
        <div>
          <h2 id="automation-popover-title">{{ title }}</h2>
          <p>{{ account.accountNote || account.unb || `账号 ${account.id}` }}</p>
        </div>
        <button type="button" class="automation-popover__close" aria-label="关闭设置" :disabled="saving" @click="close(true)">×</button>
      </header>
      <form @submit.prevent="save">
        <p v-if="loading" class="automation-popover__hint" role="status">正在加载设置…</p>
        <div v-else-if="loadError" class="automation-popover__error" role="alert">
          {{ loadError }}
          <button type="button" class="automation-popover__retry" @click="loadPolish">重新加载</button>
        </div>
        <fieldset v-else :disabled="saving">
          <label class="automation-popover__toggle">
            <span>启用{{ title }}</span>
            <input v-model="enabled" type="checkbox" role="switch" :aria-checked="enabled" />
            <span class="automation-popover__track" aria-hidden="true"></span>
          </label>
          <template v-if="section === 'rate'">
            <label class="automation-popover__label" for="automation-rate-text">评价文案</label>
            <textarea id="automation-rate-text" v-model="rateText" rows="3" placeholder="留空使用默认文案：不错的买家！"></textarea>
            <p class="automation-popover__hint">订单完成后自动评价买家。</p>
          </template>
          <p v-else-if="section === 'flower'" class="automation-popover__hint">确认发货成功后自动请求小红花，不额外发送聊天消息。</p>
          <template v-else>
            <label class="automation-popover__label" for="automation-polish-time">每日执行时间</label>
            <input id="automation-polish-time" v-model="scheduleTime" type="time" required />
            <p class="automation-popover__hint">每天先同步在售商品，再逐件擦亮。</p>
          </template>
        </fieldset>
        <p v-if="saveError" class="automation-popover__error" role="alert">{{ saveError }}</p>
        <footer class="automation-popover__footer">
          <button type="button" :disabled="saving" @click="close(true)">取消</button>
          <button type="submit" class="automation-popover__save" :disabled="loading || !!loadError || saving">{{ saving ? '保存中…' : '保存' }}</button>
        </footer>
      </form>
    </section>
  </Teleport>
</template>

<style scoped>
.automation-popover {
  position: fixed;
  z-index: 2100;
  box-sizing: border-box;
  width: min(320px, calc(100vw - 24px));
  max-height: calc(100dvh - 24px);
  overflow-y: auto;
  padding: 18px;
  border: 1px solid #e3e8ef;
  border-radius: 14px;
  background: #fff;
  color: #243247;
  box-shadow: 0 12px 40px rgba(30, 42, 60, .18);
  outline: none;
  font-size: 13px;
  line-height: 1.5;
}
.automation-popover__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 18px; }
.automation-popover__header > div { min-width: 0; }
.automation-popover h2 { margin: 0; font-size: 16px; font-weight: 700; }
.automation-popover__header p { margin: 3px 0 0; color: #748196; overflow-wrap: anywhere; }
.automation-popover button { font: inherit; cursor: pointer; }
.automation-popover button:disabled { opacity: .5; cursor: not-allowed; }
.automation-popover__close { flex: 0 0 auto; border: 0; background: transparent; color: #748196; font-size: 22px !important; line-height: 1; padding: 2px 4px; }
.automation-popover fieldset { min-width: 0; margin: 0; padding: 0; border: 0; }
.automation-popover__toggle { position: relative; display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; cursor: pointer; }
.automation-popover__toggle input { position: absolute; right: 0; width: 36px; height: 22px; margin: 0; opacity: 0; }
.automation-popover__track { display: block; width: 36px; height: 22px; border-radius: 20px; background: #d7dce3; pointer-events: none; }
.automation-popover__track::after { content: ''; display: block; width: 18px; height: 18px; margin: 2px; border-radius: 50%; background: #fff; box-shadow: 0 1px 3px #0002; }
.automation-popover__toggle input:checked + .automation-popover__track { background: #34c759; }
.automation-popover__toggle input:checked + .automation-popover__track::after { transform: translateX(14px); }
.automation-popover__toggle input:focus-visible + .automation-popover__track { outline: 2px solid #3972bd; outline-offset: 3px; }
.automation-popover__label { display: block; margin-bottom: 6px; font-weight: 600; }
.automation-popover textarea, .automation-popover input[type='time'] { box-sizing: border-box; width: 100%; padding: 9px 10px; border: 1px solid #dce2ea; border-radius: 8px; background: #f9fafb; color: inherit; font: inherit; }
.automation-popover textarea { resize: vertical; min-height: 80px; }
.automation-popover__hint { margin: 10px 0 0; color: #748196; font-size: 12px; }
.automation-popover__error { margin: 10px 0; color: #c43732; overflow-wrap: anywhere; }
.automation-popover__retry { display: block; margin-top: 8px; padding: 0; border: 0; background: transparent; color: #3972bd; }
.automation-popover__footer { display: flex; justify-content: flex-end; gap: 8px; margin-top: 20px; }
.automation-popover__footer button { padding: 7px 18px; border: 1px solid #dce2ea; border-radius: 8px; background: #fff; color: inherit; }
.automation-popover__footer .automation-popover__save { border-color: #ffc400; background: #ffc400; color: #332900; font-weight: 600; }
.automation-popover :is(button, textarea, input[type='time']):focus-visible { outline: 2px solid #3972bd; outline-offset: 2px; }
</style>
