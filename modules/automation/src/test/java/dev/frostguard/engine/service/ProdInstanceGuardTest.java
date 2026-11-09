package dev.frostguard.engine.service;

import dev.frostguard.engine.emulator.BoundedProcessRunner.ProcessResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProdInstanceGuardTest {

    private static final String PROD_LINE = "\"C:\\Frostguard-tools\\jdk\\bin\\javaw.exe\" "
            + "-Dfrostguard.workspace=\"C:\\Bearguard\\.\" -cp x dev.frostguard.app.bootstrap.Main";
    private static final String NEWUI_LINE = "\"C:\\Frostguard-tools\\jdk\\bin\\javaw.exe\" "
            + "-Dfrostguard.workspace=\"C:\\Bearguard-newui\\.\" -cp x dev.frostguard.app.bootstrap.Main";

    @Test
    void matchesProdCommandLineIgnoringCase() {
        assertTrue(ProdInstanceGuard.commandLineIsProd(PROD_LINE));
        assertTrue(ProdInstanceGuard.commandLineIsProd(PROD_LINE.replace("C:\\Bearguard", "c:\\BEARGUARD")));
    }

    @Test
    void doesNotMatchSiblingInstallsThatShareThePrefix() {
        assertFalse(ProdInstanceGuard.commandLineIsProd(NEWUI_LINE));
        assertFalse(ProdInstanceGuard.commandLineIsProd(NEWUI_LINE.replace("newui", "dev")));
        assertFalse(ProdInstanceGuard.commandLineIsProd("javaw -jar C:\\Bearguard\\unrelated.jar"));
        assertFalse(ProdInstanceGuard.commandLineIsProd(null));
    }

    @Test
    void separateInstanceIsAnyRootButProd() {
        assertFalse(ProdInstanceGuard.isSeparateInstance(Paths.get("C:\\Bearguard\\.")));
        assertFalse(ProdInstanceGuard.isSeparateInstance(Paths.get("c:\\bearguard")));
        assertTrue(ProdInstanceGuard.isSeparateInstance(Paths.get("C:\\Bearguard-newui\\.")));
    }

    @Test
    void failedQueryIsUnknownNeverNotRunning() {
        assertEquals(ProdInstanceGuard.ProdState.UNKNOWN,
                ProdInstanceGuard.stateFromOutput(new ProcessResult(1, "", false)));
        assertEquals(ProdInstanceGuard.ProdState.UNKNOWN,
                ProdInstanceGuard.stateFromOutput(new ProcessResult(0, "", true)));
    }

    @Test
    void readsProcessList() {
        assertEquals(ProdInstanceGuard.ProdState.RUNNING,
                ProdInstanceGuard.stateFromOutput(new ProcessResult(0, NEWUI_LINE + "\r\n" + PROD_LINE, false)));
        assertEquals(ProdInstanceGuard.ProdState.NOT_RUNNING,
                ProdInstanceGuard.stateFromOutput(new ProcessResult(0, NEWUI_LINE, false)));
    }
}
