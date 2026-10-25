import { ref, reactive } from 'vue'
import { getAccountList, deleteAccount as deleteAccountApi, setAccountEnabled, resumeAccountAutomation, refreshAccountAvatar as refreshAccountAvatarApi, updateAccount } from '@/api/account'
import { showSuccess, showError, showConfirm } from '@/utils'
import type { Account } from '@/types'

export function useAccountManager() {
  const loading = ref(false)
  const accounts = ref<Account[]>([])
  
  const dialogs = reactive({
    add: false,
    manualAdd: false,
    qrLogin: false,
    deleteConfirm: false
  })
  
  const currentAccount = ref<Account | null>(null)
  const deleteAccountId = ref<number | null>(null)

  // 加载账号列表
  const loadAccounts = async () => {
    loading.value = true;
    try {
      const response = await getAccountList();
      if (response.code === 0 || response.code === 200) {
        // 后端返回格式: { code: 200, data: { accounts: [...] } }
        accounts.value = response.data?.accounts || [];
      } else {
        throw new Error(response.msg || '获取账号列表失败');
      }
    } catch (error: any) {
      // 只有在错误消息未显示过时才弹出提示（避免重复显示）
      if (!error.messageShown) {
        showError('加载账号列表失败: ' + error.message);
      }
      accounts.value = [];
    } finally {
      loading.value = false;
    }
  };

  // 显示添加对话框
  const showAddDialog = () => {
    currentAccount.value = null;
    dialogs.add = true;
  };

  // 显示手动添加对话框
  const showManualAddDialog = () => {
    dialogs.manualAdd = true;
  };

  // 显示扫码登录对话框
  const showQRLoginDialog = () => {
    dialogs.qrLogin = true;
  };

  // 编辑账号
  const editAccount = (account: Account) => {
    currentAccount.value = account;
    dialogs.add = true;
  };

  // 删除账号
  const deleteAccount = (id: number) => {
    deleteAccountId.value = id;
    dialogs.deleteConfirm = true;
  };

  const toggleAccountEnabled = async (account: Account) => {
    const enabled = account.status === 0
    try {
      await showConfirm(
        enabled
          ? `确定启用“${account.accountNote || account.unb}”吗？系统会尝试恢复实时连接。`
          : `确定禁用“${account.accountNote || account.unb}”吗？实时连接、自动回复、自动发货和定时自动化都会暂停。`,
        enabled ? '启用账号' : '禁用账号'
      )
      const response = await setAccountEnabled({ accountId: account.id, enabled })
      if (response.code !== 0 && response.code !== 200) {
        throw new Error(response.msg || '切换账号状态失败')
      }
      showSuccess(response.data || (enabled ? '账号已启用' : '账号已禁用'))
      await loadAccounts()
    } catch (error: any) {
      if (error !== 'cancel' && !error?.messageShown) {
        showError('切换账号状态失败: ' + (error.message || '未知错误'))
      }
    }
  }

  const resumeAutomation = async (account: Account) => {
    try {
      await showConfirm(
        `确定恢复“${account.accountNote || account.unb}”的自动化吗？恢复后，之前因保护暂停的待发货任务会重新进入队列。`,
        '恢复自动化'
      )
      const response = await resumeAccountAutomation({ accountId: account.id })
      if (response.code !== 0 && response.code !== 200) {
        throw new Error(response.msg || '恢复自动化失败')
      }
      showSuccess(response.data || '自动化已恢复')
      await loadAccounts()
    } catch (error: any) {
      if (error !== 'cancel' && !error?.messageShown) {
        showError('恢复自动化失败: ' + (error.message || '未知错误'))
      }
    }
  }

  const refreshingAvatarIds = new Set<number>()

  // 卡片上的自动化开关切换：利用后端 /account/update 的部分更新语义，
  // 只提交单个开关字段，其余字段保持不动。乐观更新，失败回滚。
  const automationToggling = new Set<string>()

  type AutomationToggleKey = 'rate' | 'flower'

  // 从未知类型的异常里安全取出提示文案（新代码不再使用 any，避免恶化 lint 基线）
  const extractErrorMessage = (error: unknown, fallback: string): string => {
    if (error instanceof Error && error.message) return error.message
    if (typeof error === 'object' && error !== null && 'message' in error) {
      const message = (error as { message?: unknown }).message
      if (typeof message === 'string' && message) return message
    }
    return fallback
  }

  const isAlreadyShownError = (error: unknown): boolean =>
    typeof error === 'object' && error !== null &&
    (error as { messageShown?: unknown }).messageShown === true

  const toggleAutomation = async (account: Account, key: AutomationToggleKey) => {
    if (account.status === 0) {
      showError('账号已禁用，请先启用账号再调整自动化')
      return
    }
    const field = key === 'rate' ? 'autoRateEnabled' : 'autoAskFlower'
    const label = key === 'rate' ? '自动评价' : '自动求花'
    const lockKey = `${account.id}:${key}`
    if (automationToggling.has(lockKey)) return
    automationToggling.add(lockKey)
    const previous = account[field] ?? 0
    const next = previous === 1 ? 0 : 1
    account[field] = next
    try {
      const response = await updateAccount({ id: account.id, [field]: next })
      if (response.code !== 0 && response.code !== 200) {
        throw new Error(response.msg || '更新自动化开关失败')
      }
      showSuccess(`${label}已${next === 1 ? '开启' : '关闭'}`)
    } catch (error: unknown) {
      account[field] = previous
      if (!isAlreadyShownError(error)) {
        showError(`更新${label}失败: ` + extractErrorMessage(error, '未知错误'))
      }
    } finally {
      automationToggling.delete(lockKey)
    }
  }

  // 账号卡片行内修改备注：同样走 /account/update 的部分更新，空字符串表示清空备注。
  const savingNoteIds = new Set<number>()

  const saveAccountNote = async (account: Account, note: string) => {
    if (savingNoteIds.has(account.id)) return
    savingNoteIds.add(account.id)
    const previous = account.accountNote
    account.accountNote = note
    try {
      const response = await updateAccount({ id: account.id, accountNote: note })
      if (response.code !== 0 && response.code !== 200) {
        throw new Error(response.msg || '更新账号备注失败')
      }
      showSuccess(note ? '账号备注已更新' : '账号备注已清除')
    } catch (error: unknown) {
      account.accountNote = previous
      if (!isAlreadyShownError(error)) {
        showError('更新账号备注失败: ' + extractErrorMessage(error, '未知错误'))
      }
    } finally {
      savingNoteIds.delete(account.id)
    }
  }

  const refreshAccountAvatar = async (account: Account) => {
    if (refreshingAvatarIds.has(account.id)) return
    refreshingAvatarIds.add(account.id)
    try {
      const response = await refreshAccountAvatarApi({ accountId: account.id })
      if (response.code !== 0 && response.code !== 200) {
        throw new Error(response.msg || '暂时无法获取闲鱼头像')
      }
      showSuccess('闲鱼头像已更新')
      await loadAccounts()
    } catch (error: any) {
      if (!error?.messageShown) {
        showError(error?.message || '暂时无法获取闲鱼头像，已保留文字头像')
      }
    } finally {
      refreshingAvatarIds.delete(account.id)
    }
  }

  // 确认删除账号
  const confirmDelete = async () => {
    if (!deleteAccountId.value) return;
    
    try {
      const response = await deleteAccountApi({ id: deleteAccountId.value });
      if (response.code === 0 || response.code === 200) {
        showSuccess('账号删除成功');
        dialogs.deleteConfirm = false;
        await loadAccounts();
      } else {
        throw new Error(response.msg || '删除失败');
      }
    } catch (error: any) {
      // 只有在错误消息未显示过时才弹出提示（避免重复显示）
      if (!error.messageShown) {
        showError('删除失败: ' + error.message);
      }
    }
  };

  return {
    loading,
    accounts,
    dialogs,
    currentAccount,
    deleteAccountId,
    loadAccounts,
    showAddDialog,
    showManualAddDialog,
    showQRLoginDialog,
    editAccount,
    deleteAccount,
    toggleAccountEnabled,
    resumeAutomation,
    toggleAutomation,
    saveAccountNote,
    refreshAccountAvatar,
    confirmDelete
  }
}
