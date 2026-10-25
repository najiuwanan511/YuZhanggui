package com.yuzhanggui.config;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class PlaywrightManager {

    private volatile Playwright playwright;
    private volatile Browser browser;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean initialized = false;

    @Value("${app.captcha.browser-headless:true}")
    private boolean browserHeadless;

    @Value("${app.captcha.remote-enabled:false}")
    private boolean remoteBrowserEnabled;

    @Value("${app.captcha.profile-dir:./data/browser-profiles}")
    private String browserProfileDir;

    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36";
    private static final String[] BROWSER_CHANNEL_CANDIDATES = {null, "chrome", "msedge"};

    private static final String BROWSER_CACHE_DIR;

    static {
        String configuredBrowserPath = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        if (configuredBrowserPath != null && !configuredBrowserPath.isBlank()) {
            // 容器优先使用预置浏览器目录，避免扫描阶段依赖不稳定的JAR路径。
            BROWSER_CACHE_DIR = configuredBrowserPath;
        } else {
            String jarDir = getJarDirectory();
            BROWSER_CACHE_DIR = jarDir + File.separator + "ms-playwright";
        }
        System.setProperty("PLAYWRIGHT_BROWSERS_PATH", BROWSER_CACHE_DIR);
        log.info("Playwright浏览器缓存目录: {}", BROWSER_CACHE_DIR);
    }

    private static String getJarDirectory() {
        try {
            String jarPath = PlaywrightManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            File jarFile = new File(jarPath);
            if (jarFile.isFile()) {
                return jarFile.getParent();
            }
            return jarFile.getAbsolutePath();
        } catch (Exception e) {
            String userDir = System.getProperty("user.dir");
            log.warn("无法获取JAR目录，使用user.dir: {}", userDir, e);
            return userDir;
        }
    }

    @PostConstruct
    public void init() {
        log.info("PlaywrightManager初始化，浏览器缓存目录: {}", BROWSER_CACHE_DIR);
    }

    public BrowserContext createContext() {
        return createContext(null);
    }

    public BrowserContext createContext(Long accountId) {
        lock.lock();
        try {
            ensureBrowserReady();
            return browser.newContext(contextOptions(accountId));
        } catch (Exception e) {
            log.error("创建BrowserContext失败，尝试重建浏览器实例", e);
            try {
                rebuild();
                return browser.newContext(contextOptions(accountId));
            } catch (Exception ex) {
                log.error("重建浏览器后仍然失败", ex);
                throw new RuntimeException("Playwright浏览器不可用", ex);
            }
        } finally {
            lock.unlock();
        }
    }

    private Browser.NewContextOptions contextOptions(Long accountId) throws Exception {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setUserAgent(BROWSER_USER_AGENT)
                .setLocale("zh-CN")
                .setTimezoneId("Asia/Shanghai")
                .setViewportSize(1365, 768);
        if (accountId != null) {
            Path profilePath = profilePath(accountId);
            if (Files.isRegularFile(profilePath)) {
                options.setStorageStatePath(profilePath);
                log.debug("【账号{}】加载持久化浏览器环境", accountId);
            }
        }
        return options;
    }

    public void saveContext(Long accountId, BrowserContext context) {
        if (accountId == null || context == null) return;
        try {
            Path profilePath = profilePath(accountId);
            context.storageState(new BrowserContext.StorageStateOptions().setPath(profilePath));
            log.debug("【账号{}】浏览器环境已持久化", accountId);
        } catch (Exception exception) {
            log.warn("【账号{}】保存浏览器环境失败: {}", accountId, exception.getMessage());
        }
    }

    Path profilePath(Long accountId) throws Exception {
        Path root = Paths.get(browserProfileDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        return root.resolve("account-" + accountId + ".json");
    }

    private void ensureBrowserReady() {
        if (browser != null && browser.isConnected()) {
            return;
        }
        log.info("Playwright浏览器未就绪或已断开，开始初始化...");
        doInit();
    }

    private void doInit() {
        try {
            if (this.playwright != null) {
                try {
                    this.playwright.close();
                } catch (Exception ignored) {
                }
            }
            this.playwright = Playwright.create();
            this.browser = launchAvailableBrowser();
            this.initialized = true;
            log.info("Playwright浏览器初始化成功");
        } catch (Exception e) {
            log.error("Playwright浏览器初始化失败", e);
            this.initialized = false;
            throw new RuntimeException("Playwright浏览器初始化失败", e);
        }
    }

    private Browser launchAvailableBrowser() {
        RuntimeException lastFailure = null;
        for (String channel : BROWSER_CHANNEL_CANDIDATES) {
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(browserHeadless && !remoteBrowserEnabled)
                    .setTimeout(TimeUnit.SECONDS.toMillis(15));
            if (channel != null) {
                launchOptions.setChannel(channel);
            }
            try {
                Browser launchedBrowser = playwright.chromium().launch(launchOptions);
                log.info("Playwright使用{}启动浏览器", browserChannelName(channel));
                return launchedBrowser;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                log.warn("Playwright无法使用{}启动浏览器，尝试下一个候选项: {}",
                        browserChannelName(channel), exception.getMessage());
            }
        }
        throw new RuntimeException("未找到可用的 Chromium、Chrome 或 Edge 浏览器", lastFailure);
    }

    static String browserChannelName(String channel) {
        return channel == null ? "Playwright Chromium" : "系统 " + channel;
    }

    private void rebuild() {
        log.info("重建Playwright浏览器实例...");
        closeQuietly();
        doInit();
    }

    private void closeQuietly() {
        try {
            if (browser != null) {
                browser.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception ignored) {
        }
        browser = null;
        playwright = null;
        initialized = false;
    }

    @PreDestroy
    public void destroy() {
        log.info("PlaywrightManager销毁，关闭浏览器资源...");
        lock.lock();
        try {
            closeQuietly();
        } finally {
            lock.unlock();
        }
        log.info("PlaywrightManager已销毁");
    }

    public boolean isInitialized() {
        return initialized && browser != null && browser.isConnected();
    }

    public void cleanTempFiles() {
        try {
            Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
            long now = System.currentTimeMillis();
            long thresholdMs = TimeUnit.HOURS.toMillis(1);
            long[] deletedCount = {0};
            long[] deletedSize = {0};

            Files.list(tmpDir)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("playwright") || name.contains("chromium")
                                || name.startsWith("core.") || name.endsWith(".pipe")
                                || name.endsWith(".sock");
                    })
                    .forEach(path -> {
                        try {
                            File file = path.toFile();
                            if (file.isDirectory()) {
                                long dirSize = deleteDirectory(file);
                                deletedCount[0]++;
                                deletedSize[0] += dirSize;
                            } else {
                                long fileAge = now - file.lastModified();
                                if (fileAge > thresholdMs) {
                                    long fileSize = file.length();
                                    if (file.delete()) {
                                        deletedCount[0]++;
                                        deletedSize[0] += fileSize;
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    });

            if (deletedCount[0] > 0) {
                log.info("清理Playwright临时文件: {}个文件, 释放空间: {}KB",
                        deletedCount[0], deletedSize[0] / 1024);
            }
        } catch (Exception e) {
            log.warn("清理Playwright临时文件失败", e);
        }
    }

    private long deleteDirectory(File directory) {
        long totalSize = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    totalSize += deleteDirectory(file);
                } else {
                    totalSize += file.length();
                    file.delete();
                }
            }
        }
        directory.delete();
        return totalSize;
    }
}
