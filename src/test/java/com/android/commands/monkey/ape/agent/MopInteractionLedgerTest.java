package com.android.commands.monkey.ape.agent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MopInteractionLedgerTest {

    @Test
    public void aKeyIsTriedOnlyAfterItsFirstInteractionInItsActivity() {
        MopInteractionLedger ledger = new MopInteractionLedger();
        assertFalse(ledger.tried("A", "//b|MODEL_CLICK"));
        ledger.record("A", "//b|MODEL_CLICK");
        assertTrue(ledger.tried("A", "//b|MODEL_CLICK"));
    }

    @Test
    public void theRecordIsPerActivityAndPerActionType() {
        MopInteractionLedger ledger = new MopInteractionLedger();
        ledger.record("A", "//b|MODEL_CLICK");
        assertFalse("another activity has its own first interaction",
                ledger.tried("B", "//b|MODEL_CLICK"));
        assertFalse("another action type is another key",
                ledger.tried("A", "//b|MODEL_LONG_CLICK"));
    }

    @Test
    public void recordingTwiceIsHarmless() {
        MopInteractionLedger ledger = new MopInteractionLedger();
        ledger.record("A", "k");
        ledger.record("A", "k");
        assertTrue(ledger.tried("A", "k"));
    }
}
