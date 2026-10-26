<script setup lang="ts">
import { ref, watch } from 'vue'
import { updateAccount } from '@/api/account'
import { showSuccess, showError } from '@/utils'
import type { Account } from '@/types'

const props = defineProps<{
  modelValue: boolean
  account?: Account | null
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'success'): void
}>()
const accountNote = ref('')
const saving = ref(false)

watch([() => props.modelValue, () => props.account], () => {
  if (props.modelValue) accountNote.value = props.account?.accountNote || ''
}, { immediate: true })

const handleClose = () => {
  if (!saving.value) emit('update:modelValue', false)
}

const handleSubmit = async () => {
  if (saving.value || !props.account) return
  saving.value = true
  try {
    const response = await updateAccount({ id: props.account.id, accountNote: accountNote.value.trim() })
    if (response.code !== 0 && response.code !== 200) throw new Error(response.msg || '保存失败')
    showSuccess('账号资料已保存')
    emit('update:modelValue', false)
    emit('success')
  } catch (error: unknown) {
    if (!(typeof error === 'object' && error !== null && 'messageShown' in error && error.messageShown)) {
      showError(`保存失败：${error instanceof Error ? error.message : '请稍后重试'}`)
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="modal-overlay" @click="handleClose">
      <form class="modal" role="dialog" aria-modal="true" aria-labelledby="account-profile-title" @click.stop @submit.prevent="handleSubmit">
        <div class="modal-header">
          <h2 id="account-profile-title" class="modal-title">编辑账号资料</h2>
          <button class="modal-close" type="button" aria-label="关闭" :disabled="saving" @click="handleClose">×</button>
        </div>
        <div class="modal-body">
          <section class="profile-section">
            <label class="field-label" for="account-note">账号备注</label>
            <input id="account-note" v-model="accountNote" type="text" class="modal-input" maxlength="100" :disabled="saving" placeholder="例如：主账号、店铺一" />
            <p class="profile-section__hint">用于在系统内区分账号，不会发送给买家。</p>
          </section>
        </div>
        <div class="modal-footer">
          <button type="button" class="modal-btn modal-btn-cancel" :disabled="saving" @click="handleClose">取消</button>
          <button type="submit" class="modal-btn modal-btn-primary" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </form>
    </div>
  </Teleport>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.25);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  display: flex;
  align-items: stretch;
  justify-content: flex-end;
  z-index: 2000;
  animation: fadeIn 0.2s ease;
}

.modal {
  width: min(600px, 100vw);
  height: 100%;
  max-height: none;
  border-radius: 0;
  background: #fff;
  box-shadow: -18px 0 50px rgba(12, 31, 56, .2);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  animation: scaleIn 0.2s ease;
}

.modal-header {
  flex: 0 0 auto;
  padding: 18px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 0.5px solid rgba(0, 0, 0, 0.1);
}

.modal-title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: #000;
  line-height: 1.2;
}

.modal-close {
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: 50%;
  color: rgba(28,28,30,.55);
  background: rgba(60,60,67,.08);
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
}

.modal-body {
  flex: 1 1 auto;
  min-height: 0;
  padding: 20px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.profile-section {
  min-height: 152px;
}

.profile-section__hint {
  margin: 9px 0 0;
  color: rgba(28,28,30,.52);
  font-size: 12px;
  line-height: 1.5;
}

.field-label {
  display: block;
  margin-bottom: 8px;
  color: rgba(28,28,30,.55);
  font-size: 13px;
  font-weight: 500;
}

.modal-input {
  width: 100%;
  height: 42px;
  border-radius: 12px;
  border: none;
  padding: 0 12px;
  font-size: 15px;
  background: rgba(0, 0, 0, 0.05);
  color: #000;
  outline: none;
  transition: all 0.2s ease;
  box-sizing: border-box;
}

.modal-input:hover {
  background: rgba(0, 0, 0, 0.08);
}

.modal-input:focus {
  background: rgba(0, 0, 0, 0.08);
}

.modal-input::placeholder {
  color: #999;
}

.modal-footer {
  display: flex;
  flex: 0 0 auto;
  gap: 10px;
  padding: 16px 20px;
  border-top: 1px solid #e7ebf0;
  background: #fff;
  box-shadow: 0 -8px 22px rgba(24, 43, 68, .05);
}

.modal-btn {
  min-width: 94px;
  height: 38px;
  padding: 0 16px;
  border: 1px solid #d5deea;
  border-radius: 9px;
  background: #fff;
  color: #49617f;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  transition: opacity 0.15s;
  -webkit-tap-highlight-color: transparent;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal-btn:active {
  opacity: 0.5;
}

.modal-btn-cancel {
  color: #666;
}

.modal-btn-primary {
  margin-left: auto;
  border-color: #287df0;
  background: #287df0;
  color: #fff;
}

.modal-btn:disabled { cursor: not-allowed; opacity: .5; }

@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

@keyframes scaleIn {
  from {
    transform: translateX(28px);
    opacity: 0;
  }
  to {
    transform: translateX(0);
    opacity: 1;
  }
}
</style>
