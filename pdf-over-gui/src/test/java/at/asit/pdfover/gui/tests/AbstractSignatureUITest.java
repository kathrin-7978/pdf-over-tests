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

                // Start the state machine asynchronously to avoid blocking the event loop
                display.asyncExec(() -> {
                    try {
                        sm.start();
                    } catch (Exception e) {
                        logger.error("Error starting state machine", e);
                    }
                });

                // Run the event loop
                while (!shell.isDisposed()) {
                    try {
                        if (!display.readAndDispatch()) {
                            display.sleep();
                        }
                    } catch (Exception e) {
                        logger.error("Error in event loop", e);
                        break;
                    }
                }

            } catch (Exception e) {
                logger.error("Error initializing UI", e);
                initException.set(e);
                uiInitialized.countDown();
            } finally {
                // Cleanup
                if (display != null && !display.isDisposed()) {
                    display.dispose();
                }
            }
            
        }, "SWT-UI-Thread");

        uiThread.setDaemon(false); // Don't make it daemon so JVM waits for cleanup
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

        // IMPORTANT: Wait longer for background signing operations to complete
        // The FinishSignThread needs time to complete
        logger.info("Waiting for background operations to complete...");
        Thread.sleep(3000);  // Increased from 1000ms to 3000ms

        deleteOutputFile();
        closeShell();

        logger.info("Test cleanup completed");
    }

    public void closeShell() throws InterruptedException {
        if (display != null && !display.isDisposed()) {
            /*
            display.asyncExec(() -> {
                if (shell != null && !shell.isDisposed()) {
                    shell.close();
                }
            });
            
             */

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
            
            Thread.sleep(1000);

            display.asyncExec(() -> {
                try {
                    if (shell != null && !shell.isDisposed()) {
                        logger.info("Closing shell");
                        shell.close();
                    }
                } catch (Exception e) {
                    logger.error("Error closing shell", e);
                }
            });
            
            display.wake();
            
            uiThread.join(10000);
            
            if (uiThread.isAlive()) {
                logger.warn("UI thread did not terminate gracefully, forcing disposal");
                if (!display.isDisposed()) {
                    display.wake();
                }
                uiThread.join(3000);

                if (uiThread.isAlive()) {
                    logger.error("UI thread still alive after 13 seconds, abandoning");
                }
            } else {
                logger.info("UI thread terminated gracefully");
            }
        }
    }


    protected void setCredentials() {
        try {
            ICondition widgetExists = new WidgetExistsCondition(str("mobileBKU.number"));
            bot.waitUntil(widgetExists, 80000);
            bot.textWithLabel(str("mobileBKU.number")).setText("TestUser-1902503362");
            bot.textWithLabel(str("mobileBKU.password")).setText("123456789");
            bot.button(str("common.Ok")).click();
        }
        catch (WidgetNotFoundException wnf) {
            bot.button(str("common.Cancel")).click();
            fail(wnf.getMessage());
        }

        File output = new File(getPathOutputFile());
        ICondition outputExists = new FileExistsCondition(output);
        bot.waitUntil(outputExists, 20000);

        if(!output.exists()) {
            bot.button(str("common.Cancel")).click();
        }
        assertTrue(output.exists(), "Received signed PDF");
    }

    private void deleteOutputFile() {
        if (getPathOutputFile() != null) {
            File outputFile = new File(getPathOutputFile());
            outputFile.delete();
            assertFalse(outputFile.exists());
            logger.info("Deleted output file");
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