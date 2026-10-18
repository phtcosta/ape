package com.android.commands.monkey.ape.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MopInteractionLedgerTest {

    @Test
    public void aKeyIsTriedOnlyAfterItsFirstExecutionInItsActivity() {
        MopInteractionLedger ledger = new MopInteractionLedger(1);
        assertFalse(ledger.tried("A", "//b|MODEL_CLICK"));
        ledger.record("A", "//b|MODEL_CLICK");
        assertTrue(ledger.tried("A", "//b|MODEL_CLICK"));
    }

    @Test
    public void theRecordIsPerActivityAndPerActionType() {
        MopInteractionLedger ledger = new MopInteractionLedger(1);
        ledger.record("A", "//b|MODEL_CLICK");
        assertFalse("another activity has its own count",
                ledger.tried("B", "//b|MODEL_CLICK"));
        assertFalse("another action type is another key",
                ledger.tried("A", "//b|MODEL_LONG_CLICK"));
    }

    @Test
    public void everyRecordAddsOneToItsKeysCount() {
        MopInteractionLedger ledger = new MopInteractionLedger(1);
        assertEquals(0, ledger.count("A", "k"));
        ledger.record("A", "k");
        ledger.record("A", "k");
        assertEquals(2, ledger.count("A", "k"));
        assertTrue(ledger.tried("A", "k"));
        assertEquals("per activity", 0, ledger.count("B", "k"));
    }

    @Test
    public void theDefaultBudgetIsTheFirstInteractionRule() {
        MopInteractionLedger ledger = new MopInteractionLedger(1);
        assertTrue(ledger.belowBudget("A", "k"));
        ledger.record("A", "k");
        assertFalse(ledger.belowBudget("A", "k"));
    }

    @Test
    public void aKeyStaysBelowBudgetUntilItCountsK() {
        MopInteractionLedger ledger = new MopInteractionLedger(3);
        for (int i = 0; i < 3; i++) {
            assertTrue("below budget after " + i + " executions", ledger.belowBudget("A", "k"));
            ledger.record("A", "k");
        }
        assertFalse(ledger.belowBudget("A", "k"));
        assertTrue("another activity has its own count", ledger.belowBudget("B", "k"));
    }
}
