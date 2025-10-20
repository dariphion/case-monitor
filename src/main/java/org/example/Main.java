package org.example;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;

import java.awt.*;
import java.awt.TrayIcon.MessageType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final Set<String> caseNumbers = new HashSet<>();
    private static WebDriver driver;
    private static TrayIcon trayIcon;

    // Configuration properties
    private static String targetUrl;
    private static int refreshIntervalMs;
    private static String tbodyId;
    private static String cellClass;

    public static void main(String[] args) {
        logger.info("Starting Case Monitor Application...");

        // Load configuration
        if (!loadConfiguration()) {
            logger.error("Failed to load configuration. Exiting...");
            System.exit(1);
        }

        // Setup shutdown hook for graceful termination (Ctrl+C)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down gracefully...");
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception e) {
                    logger.warn("Error while closing driver", e);
                }
            }
            logger.info("Application terminated.");
        }));

        try {
            // Initialize system tray for notifications
            initializeSystemTray();

            // Setup Chrome WebDriver automatically
            logger.info("Setting up ChromeDriver...");
            WebDriverManager.chromedriver().clearDriverCache().setup();
            logger.info("ChromeDriver setup completed");

            // Configure Chrome options
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--start-maximized");
            options.addArguments("--disable-blink-features=AutomationControlled");
            options.addArguments("--remote-allow-origins=*");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");

            // Initialize Chrome driver
            logger.info("Initializing Chrome browser...");
            driver = new ChromeDriver(options);
            logger.info("Chrome WebDriver initialized successfully");

            // Navigate to target URL
            driver.get(targetUrl);
            logger.info("Navigated to: {}", targetUrl);

            // Start monitoring loop
            monitorCases();

        } catch (org.openqa.selenium.SessionNotCreatedException e) {
            logger.error("Failed to create browser session. This usually means:");
            logger.error("1. Chrome browser is not installed");
            logger.error("2. Chrome version is incompatible with ChromeDriver");
            logger.error("3. Chrome is already running with incompatible flags");
            logger.error("Error details: {}", e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            logger.error("Fatal error occurred", e);
            if (driver != null) {
                driver.quit();
            }
            System.exit(1);
        }
    }

    private static boolean loadConfiguration() {
        Properties properties = new Properties();

        // Try to load from external file first (same directory as jar)
        File externalConfig = new File("application.properties");

        try {
            InputStream input;

            if (externalConfig.exists()) {
                logger.info("Loading configuration from external file: {}", externalConfig.getAbsolutePath());
                input = Files.newInputStream(externalConfig.toPath());
            } else {
                logger.info("External configuration not found, loading from classpath");
                input = Main.class.getClassLoader().getResourceAsStream("application.properties");

                if (input == null) {
                    logger.error("Unable to find application.properties in classpath");
                    return false;
                }
            }

            properties.load(input);
            input.close();

            // Load configuration values
            targetUrl = properties.getProperty("monitor.url");
            refreshIntervalMs = Integer.parseInt(
                    properties.getProperty("monitor.refresh.interval.ms", "5000")
            );
            tbodyId = properties.getProperty("monitor.tbody.id");
            cellClass = properties.getProperty("monitor.cell.class");

            // Validate required properties
            if (targetUrl == null || targetUrl.isEmpty()) {
                logger.error("monitor.url is not configured");
                return false;
            }
            if (tbodyId == null || tbodyId.isEmpty()) {
                logger.error("monitor.tbody.id is not configured");
                return false;
            }
            if (cellClass == null || cellClass.isEmpty()) {
                logger.error("monitor.cell.class is not configured");
                return false;
            }

            logger.info("Configuration loaded successfully:");
            logger.info("  Target URL: {}", targetUrl);
            logger.info("  Refresh Interval: {}ms", refreshIntervalMs);
            logger.info("  TBody ID: {}", tbodyId);
            logger.info("  Cell Class: {}", cellClass);

            return true;

        } catch (IOException e) {
            logger.error("Error loading configuration", e);
            return false;
        } catch (NumberFormatException e) {
            logger.error("Invalid refresh interval value", e);
            return false;
        }
    }

    private static void initializeSystemTray() {
        if (!SystemTray.isSupported()) {
            logger.warn("System tray is not supported on this platform. Notifications will be logged only.");
            return;
        }

        try {
            SystemTray tray = SystemTray.getSystemTray();
            Image image = Toolkit.getDefaultToolkit().createImage("icon.png");

            trayIcon = new TrayIcon(image, "Case Monitor");
            trayIcon.setImageAutoSize(true);
            trayIcon.setToolTip("Case Monitor Running");

            tray.add(trayIcon);
            logger.info("System tray initialized successfully");
        } catch (AWTException e) {
            logger.warn("Could not initialize system tray", e);
        }
    }

    private static void monitorCases() {
        logger.info("Starting case monitoring loop (refresh interval: {}ms)", refreshIntervalMs);

        while (true) {
            try {
                // Get current cases from the page
                Set<String> currentCases = extractCasesFromPage();

                // Find new cases (in currentCases but not in caseNumbers)
                Set<String> newCases = new HashSet<>(currentCases);
                newCases.removeAll(caseNumbers);

                // Find removed cases (in caseNumbers but not in currentCases)
                Set<String> removedCases = new HashSet<>(caseNumbers);
                removedCases.removeAll(currentCases);

                // Process new cases
                for (String caseNumber : newCases) {
                    caseNumbers.add(caseNumber);
                    logger.info("NEW CASE FOUND: {}", caseNumber);
                    showNotification("New Case Found: " + caseNumber);
                }

                // Process removed cases
                for (String caseNumber : removedCases) {
                    caseNumbers.remove(caseNumber);
                    logger.info("CASE REMOVED: {}", caseNumber);
                }

                // Log current state
                if (!newCases.isEmpty() || !removedCases.isEmpty()) {
                    logger.info("Total active cases: {}", caseNumbers.size());
                }

                // Wait before next refresh
                Thread.sleep(refreshIntervalMs);

                // Refresh the page
                driver.navigate().refresh();

            } catch (InterruptedException e) {
                logger.info("Monitoring interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error during monitoring cycle", e);
                // Continue monitoring despite errors
            }
        }
    }

    private static Set<String> extractCasesFromPage() {
        Set<String> cases = new HashSet<>();

        try {
            // Find the tbody element
            WebElement tbody = driver.findElement(By.id(tbodyId));

            // Find all <tr> elements within tbody
            List<WebElement> rows = tbody.findElements(By.tagName("tr"));

            // Extract case numbers from first column
            for (WebElement row : rows) {
                try {
                    // Build CSS selector from class property
                    String cssSelector = "td." + cellClass;
                    WebElement firstCell = row.findElement(By.cssSelector(cssSelector));
                    String caseNumber = firstCell.getText().trim();

                    if (!caseNumber.isEmpty()) {
                        cases.add(caseNumber);
                    }
                } catch (Exception e) {
                    // Skip rows that don't match the expected structure
                    logger.debug("Skipping row due to structure mismatch", e);
                }
            }

            logger.debug("Extracted {} cases from page", cases.size());

        } catch (Exception e) {
            logger.warn("Could not extract cases from page", e);
        }

        return cases;
    }

    private static void showNotification(String message) {
        if (trayIcon != null) {
            trayIcon.displayMessage(
                    "Case Monitor",
                    message,
                    MessageType.INFO
            );
        }
    }
}