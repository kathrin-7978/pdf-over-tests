package at.asit.pdfover.gui.tests;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swtbot.swt.finder.SWTBot;
import org.eclipse.swtbot.swt.finder.exceptions.WidgetNotFoundException;
import org.eclipse.swtbot.swt.finder.waits.ICondition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.asit.pdfover.commons.Constants;
import at.asit.pdfover.commons.Messages;
import at.asit.pdfover.commons.Profile;
import at.asit.pdfover.gui.Main;
import at.asit.pdfover.gui.workflow.StateMachine;
import at.asit.pdfover.gui.workflow.config.ConfigurationManager;
import lombok.NonNull;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.SAME_THREAD)
public abstract class AbstractSignatureUITest {

    private Thread uiThread;
    private Display display;
    private Shell shell;
    private StateMachine sm;
    private SWTBot bot;

    private static final File inputFile = new File("src/test/resources/TestFile.pdf");
    private static String outputDir = inputFile.getAbsoluteFile().getParent();
    private Profile currentProfile;
    private final String postFix = "_superSigned";
    private static final List<Profile> profiles = new ArrayList<>();

    private static final Logger logger = LoggerFactory
            .getLogger(AbstractSignatureUITest.class);

    protected String str(String k) { return Messages.getString(k); }

    @BeforeAll
    public static void prepareTestEnvironment() throws IOException {
        deleteTempDir();
        createTempDir();
        setSignatureProfiles();
        setupPdfBox();
    }

    private static void setupPdfBox() throws IOException{
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                doc.save(baos);
                pdfBytes = baos.toByteArray();
            }
        }

        // Load PDF to trigger initialization
        try (PDDocument ignored = PDDocument.load(pdfBytes)) {

        }
    }

    private static void deleteTempDir() throws IOException {
        String root = inputFile.getAbsoluteFile().getParent();
        File dir = new File(root);
        for (File f : Objects.requireNonNull(dir.listFiles())) {
            if (f.getName().startsWith("output_")) {
                FileUtils.deleteDirectory(f);
            }
        }
    }

    private static void createTempDir() throws IOException {
        Path tmpDir = Files.createTempDirectory(Paths.get(inputFile.getAbsoluteFile().getParent()), "output_");
        tmpDir.toFile().deleteOnExit();
        outputDir = FilenameUtils.separatorsToSystem(tmpDir.toString());
    }


    @BeforeEach
    public final void setupUITest() throws InterruptedException {
        final CountDownLatch uiInitialized = new CountDownLatch(1);
        final AtomicReference<Exception> initException = new AtomicReference<>();

        uiThread = new Thread(() -> {
            try {
                display = new Display();
                currentProfile = getCurrentProfile();
                setConfig(currentProfile);
                sm = Main.setup(new String[]{inputFile.getAbsolutePath()});
                shell = sm.getMainShell();

                if (shell == null || shell.isDisposed()) {
                    throw new IllegalStateException("Shell was not created properly");
                }

                // Signal that initialization is complete
                uiInitialized.countDown();

                // Hand over control to the StateMachine (it owns the SWT event loop)
                sm.start();

            } catch (Exception e) {
                logger.error("Error initializing UI", e);
                initException.set(e);
                uiInitialized.countDown();
            } finally {
                // Cleanup
                if (display != null && !display.isDisposed()) {
                    try {
                        display.dispose();
                    } catch (Exception ignore) {
                        // ignore
                    }
                }
            }

        }, "SWT-UI-Thread");

        uiThread.setDaemon(false);
        uiThread.start();

        if (!uiInitialized.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("UI initialization timed out");
        }

        if (initException.get() != null) {
            throw new RuntimeException("UI initialization failed", initException.get());
        }

        if (shell == null || shell.isDisposed()) {
            throw new IllegalStateException("Shell is not available after initialization");
        }

        bot = new SWTBot(shell);
    }

    @AfterEach
    public void reset() throws InterruptedException {
        logger.info("Starting test cleanup");

        // Give background signing threads time to complete
        logger.info("Waiting for background operations to complete...");
        Thread.sleep(3000);

        deleteOutputFile();
        closeShell();

        logger.info("Test cleanup completed");
    }

    public void closeShell() throws InterruptedException {
        // If Display already disposed or never created, nothing to do
        if (display == null || display.isDisposed()) {
            uiThread = null;
            return;
        }

        try {
            // Ask state machine to exit gracefully (on UI thread)
            if (sm != null) {
                try {
                    display.syncExec(() -> {
                        try {
                            sm.exit();
                        } catch (Exception e) {
                            logger.warn("Could not stop state machine", e);
                        }
                    });
                } catch (Exception e) {
                    logger.warn("Error during state machine stop", e);
                }
            }

            // Close shell (on UI thread)
            try {
                display.syncExec(() -> {
                    try {
                        if (shell != null && !shell.isDisposed()) {
                            logger.info("Closing shell");
                            shell.close();
                        }
                    } catch (Exception e) {
                        logger.error("Error closing shell", e);
                    }
                });
            } catch (org.eclipse.swt.SWTException swt) {
                if (!"Device is disposed".equalsIgnoreCase(swt.getMessage())) {
                    throw swt;
                }
                logger.warn("Display disposed before closeShell syncExec");
            }

            // Wait for UI thread to finish with timeout
            uiThread.join(10000);

            if (uiThread.isAlive()) {
                logger.warn("UI thread did not terminate gracefully, nudging event loop and waiting again");
                if (display != null && !display.isDisposed()) {
                    try {
                        display.wake();
                    } catch (Exception ignore) {
                        // ignore
                    }
                }
                uiThread.join(3000);

                if (uiThread.isAlive()) {
                    logger.error("UI thread still alive after 13 seconds, abandoning");
                }
            } else {
                logger.info("UI thread terminated gracefully");
            }
        } finally {
            bot = null;
            shell = null;
            sm = null;
            display = null;
            uiThread = null;

            // Small gap between tests to avoid race with background tasks
            Thread.sleep(500);
        }
    }


    protected void setCredentials() {
        try {
            ICondition widgetExists = new WidgetExistsCondition(str("mobileBKU.number"));
            bot.waitUntil(widgetExists, 80000);

            bot.textWithLabel(str("mobileBKU.number")).setText("TestUser-1902503362");
            bot.textWithLabel(str("mobileBKU.password")).setText("123456789");

            // Wait for OK to become enabled to avoid clicking a disabled button
            bot.waitUntil(new ICondition() {
                @Override
                public boolean test() {
                    try {
                        return bot.button(str("common.Ok")).isEnabled();
                    } catch (WidgetNotFoundException e) {
                        return false;
                    }
                }
                @Override public void init(SWTBot bot) {}
                @Override public String getFailureMessage() { return "OK button did not become enabled"; }
            }, 5000);

            bot.button(str("common.Ok")).click();
        }
        catch (WidgetNotFoundException wnf) {
            try {
                bot.button(str("common.Cancel")).click();
            } catch (Exception ignore) {
                // ignore
            }
            fail(wnf.getMessage());
        }

        File output = new File(getPathOutputFile());
        ICondition outputExists = new FileExistsCondition(output);
        bot.waitUntil(outputExists, 20000);

        if(!output.exists()) {
            try {
                bot.button(str("common.Cancel")).click();
            } catch (Exception ignore) {
                // ignore
            }
        }
        assertTrue(output.exists(), "Received signed PDF");
    }

    private void deleteOutputFile() {
        String path = getPathOutputFile();
        if (path != null) {
            File outputFile = new File(path);
            if (outputFile.exists()) {
                boolean deleted = outputFile.delete();
                if (deleted) {
                    logger.info("Deleted output file");
                } else {
                    logger.warn("Failed to delete output file");
                }
            }
        }
    }

    protected void testSignature(boolean negative, boolean captureRefImage) throws IOException {
        String outputFile = getPathOutputFile();
        assertNotNull(currentProfile);
        assertNotNull(outputFile);

        try (SignaturePositionValidator provider = new SignaturePositionValidator(negative, captureRefImage, currentProfile, outputFile)) {
            provider.verifySignaturePosition();
        } catch (Exception e) {
            fail("Error verifiying signature position", e);
        }
    }

    private static void setProperty(@NonNull Properties props, @NonNull String key, @NonNull String value) { props.setProperty(key, value); }

    private void setConfig(Profile currentProfile) {
        ConfigurationManager cm = new ConfigurationManager();
        Point size = cm.getMainWindowSize();

        Map<String, String> testParams = Map.ofEntries(
                Map.entry(Constants.CFG_BKU, cm.getDefaultBKUPersistent().name()),
                Map.entry(Constants.CFG_KEYSTORE_PASSSTORETYPE, "memory"),
                Map.entry(Constants.CFG_LOCALE, cm.getInterfaceLocale().toString()),
                Map.entry(Constants.CFG_LOGO_ONLY_SIZE, Double.toString(cm.getLogoOnlyTargetSize())),
                Map.entry(Constants.CFG_MAINWINDOW_SIZE, size.x + "," + size.y),
                Map.entry(Constants.CFG_OUTPUT_FOLDER, outputDir),
                Map.entry(Constants.CFG_POSTFIX, postFix),
                Map.entry(Constants.CFG_SIGNATURE_NOTE, currentProfile.getDefaultSignatureBlockNote(Locale.GERMANY)),
                Map.entry(Constants.CFG_SIGNATURE_POSITION, "auto"),
                Map.entry(Constants.SIGNATURE_PROFILE, currentProfile.toString()),
                Map.entry(Constants.CFG_SIGNATURE_LOCALE, cm.getSignatureLocale().toString())
        );

        File pdfOverConfig = new File(Constants.CONFIG_DIRECTORY + File.separator + Constants.DEFAULT_CONFIG_FILENAME);
        Properties props = new Properties();
        testParams.forEach((k, v) -> setProperty(props, k, v));

        try {
            FileOutputStream outputStream = new FileOutputStream(pdfOverConfig, false);
            props.store(outputStream, "TEST Configuration file was generated!");
        } catch (IOException e) {
            logger.warn("Failed to create configuration file.");
        }
    }

    public static void setSignatureProfiles() {
        Collections.addAll(profiles, Profile.values());
        assert(profiles.containsAll(EnumSet.allOf(Profile.class)));
    }

    public Profile getCurrentProfile() {
        currentProfile = profiles.get(0);
        profiles.remove(0);
        if (profiles.isEmpty()) {
            setSignatureProfiles();
        }
        return currentProfile;
    }

    /**
     * Returns path of the signed document.
     */
    private String getPathOutputFile() {
        String fileNameSigned = inputFile
                .getName()
                .substring(0, inputFile.getName().lastIndexOf('.'))
                .concat(postFix)
                .concat(".pdf");
        String pathOutputFile = FilenameUtils.separatorsToSystem(outputDir
                .concat("/")
                .concat(fileNameSigned));
        assertNotNull(pathOutputFile);
        return pathOutputFile;
    }

}