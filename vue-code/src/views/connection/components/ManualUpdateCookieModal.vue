<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { updateCookie } from '@/api/websocket'
import { showInfo, showError } from '@/utils'
import IconClose from '@/components/icons/IconClose.vue'
import IconHelp from '@/components/icons/IconHelp.vue'

interface Props {
  modelValue: boolean
  accountId: number
  currentCookie: string
  accountUnb?: string
  verificationRequired?: boolean
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void
  (e: 'success'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const cookieText = ref('')
const loading = ref(false)
const showHelpGuide = ref(false)
const missingVerificationCookie = computed(() => props.verificationRequired && cookieText.value.trim()
  && !/(?:^|;)\s*x5sec\s*=\s*[^;\s]+/i.test(cookieText.value))
const submitError = ref('')

watch(() => props.modelValue, (newVal) => {
  if (newVal) {
    cookieText.value = props.currentCookie || ''
    showHelpGuide.value = false
    submitError.value = ''
  }
})

const handleSubmit = async () => {
  if (!cookieText.value.trim()) {
    showError('Cookie不能为空')
    return
  }

  loading.value = true
  submitError.value = ''
  try {
    const response = await updateCookie({
      xianyuAccountId: props.accountId,
      cookieText: cookieText.value.trim()
    })

    if (response.code === 0 || response.code === 200) {
      showInfo(response.data?.message || 'Cookie 已保存，正在检查消息连接状态')
      handleClose()
      emit('success')
    } else {
      submitError.value = response.msg || 'Cookie 更新未完成，请重试'
    }
  } catch (error: unknown) {
    // Axios errors can contain the submitted Cookie; never print the request object.
    submitError.value = error instanceof Error ? error.message : 'Cookie 更新失败，请重试'
  } finally {
    loading.value = false
  }
}

const handleClose = () => {
  emit('update:modelValue', false)
  showHelpGuide.value = false
}

const toggleHelpGuide = () => {
  showHelpGuide.value = !showHelpGuide.value
}
</script>

<template>
  <Transition name="modal-fade">
    <div v-if="modelValue" class="modal-overlay" @click="handleClose">
      <div class="modal-container" @click.stop>
        <!-- Header -->
        <div class="modal-header">
          <h2 class="modal-title">手动更新Cookie</h2>
          <button class="help-link" @click="toggleHelpGuide">
            <IconHelp />
            <span>不会获取Cookie？</span>
          </button>
          <button class="modal-close" @click="handleClose">
            <IconClose />
          </button>
        </div>

        <!-- Content -->
        <div class="modal-content">
          <div v-if="verificationRequired" class="tips-container">
            <div class="tips-title">导入刚完成验证的浏览器凭证</div>
            <div class="tips-content">
              <p v-if="accountUnb" class="tips-text">当前账号 UNB：{{ accountUnb }}，请确认网页登录的是这个账号。</p>
              <p class="tips-text">请使用同一浏览器中验证后的完整 Cookie，包含 x5sec。重新扫码生成的 Cookie 不会带回这次网页验证结果。</p>
            </div>
          </div>
          <!-- Cookie 获取步骤 -->
          <div v-if="showHelpGuide" class="tips-container">
            <div class="tips-title">Cookie 获取步骤</div>
            <div class="tips-content">
              <ol class="tips-text">
                <li>在刚完成验证的同一浏览器中打开闲鱼消息页面，确认登录账号一致。</li>
                <li>按 F12，选择 Network（网络），刷新消息页面。</li>
                <li>筛选 pc.login.token，选择发送到 h5api.m.goofish.com 的请求。</li>
                <li>在 Headers → Request Headers 中找到 Cookie，复制整段值（不是响应里的 Set-Cookie）。</li>
                <li>回到此处粘贴并确定更新；消息连接结果以账号详情为准。</li>
              </ol>
              <p class="tips-text">Cookie 仅用于当前私有服务，请勿截图、分享或提交到代码仓库。</p>
            </div>
          </div>

          <!-- Form -->
          <div v-else class="form-container">
            <div class="form-group">
              <label class="form-label">Cookie值</label>
              <textarea
                v-model="cookieText"
                class="form-textarea"
                placeholder="请输入完整的Cookie字符串"
                rows="8"
              />
              <p v-if="missingVerificationCookie" class="tips-text" role="status">当前内容未包含 x5sec，请核对是否复制了刚完成验证的 h5api.m.goofish.com 请求 Cookie。</p>
              <p v-if="submitError" class="tips-text" role="alert">{{ submitError }}</p>
            </div>

            <!-- Tips -->
            <div class="tips-container">
              <div class="tips-title">提示</div>
              <div class="tips-content">
                <p class="tips-text">请从浏览器中复制完整的Cookie字符串</p>
                <p class="tips-text tips-text--highlight">
                  <span class="tips-label">重要字段：</span>
                  <span class="tips-field">unb</span>、
                  <span class="tips-field">_m_h5_tk</span>、
                  <span class="tips-field">cookie2</span>、
                  <span class="tips-field">t</span>
                </p>
                <p class="tips-text tips-text--bold">格式示意（请复制实际完整值，勿使用示例）：</p>
                <p class="tips-example">
                  <span class="tips-field">unb</span>=当前账号ID;
                  <span class="tips-field">_m_h5_tk</span>=当前值;
                  <span class="tips-field">cookie2</span>=当前值;
                  sgcookie=当前值;
                  <span class="tips-field">x5sec</span>=验证后的值; ...
                </p>
              </div>
            </div>
          </div>
        </div>

        <!-- Footer -->
        <div class="modal-footer">
          <button class="btn btn--secondary" @click="showHelpGuide ? toggleHelpGuide() : handleClose()">
            {{ showHelpGuide ? '返回' : '取消' }}
          </button>
          <button v-if="!showHelpGuide" class="btn btn--primary" :disabled="loading" @click="handleSubmit">
            {{ loading ? '更新中...' : '确定更新' }}
          </button>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.20);
  backdrop-filter: blur(28px) saturate(1.8);
  -webkit-backdrop-filter: blur(28px) saturate(1.8);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  animation: fadeIn 0.2s ease-out;
}

@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.modal-container {
  background: rgba(255,255,255,0.72);
  border-radius: 20px;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  max-height: 85vh;
  animation: slideUp 0.3s cubic-bezier(0.25, 0.1, 0.25, 1);
}

@keyframes slideUp {
  from {
    transform: translateY(20px);
    opacity: 0;
  }
  to {
    transform: translateY(0);
    opacity: 1;
  }
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 0.5px solid rgba(60,60,67,.12);
  background: rgba(255,255,255,0.72);
  backdrop-filter: blur(28px) saturate(1.8);
  -webkit-backdrop-filter: blur(28px) saturate(1.8);
  flex-shrink: 0;
  gap: 12px;
}

.modal-title {
  font-size: 18px;
  font-weight: 600;
  color: #1c1c1e;
  margin: 0;
  letter-spacing: -0.01em;
  flex: 1;
}

.help-link {
  display: flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  color: rgba(28,28,30,.55);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  padding: 6px 10px;
  border-radius: 8px;
  transition: all 0.2s;
  -webkit-tap-highlight-color: transparent;
  flex-shrink: 0;
}

.help-link:hover {
  color: #0A84FF;
  background: rgba(0, 122, 255, 0.08);
}

.help-link svg {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}

.modal-close {
  background: none;
  border: none;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(28,28,30,.55);
  cursor: pointer;
  border-radius: 8px;
  transition: all 0.2s;
  -webkit-tap-highlight-color: transparent;
  flex-shrink: 0;
}

.modal-close:hover {
  background: rgba(60,60,67,.12);
  color: #1c1c1e;
}

.modal-close svg {
  width: 20px;
  height: 20px;
}

.modal-content {
  flex: 1;
  overflow-y: auto;
  scrollbar-width: none;
  padding: 24px;
}

.modal-content::-webkit-scrollbar {
  display: none;
}

.form-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-label {
  font-size: 15px;
  font-weight: 600;
  color: #1c1c1e;
  letter-spacing: -0.01em;
}

.form-textarea {
  padding: 12px;
  font-size: 12px;
  font-family: 'SF Mono', 'Menlo', 'Monaco', monospace;
  line-height: 1.5;
  border: 1px solid rgba(0, 0, 0, 0.1);
  border-radius: 12px;
  background: rgba(255,255,255,0.15);
  color: #1c1c1e;
  resize: vertical;
  transition: all 0.2s;
}

.form-textarea:focus {
  outline: none;
  border-color: #0A84FF;
  background: rgba(255,255,255,0.55);
  box-shadow: 0 0 0 3px rgba(0, 122, 255, 0.1);
}

.form-textarea::placeholder {
  color: rgba(28,28,30,.55);
}

.tips-container {
  background: rgba(0, 122, 255, 0.08);
  border: 1px solid rgba(0, 122, 255, 0.2);
  border-radius: 12px;
  padding: 12px;
}

.tips-title {
  font-size: 13px;
  font-weight: 600;
  color: #0A84FF;
  margin-bottom: 8px;
}

.tips-content {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.tips-text {
  font-size: 12px;
  color: rgba(28,28,30,.55);
  margin: 0;
  line-height: 1.5;
}

.tips-text--highlight {
  color: #1c1c1e;
}

.tips-text--bold {
  font-weight: 500;
  color: #1c1c1e;
}

.tips-label {
  color: #FF9F0A;
  font-weight: 600;
}

.tips-field {
  color: #FF453A;
  font-weight: 500;
}

.tips-example {
  font-size: 11px;
  font-family: 'SF Mono', 'Menlo', 'Monaco', monospace;
  color: rgba(28,28,30,.55);
  line-height: 1.6;
  word-break: break-all;
  margin: 0;
}

.modal-footer {
  display: flex;
  gap: 12px;
  padding: 16px 24px;
  border-top: 0.5px solid rgba(60,60,67,.12);
  background: rgba(255,255,255,0.72);
  flex-shrink: 0;
}

.btn {
  flex: 1;
  padding: 12px 16px;
  font-size: 15px;
  font-weight: 600;
  border: none;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.25, 0.1, 0.25, 1);
  -webkit-tap-highlight-color: transparent;
  letter-spacing: -0.01em;
}

.btn--primary {
  background: #0A84FF;
  color: white;
  box-shadow: 0 4px 12px rgba(0, 122, 255, 0.3);
}

.btn--primary:hover:not(:disabled) {
  background: #0066d6;
  box-shadow: 0 6px 16px rgba(0, 122, 255, 0.4);
}

.btn--primary:active:not(:disabled) {
  transform: scale(0.96);
}

.btn--primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn--secondary {
  background: rgba(60,60,67,.12);
  color: #1c1c1e;
}

.btn--secondary:hover {
  background: rgba(0, 0, 0, 0.1);
}

.btn--secondary:active {
  transform: scale(0.96);
}

/* 手机端适配 */
@media screen and (max-width: 767px) {
  .modal-container {
    width: 90%;
    max-height: 90vh;
    border-radius: 20px;
  }

  .modal-header {
    padding: 16px;
  }

  .modal-title {
    font-size: 16px;
  }

  .modal-content {
    padding: 16px;
  }

  .modal-footer {
    padding: 12px 16px;
    gap: 10px;
  }

  .btn {
    padding: 10px 14px;
    font-size: 14px;
  }

  .form-textarea {
    font-size: 11px;
  }

  .tips-text {
    font-size: 11px;
  }

  .tips-example {
    font-size: 10px;
  }
}

/* 平板端适配 */
@media screen and (min-width: 768px) and (max-width: 1023px) {
  .modal-container {
    width: 70%;
    max-height: 85vh;
  }

  .modal-content {
    padding: 20px;
  }

  .form-textarea {
    font-size: 11px;
  }
}

/* 电脑端适配 */
@media screen and (min-width: 1024px) {
  .modal-container {
    width: 60%;
    max-height: 85vh;
  }

  .modal-content {
    padding: 32px;
  }
}

.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.2s ease;
}

.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}
</style>
