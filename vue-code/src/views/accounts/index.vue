<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAccountManager } from './useAccountManager'
import AccountTable from './components/AccountTable.vue'
import AddAccountDialog from './components/AddAccountDialog.vue'
import AutomationSettingsPopover from './components/AutomationSettingsPopover.vue'
import ManualAddDialog from './components/ManualAddDialog.vue'
import QRLoginDialog from './components/QRLoginDialog.vue'
import DeleteConfirmDialog from './components/DeleteConfirmDialog.vue'
import ConnectionDetail from '@/views/connection/components/ConnectionDetail.vue'
import type { Account } from '@/types'

import IconQrCode from '@/components/icons/IconQrCode.vue'
import IconPlus from '@/components/icons/IconPlus.vue'
import IconSync from '@/components/icons/IconSync.vue'

defineOptions({ name: 'AccountManagement' })

const route = useRoute()
const router = useRouter()
const selectedConnectionAccountId = ref<number | null>(null)
const automationSaving = ref(false)
const automationEditor = shallowRef<{
  account: Account
  section: 'rate' | 'flower' | 'polish'
  anchor: HTMLElement
} | null>(null)

const openAutomationSettings = (account: Account, section: 'rate' | 'flower' | 'polish', event: MouseEvent) => {
  if (automationSaving.value) return
  if (event.currentTarget instanceof HTMLElement) {
    automationEditor.value = { account, section, anchor: event.currentTarget }
  }
}

const {
  loading,
  accounts,
  dialogs,
  currentAccount,
  deleteAccountId,
  loadAccounts,
  showManualAddDialog,
  showQRLoginDialog,
  editAccount,
  deleteAccount,
  toggleAccountEnabled,
  resumeAutomation,
  toggleAutomation,
  saveAccountNote,
  refreshAccountAvatar
} = useAccountManager();

const selectedConnectionAccount = computed(() =>
  accounts.value.find(account => Number(account.id) === selectedConnectionAccountId.value) || null
)

const syncConnectionSelection = () => {
  const requestedId = Number(route.query.accountId)
  if (route.query.connection === '1' && requestedId && accounts.value.some(account => Number(account.id) === requestedId)) {
    selectedConnectionAccountId.value = requestedId
  }
}

const openPolish = (account: Account) => router.push({ path: '/item-polish', query: { accountId: String(account.id) } })

const openConnection = (account: Account) => {
  selectedConnectionAccountId.value = Number(account.id)
  router.replace({
    path: '/accounts',
    query: { ...route.query, connection: '1', accountId: String(account.id) }
  })
}

const closeConnection = () => {
  selectedConnectionAccountId.value = null
  const rest = { ...route.query }
  delete rest.accountId
  delete rest.connection
  router.replace({ path: '/accounts', query: rest })
}

const updateConnectionStatus = (accountId: number, connected: boolean) => {
  const account = accounts.value.find(item => Number(item.id) === accountId)
  if (account) account.websocketConnected = connected
}

watch([accounts, () => route.query.accountId, () => route.query.connection], syncConnectionSelection, { immediate: true })

void loadAccounts();
</script>

<template>
  <div class="accounts">
    <!-- Page Header -->
    <header class="accounts__header">
      <div class="accounts__title-row">
        <h1 class="accounts__title mobile-hidden">闲鱼账号</h1>
      </div>
      <div class="accounts__actions desktop-only">
        <button class="btn btn--primary" @click="showQRLoginDialog">
          <IconQrCode />
          <span>扫码添加</span>
        </button>
        <button class="btn btn--secondary" @click="showManualAddDialog">
          <IconPlus />
          <span>手动添加</span>
        </button>
        <button
          class="btn btn--ghost"
          :class="{ 'btn--loading': loading }"
          @click="loadAccounts"
          :disabled="loading"
        >
          <IconSync />
          <span>刷新</span>
        </button>
      </div>
    </header>

    <!-- Content Card -->
    <section class="accounts__content" :class="{ 'accounts__content--detail-open': selectedConnectionAccount }">
      <div class="accounts__workspace">
      <div class="accounts__table-wrap">
        <AccountTable
          :accounts="accounts"
          :loading="loading"
          @edit="editAccount"
          @automation-settings="openAutomationSettings"
          @toggle-automation="toggleAutomation"
          @save-note="saveAccountNote"
          @delete="deleteAccount"
          @toggle-enabled="toggleAccountEnabled"
          @resume-automation="resumeAutomation"
          @refresh-avatar="refreshAccountAvatar"
          @connection="openConnection"
          @polish-shortcut="openPolish"
        />
      </div>
      <aside v-if="selectedConnectionAccount" class="accounts__connection-panel">
        <header class="accounts__connection-header">
          <div>
            <strong>账号与连接详情</strong>
            <span>{{ selectedConnectionAccount.accountNote || selectedConnectionAccount.unb }}</span>
          </div>
          <button type="button" class="accounts__connection-close" aria-label="关闭账号详情" @click="closeConnection">×</button>
        </header>
        <div class="accounts__connection-body">
          <ConnectionDetail
            :account-id="selectedConnectionAccount.id"
            :account-name="selectedConnectionAccount.accountNote || selectedConnectionAccount.unb"
            :account-unb="selectedConnectionAccount.unb"
            :auto-connect-on-startup="selectedConnectionAccount.autoConnectOnStartup"
            @status-change="updateConnectionStatus"
            @credential-updated="loadAccounts"
          />
        </div>
      </aside>
      </div>
    </section>

    <!-- Mobile Bottom Actions -->
    <footer class="accounts__footer mobile-only">
      <button class="btn btn--primary btn--full" @click="showQRLoginDialog">
        <IconQrCode />
        <span>扫码添加</span>
      </button>
      <button class="btn btn--secondary btn--full" @click="showManualAddDialog">
        <IconPlus />
        <span>手动添加</span>
      </button>
    </footer>

    <!-- Dialogs -->
    <AutomationSettingsPopover
      v-if="automationEditor"
      :key="`${automationEditor.account.id}:${automationEditor.section}`"
      :account="automationEditor.account"
      :section="automationEditor.section"
      :anchor="automationEditor.anchor"
      @busy="automationSaving = $event"
      @close="automationEditor = null"
      @success="loadAccounts"
    />
    <AddAccountDialog
      v-model="dialogs.add"
      :account="currentAccount"
      @success="loadAccounts"
    />
    <ManualAddDialog v-model="dialogs.manualAdd" @success="loadAccounts" />
    <QRLoginDialog v-model="dialogs.qrLogin" @success="loadAccounts" @manual="showManualAddDialog" />
    <DeleteConfirmDialog
      v-model="dialogs.deleteConfirm"
      :account-id="deleteAccountId"
      @success="loadAccounts"
    />
  </div>
</template>

<style scoped src="./accounts.css"></style>
