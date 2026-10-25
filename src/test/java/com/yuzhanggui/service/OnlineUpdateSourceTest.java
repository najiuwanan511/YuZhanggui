package com.yuzhanggui.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineUpdateSourceTest {

    @Test
    void onlineUpdaterKeepsTwoContainerArchitectureAndUsesHostAgent() throws Exception {
        String compose = Files.readString(Path.of("compose.yaml"));
        String agent = Files.readString(Path.of("deploy/self-update/yuzhanggui-update-agent.sh"));
        String service = Files.readString(Path.of("deploy/self-update/yuzhanggui-update.service"));

        assertTrue(compose.contains("UPDATE_REQUEST_DIR: /app/update"));
        assertTrue(compose.contains(":/app/update"));
        assertFalse(compose.matches("(?s).*\\n\\s{2}(updater|update-agent):\\s*\\n.*"));
        assertTrue(service.contains("Type=oneshot"));
        assertTrue(agent.contains("jars[0].get(\"digest\""));
        assertTrue(agent.contains("https://gh-proxy.com/"));
        assertTrue(agent.contains("https://ghfast.top/"));
        assertTrue(agent.contains("https://ghproxy.net/"));
        assertTrue(agent.contains("--continue-at -"));
        assertTrue(agent.contains("mysqldump --single-transaction"));
        assertTrue(agent.contains("restore_previous_jar"));
        assertTrue(agent.contains("wait_for_app"));
        assertTrue(agent.contains("maintenance.flag"));
        assertTrue(agent.contains("wait_for_business_idle"));
        assertTrue(agent.contains("FROM xianyu_zero_bridge_order WHERE status IN"));
        for (String source : new String[] {
                "src/main/java/com/yuzhanggui/service/DeliveryTaskScheduler.java",
                "src/main/java/com/yuzhanggui/service/ConfirmShipmentTaskScheduler.java",
                "src/main/java/com/yuzhanggui/service/RateTaskScheduler.java",
                "src/main/java/com/yuzhanggui/service/RedFlowerTaskScheduler.java",
                "src/main/java/com/yuzhanggui/service/impl/ZeroBridgeScheduler.java",
                "src/main/java/com/yuzhanggui/service/reply/AutoReplyDelayServiceImpl.java"
        }) {
            assertTrue(Files.readString(Path.of(source)).contains("onlineUpdateMaintenanceService.isActive()"));
        }
    }

    @Test
    void releaseWorkflowPublishesJarAndChecksum() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/publish-container.yml"));
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertTrue(workflow.contains("release-artifacts:"));
        assertTrue(workflow.contains("SHA256SUMS.txt"));
        assertTrue(workflow.contains("gh release upload"));
        assertTrue(dockerfile.contains("UPDATE_JAR_PATH:-/app/update/app.jar"));
    }

    @Test
    void firstInstallEnablesHostUpdateAgentWithoutRebuildingApplicationTwice() throws Exception {
        String installer = Files.readString(Path.of("install.sh"));
        String agentInstaller = Files.readString(Path.of("deploy/self-update/install-online-update.sh"));

        assertTrue(installer.contains("install-online-update.sh \"$ROOT_DIR\" --skip-app-recreate"));
        assertTrue(installer.contains("[ ! -d /run/systemd/system ]"));
        assertTrue(agentInstaller.contains("--skip-app-recreate"));
        assertTrue(agentInstaller.contains("SKIP_APP_RECREATE"));
        assertTrue(agentInstaller.contains("UPDATE_DOWNLOAD_PROXY_PREFIXES"));
    }
}
