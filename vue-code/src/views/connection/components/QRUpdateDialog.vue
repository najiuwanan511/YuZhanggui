<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { generateQRCode, getQRCodeStatus, getQRCodeCookies } from '@/api/qrlogin'
import { showSuccess, showError } from '@/utils'
import type { QRLoginSession } from '@/types'

interface Props {
  modelValue: boolean
  accountId: number
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void
  (e: 'success'): void
  (e: 'manual-update'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const qrCodeUrl = ref('')
const sessionId = ref('')
const status = ref<QRLoginSession['status']>('pending')
const statusText = ref('正在生成二维码...')
const verificationUrl = ref('')
const isGenerating = ref(false)
const generationFailed = ref(false)
const canRegenerate = computed(() => generationFailed.value || ['expired', 'cancelled', 'error', 'not_found'].includes(status.value))
const showVerificationFallback = computed(() =>
  status.value === 'verification_required' &&
  !qrCodeUrl.value &&
  (statusText.value.includes('失败') || statusText.value.includes('未返回'))
)
let pollTimer: number | null = null
let pollRequestPending = false
let generationId = 0
let generatedAt = 0
let quickExpiryRetries = 0

watch(() => props.modelValue, (newVal) => {
  if (newVal) {
    quickExpiryRetries = 0
    resetQRState()
    void generateQR()
  } else {
    stopPolling()
    generationId++
  }
})

const resetQRState = () => {
  stopPolling()
  qrCodeUrl.value = ''
  sessionId.value = ''
  status.value = 'pending'
  statusText.value = '正在生成二维码...'
  verificationUrl.value = ''
  generationFailed.value = false
  generatedAt = 0
}

const generateQR = async (automaticRetry = false) => {
  const currentGeneration = ++generationId
  resetQRState()
  isGenerating.value = true
  try {
    const response = await generateQRCode()
    if (currentGeneration !== generationId || !props.modelValue) return
    if (response.code === 0 || response.code === 200) {
      qrCodeUrl.value = response.data?.qrCodeUrl || ''
      sessionId.value = response.data?.sessionId || ''
      if (!response.data?.success || !qrCodeUrl.value || !sessionId.value) {
        throw new Error(response.data?.message || '二维码数据不完整')
      }
      generatedAt = Date.now()
      statusText.value = '等待扫码...'
      startPolling(currentGeneration, sessionId.value)
    } else {
      throw new Error(response.msg || '生成二维码失败')
    }
  } catch (error: any) {
    if (currentGeneration !== generationId || !props.modelValue) return
    console.error('生成二维码失败:', error)
    generationFailed.value = true
    statusText.value = automaticRetry ? '二维码仍不可用，请手动重新生成或更新 Cookie' : '生成二维码失败，请重试'
    showError(statusText.value)
  } finally {
    if (currentGeneration === generationId) isGenerating.value = false
  }
}

const startPolling = (currentGeneration: number, currentSessionId: string) => {
  if (!currentSessionId) {
    showError('会话ID为空，无法查询状态')
    return
  }
  stopPolling()
  pollTimer = window.setInterval(async () => {
    if (currentGeneration !== generationId || !props.modelValue || pollRequestPending) return
    pollRequestPending = true
    try {
      const response = await getQRCodeStatus(currentSessionId)
      if (currentGeneration !== generationId || currentSessionId !== sessionId.value) return
      if (response.code === 0 || response.code === 200) {
        const data = response.data
        status.value = data?.status || 'pending'

        switch (data?.status) {
          case 'pending':
            statusText.value = '等待扫码...'
            break
          case 'scanned':
            statusText.value = '已扫码，等待确认...'
            break
          case 'confirmed':
            statusText.value = '登录成功！正在更新Cookie和Token...'
            stopPolling()
            await handleLoginSuccess()
            break
          case 'expired':
            statusText.value = '二维码已过期'
            stopPolling()
            if (Date.now() - generatedAt < 30000 && quickExpiryRetries < 1) {
              quickExpiryRetries++
              statusText.value = '二维码失效，正在自动换一张...'
              void generateQR(true)
            }
            break
          case 'verification_required':
            verificationUrl.value = data.verificationUrl || verificationUrl.value
            qrCodeUrl.value = data.qrCodeUrl || ''
            statusText.value = data.message || '正在准备人脸验证二维码...'
            break
          case 'cancelled':
          case 'error':
          case 'not_found':
            statusText.value = data.message || '扫码登录未完成，请重新扫码'
            stopPolling()
            break
        }
      }
    } catch (error) {
      console.error('检查登录状态失败:', error)
    } finally {
      pollRequestPending = false
    }
  }, 2000)
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  pollRequestPending = false
}

const handleLoginSuccess = async () => {
  try {
    // 1. 获取Cookie
    const cookieRes = await getQRCodeCookies(sessionId.value)
    if (cookieRes.code !== 0 && cookieRes.code !== 200) {
      showError(cookieRes.msg || '获取Cookie失败')
      handleClose()
      return
    }

    // 2. 直接使用返回的Cookie字符串和UNB
    const cookieText = cookieRes.data?.cookies || ''
    if (!cookieText) {
      showError('Cookie为空，请重试')
      handleClose()
      return
    }

    // 二维码确认时后端已经保存 Cookie，并启动该账号的凭证恢复流程。
    // 这里不要再次调用 /websocket/updateCookie，否则会并发触发第二次重连。
    showSuccess('扫码成功，Cookie 已保存，连接正在后台恢复')

    emit('success')
    handleClose()

  } catch (error: any) {
    console.error('更新失败:', error)
    showError(error.message || '更新失败')
    handleClose()
  }
}

const handleClose = () => {
  stopPolling()
  generationId++
  emit('update:modelValue', false)
}

const regenerateQR = () => {
  quickExpiryRetries = 0
  void generateQR()
}

const switchToManualUpdate = () => {
  handleClose()
  emit('manual-update')
}
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="modelValue" class="modal-overlay" @click.self="handleClose">
        <div class="modal-container">
          <div class="modal-header">
            <h2 class="modal-title">扫码更新 Cookie 和 Token</h2>
            <button class="modal-close" @click="handleClose">×</button>
          </div>
          <div class="modal-body">
            <div class="qr-code-wrap">
              <img v-if="qrCodeUrl" :src="qrCodeUrl" alt="二维码" class="qr-code" />
              <div v-else class="qr-loading"><div class="loading-spinner"></div></div>
            </div>
            <p class="qr-tip">{{ status === 'verification_required' ? '请使用闲鱼APP扫描人脸验证二维码' : '请使用闲鱼APP扫描二维码完成更新' }}</p>
            <p v-if="status === 'verification_required' && qrCodeUrl" class="face-scan-warning">
              注意：这是第二次扫码！请再次使用闲鱼APP扫描上方二维码，并完成人脸识别。
            </p>
            <div class="qr-status">
              <span class="status-tag" :class="status === 'confirmed' ? 'is-success' : ''">{{ statusText }}</span>
            </div>
            <a v-if="verificationUrl && showVerificationFallback" class="verification-link" :href="verificationUrl" target="_blank" rel="noopener noreferrer">打开安全验证页面</a>
            <p v-if="showVerificationFallback" class="verification-tip">自动人脸验证未能启动，可打开平台验证页面；若完成后仍无结果，请改用 Cookie 更新。</p>
            <p v-if="sessionId" class="session-id">会话ID: {{ sessionId }}</p>
          </div>
          <div class="modal-footer">
            <button v-if="canRegenerate" class="btn btn-primary" :disabled="isGenerating" @click="regenerateQR">
              {{ isGenerating ? '生成中...' : '重新生成二维码' }}
            </button>
            <button v-if="canRegenerate" class="btn btn-secondary" @click="switchToManualUpdate">改用 Cookie 更新</button>
            <button v-if="status === 'verification_required'" class="btn btn-secondary" @click="switchToManualUpdate">改用 Cookie 更新</button>
            <button class="btn btn-secondary" @click="handleClose">取消</button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.20);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 24px;
}

.modal-container {
  background: rgba(255,255,255,0.72);
  border-radius: 20px;
  width: 100%;
  max-width: 360px;
  box-shadow: 0 32px 100px rgba(0, 0, 0, 0.14), 0 12px 32px rgba(0, 0, 0, 0.1);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  flex-shrink: 0;
}

.modal-title {
  font-size: 15px;
  font-weight: 600;
  color: #1c1c1e;
  margin: 0;
}

.modal-close {
  width: 26px;
  height: 26px;
  border-radius: 7px;
  border: none;
  background: transparent;
  color: rgba(28,28,30,.55);
  font-size: 18px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
}

.modal-close:hover {
  background: rgba(60,60,67,.12);
  color: #1c1c1e;
}

.modal-body {
  padding: 0 20px 20px;
  text-align: center;
}

.modal-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  flex-shrink: 0;
}

.btn {
  flex: 1 1 130px;
  min-width: 0;
  padding: 8px 18px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  border: none;
}

.btn-secondary {
  background: rgba(60,60,67,.12);
  color: #1c1c1e;
}

.btn-secondary:hover {
  background: rgba(0, 0, 0, 0.1);
}

.qr-code-wrap {
  margin: 16px 0;
  display: flex;
  justify-content: center;
}

.qr-code {
  max-width: 180px;
  border-radius: 12px;
}

.qr-loading {
  width: 180px;
  height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f5f7;
  border-radius: 12px;
}

.loading-spinner {
  width: 28px;
  height: 28px;
  border: 2px solid #e5e5e5;
  border-top-color: #0071e3;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.qr-tip {
  margin: 8px 0;
  color: #1c1c1e;
  font-size: 14px;
}

.qr-status {
  margin: 10px 0;
  min-height: 28px;
  display: flex;
  justify-content: center;
  align-items: center;
}

.verification-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 36px;
  padding: 0 14px;
  border: 1px solid rgba(0, 122, 255, 0.24);
  border-radius: 6px;
  color: #007aff;
  font-size: 12px;
  text-decoration: none;
}

.verification-tip {
  margin: 7px 0 0;
  color: rgba(28, 28, 30, 0.58);
  font-size: 11px;
  line-height: 1.5;
}

.face-scan-warning {
  margin: 0 0 10px;
  color: #d70015;
  font-size: 14px;
  font-weight: 700;
  line-height: 1.55;
  text-align: center;
}

.status-tag {
  padding: 5px 12px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  background: rgba(255,255,255,0.38);
  color: rgba(28,28,30,.55);
}

.status-tag.is-success {
  background: rgba(52, 199, 89, 0.1);
  color: #30D158;
}

.session-id {
  margin: 8px 0;
  font-size: 11px;
  color: rgba(28,28,30,.55);
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s ease;
}

.modal-enter-active .modal-container,
.modal-leave-active .modal-container {
  transition: transform 0.3s cubic-bezier(0.32, 0.94, 0.6, 1), opacity 0.2s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .modal-container,
.modal-leave-to .modal-container {
  transform: scale(0.92) translateY(8px);
  opacity: 0;
}
</style>
