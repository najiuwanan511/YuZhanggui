<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { generateQRCode, getQRCodeStatus, getQRCodeCookies } from '@/api/qrlogin'
import { showSuccess, showError } from '@/utils'
import type { QRLoginSession } from '@/types'

interface Props {
  modelValue: boolean
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void
  (e: 'success'): void
  (e: 'manual'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const isMobile = ref(false)
const checkScreenSize = () => {
  isMobile.value = window.innerWidth < 768
}

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
    checkScreenSize()
    window.addEventListener('resize', checkScreenSize)
    quickExpiryRetries = 0
    resetQRState()
    void generateQR()
  } else {
    window.removeEventListener('resize', checkScreenSize)
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
    statusText.value = automaticRetry ? '二维码仍不可用，请手动重新生成或导入 Cookie' : '生成二维码失败，请重试'
    showError(statusText.value)
  } finally {
    if (currentGeneration === generationId) isGenerating.value = false
  }
}

const startPolling = (currentGeneration: number, currentSessionId: string) => {
  if (!currentSessionId) return
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
            statusText.value = '登录成功！正在获取信息...'
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

    // 后端在二维码确认时已经保存账号和 Cookie；这里只校验会话确实返回了凭证。
    if (!cookieRes.data?.cookies) {
      showError('Cookie为空，请重试')
      handleClose()
      return
    }

    showSuccess('账号添加成功，连接正在后台恢复')
    emit('success')

    // 5. 关闭弹窗（无论成功失败都关闭）
    handleClose()

  } catch (error: any) {
    console.error('处理登录失败:', error)
    showError(error.message || '处理登录失败')
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

const switchToManual = () => {
  handleClose()
  emit('manual')
}
</script>

<template>
  <!-- iOS 风格弹窗背景 -->
  <transition name="ios-sheet-fade">
    <div v-if="modelValue" class="ios-sheet-overlay" @click="handleClose"></div>
  </transition>

  <!-- iOS 风格 Sheet 弹窗 -->
  <transition name="ios-sheet-slide">
    <div v-if="modelValue" class="ios-sheet">
      <!-- 弹窗头部 -->
      <div class="ios-sheet-header">
        <div class="ios-sheet-handle"></div>
        <div class="ios-sheet-title">扫码添加闲鱼账号</div>
        <button class="ios-sheet-close" @click="handleClose">✕</button>
      </div>

      <!-- 弹窗内容 -->
      <div class="ios-sheet-content">
        <div class="qr-code-container">
          <img v-if="qrCodeUrl" :src="qrCodeUrl" alt="二维码" class="qr-code" />
          <div v-else class="qr-skeleton">
            <div class="skeleton-line"></div>
          </div>
        </div>
        
        <p class="qr-tip">{{ status === 'verification_required' ? '请使用闲鱼APP扫描人脸验证二维码' : '请使用闲鱼APP扫描二维码登录' }}</p>
        <p v-if="status === 'verification_required' && qrCodeUrl" class="face-scan-warning">
          注意：这是第二次扫码！请再次使用闲鱼APP扫描上方二维码，并完成人脸识别。
        </p>
        
        <div class="qr-status">
          <div class="status-badge" :class="`status-${status}`">
            {{ statusText }}
          </div>
        </div>

        <a v-if="verificationUrl && showVerificationFallback" class="verification-link" :href="verificationUrl" target="_blank" rel="noopener noreferrer">打开安全验证页面</a>
        <p v-if="showVerificationFallback" class="verification-tip">自动人脸验证未能启动，可打开平台验证页面；若完成后仍无结果，请改用 Cookie 导入。</p>
        
        <p v-if="sessionId" class="session-id">会话ID: {{ sessionId }}</p>
      </div>

      <!-- 弹窗底部按钮 -->
      <div class="ios-sheet-footer">
        <button v-if="canRegenerate" class="ios-sheet-btn ios-sheet-btn--manual" :disabled="isGenerating" @click="regenerateQR">
          {{ isGenerating ? '生成中...' : '重新生成二维码' }}
        </button>
        <button v-if="status === 'verification_required' || canRegenerate" class="ios-sheet-btn ios-sheet-btn--manual" @click="switchToManual">
          改用 Cookie 导入
        </button>
        <button class="ios-sheet-btn ios-sheet-btn--cancel" @click="handleClose">
          取消
        </button>
      </div>
    </div>
  </transition>
</template>

<style scoped>
/* iOS 弹窗动画 */
.ios-sheet-fade-enter-active,
.ios-sheet-fade-leave-active {
  transition: opacity 0.3s ease;
}

.ios-sheet-fade-enter-from,
.ios-sheet-fade-leave-to {
  opacity: 0;
}

.ios-sheet-slide-enter-active,
.ios-sheet-slide-leave-active {
  transition: all 0.3s cubic-bezier(0.25, 0.1, 0.25, 1);
}

.ios-sheet-slide-enter-from,
.ios-sheet-slide-leave-to {
  transform: scale(0.95);
  opacity: 0;
}

/* iOS Sheet 背景遮罩 */
.ios-sheet-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0,0,0,0.20);
  z-index: 999;
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
}

/* iOS Sheet 容器 */
.ios-sheet {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  z-index: 1000;
  background: rgba(255,255,255,0.72);
  backdrop-filter: blur(40px) saturate(2);
  -webkit-backdrop-filter: blur(40px) saturate(2);
  border: 1px solid rgba(255,255,255,0.75);
  border-radius: 20px;
  box-shadow: 0 16px 48px rgba(0,0,0,0.16), 0 2px 8px rgba(0,0,0,0.08);
  display: flex;
  flex-direction: column;
  max-height: 85vh;
  width: 340px;
  max-width: 90vw;
  overflow: hidden;
}

/* iOS Sheet 头部 */
.ios-sheet-header {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16px 16px 12px;
  border-bottom: 0.5px solid rgba(60,60,67,.12);
  position: relative;
}

.ios-sheet-handle {
  display: none;
}

.ios-sheet-title {
  font-size: 17px;
  font-weight: 600;
  color: #1c1c1e;
  text-align: center;
}

.ios-sheet-close {
  position: absolute;
  top: 12px;
  right: 12px;
  width: 32px;
  height: 32px;
  border: none;
  background: rgba(0, 0, 0, 0.06);
  border-radius: 50%;
  font-size: 18px;
  color: #1c1c1e;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
  -webkit-tap-highlight-color: transparent;
}

.ios-sheet-close:active {
  background: rgba(0, 0, 0, 0.12);
}

/* iOS Sheet 内容 */
.ios-sheet-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px 16px;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.ios-sheet-content::-webkit-scrollbar {
  display: none;
}

.qr-code-container {
  display: flex;
  justify-content: center;
  margin: 16px 0;
}

.qr-code {
  width: 200px;
  height: 200px;
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.06);
}

.qr-skeleton {
  width: 200px;
  height: 200px;
  background: rgba(0, 0, 0, 0.06);
  border-radius: 12px;
  animation: skeleton-loading 1.5s infinite;
}

@keyframes skeleton-loading {
  0%, 100% {
    background: rgba(0, 0, 0, 0.06);
  }
  50% {
    background: rgba(0, 0, 0, 0.1);
  }
}

.skeleton-line {
  width: 100%;
  height: 100%;
  background: inherit;
}

.qr-tip {
  margin: 16px 0 12px;
  color: rgba(28,28,30,.55);
  font-size: 14px;
  text-align: center;
  line-height: 1.5;
}

.qr-status {
  display: flex;
  justify-content: center;
  margin: 12px 0;
}

.verification-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 38px;
  padding: 0 16px;
  border: 1px solid rgba(0, 122, 255, 0.24);
  border-radius: 6px;
  color: #007aff;
  font-size: 13px;
  text-decoration: none;
}

.verification-tip {
  margin: 8px 0 0;
  color: rgba(28, 28, 30, 0.58);
  font-size: 12px;
  line-height: 1.5;
  text-align: center;
}

.face-scan-warning {
  margin: 0 0 12px;
  color: #d70015;
  font-size: 14px;
  font-weight: 700;
  line-height: 1.55;
  text-align: center;
}

.status-badge {
  padding: 8px 16px;
  border-radius: 20px;
  font-size: 13px;
  font-weight: 500;
  text-align: center;
  min-width: 120px;
}

.status-pending,
.status-scanned {
  background: rgba(10,132,255,.15);
  color: #0A84FF;
}

.status-confirmed {
  background: rgba(48,209,88,.2);
  color: #30D158;
}

.status-expired {
  background: rgba(255,69,58,.15);
  color: #FF453A;
}

.session-id {
  margin: 12px 0;
  font-size: 12px;
  color: rgba(28,28,30,.55);
  text-align: center;
}

/* iOS Sheet 底部 */
.ios-sheet-footer {
  padding: 12px 16px;
  border-top: 0.5px solid rgba(60,60,67,.12);
  background: transparent;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ios-sheet-btn {
  flex: 1 1 130px;
  min-width: 0;
  height: 48px;
  border: none;
  border-radius: 12px;
  font-size: 16px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  -webkit-tap-highlight-color: transparent;
}

.ios-sheet-btn--manual {
  color: #007aff;
  border-color: rgba(0, 122, 255, 0.28);
}

.ios-sheet-btn--cancel {
  background: rgba(255,255,255,0.38);
  border: 1px solid rgba(255,255,255,0.35);
  color: #1c1c1e;
}

.ios-sheet-btn--cancel:active {
  background: rgba(255,255,255,0.55);
}

/* 手机端适配 */
@media screen and (max-width: 768px) {
  .ios-sheet {
    width: 320px;
    max-height: 85vh;
    max-width: 95vw;
  }

  .ios-sheet-header {
    padding: 14px 16px 12px;
  }

  .ios-sheet-title {
    font-size: 16px;
  }

  .ios-sheet-close {
    width: 30px;
    height: 30px;
    font-size: 16px;
  }

  .ios-sheet-content {
    padding: 16px 12px;
  }

  .qr-code {
    width: 160px;
    height: 160px;
  }

  .qr-skeleton {
    width: 160px;
    height: 160px;
  }

  .qr-tip {
    font-size: 13px;
    margin: 12px 0 10px;
  }

  .status-badge {
    font-size: 12px;
    padding: 6px 14px;
  }

  .session-id {
    font-size: 11px;
  }

  .ios-sheet-footer {
    padding: 10px 12px;
    flex-direction: column;
  }

  .ios-sheet-btn {
    height: 44px;
    font-size: 15px;
  }
}

@media screen and (max-width: 480px) {
  .ios-sheet {
    width: 300px;
    max-height: 80vh;
    max-width: 92vw;
  }

  .qr-code {
    width: 140px;
    height: 140px;
  }

  .qr-skeleton {
    width: 140px;
    height: 140px;
  }

  .qr-tip {
    font-size: 12px;
  }

  .ios-sheet-btn {
    height: 42px;
    font-size: 14px;
  }
}
</style>
