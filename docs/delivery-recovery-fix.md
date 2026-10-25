# 订单凭证恢复修复

基于 main 提交 `ba8acce9f4446713f42e9ec8a6faff26bb10c0a6` 修复，版本为 2.3.23。版本号高于用户已运行的 2.3.22，正式 Release 的安装附件就绪后可以在线升级。发布状态以 GitHub Release 和对应工作流为准。

## 修复行为

订单详情令牌过期后，签名 Token 恢复请求携带完整参数。未下发新 Token 时，保留登录态，仅在临时请求中去掉 `_m_h5_tk` 与 `_m_h5_tk_enc` 重新协商。最多三次请求，并尝试一次 hasLogin；没有取得新 Token 就保留数据库凭证，不宣称恢复成功。平台要求人工验证时暂停请求。

订单首次核验失败后等待五分钟，连续三次后改为每三十分钟检查恢复，不再永久挂起。等待核验不消耗发货失败次数。扫码或手动保存 Cookie 后，未发送的核验等待订单会提前入队，账号恢复正常后才领取执行。

启动时 V37 迁移恢复旧代码挂起或等待核验的未发货订单。只恢复未发送、未确认发货、未记录发货内容且交易状态为空或待发货的订单；排除自提、退款、发送结果不确定等情况。恢复后仍需重新核验订单买家。

## 验证和安装范围

已执行 Maven 全量测试与 package，最终结果和新增修复见 [完整检查报告](audit-2026-10-12.md)。Token 请求使用本地模拟平台验证，SQL 使用 H2 MySQL 兼容模式验证；尚未连接飞牛生产环境或真实闲鱼账号验证。

本地修复产物为 `target/yuzhanggui.jar`，正式发布附件为 `yuzhanggui-2.3.23.jar`。`update.sh` 从 main 构建，网页在线更新使用正式 Release 的安装附件。

宿主机更新代理的新增版本校验与 Zero 任务等待逻辑位于 `deploy/self-update/yuzhanggui-update-agent.sh`，仅替换应用 JAR 不会替换宿主机代理。取得正式发布源码后还需按现有在线更新安装流程更新代理。

当前 Docker 入口优先运行挂载目录中的 `/app/update/app.jar`。如采用 JAR 更新，需把修复产物传到飞牛，确认没有在线更新或发货任务正在执行，备份数据库及原运行 JAR，再替换 `.env` 中 `UPDATE_HOST_DIR` 对应目录内的 `app.jar`（默认 `./runtime/update/app.jar`），保持文件可读，重建应用容器：

```sh
cd ~/yuzhanggui
docker compose up -d --no-build --no-deps --force-recreate app
docker compose ps
docker compose logs --tail=100 app
```

安装后核对应用健康检查、V37 迁移结果及目标订单的重试/发货记录。若账号需要平台验证，应先完成验证并更新 Cookie；代码无法代替失效的登录授权。
