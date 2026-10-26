import { request } from '@/utils/request'

export interface VerificationView {
  accountId: number
  sessionId: string
  state: 'STARTING' | 'WAITING' | 'APPLYING' | 'RECONNECTING' | 'CONNECTED' | 'VERIFICATION_REQUIRED' | 'FAILED' | 'CLOSING' | 'CLOSED' | 'EXPIRED'
  message: string
  image: string | null
  width: number
  height: number
  revision: number
  expiresAt: number
}
export interface VerificationPoint { x: number; y: number; elapsedMs: number }
export const startVerification = (accountId: number) => request<VerificationView>({
  url: '/websocket/verification/start', method: 'POST', data: { accountId }
})
export const verificationState = (accountId: number, sessionId: string) => request<VerificationView>({
  url: '/websocket/verification/state', method: 'POST', data: { accountId, sessionId }
})
export const verificationInput = (accountId: number, sessionId: string, points: VerificationPoint[]) => request<VerificationView>({
  url: '/websocket/verification/input', method: 'POST', data: { accountId, sessionId, points }
})
export const closeVerification = (accountId: number, sessionId: string) => request<string>({
  url: '/websocket/verification/close', method: 'POST', data: { accountId, sessionId }
})
