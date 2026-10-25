import { request } from '@/utils/request'

export function testZeroBridge() {
  return request<boolean>({
    url: '/integrations/zero/test',
    method: 'post',
    data: {}
  })
}
