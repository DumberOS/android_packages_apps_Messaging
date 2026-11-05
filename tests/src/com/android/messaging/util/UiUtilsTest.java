package com.android.messaging.util;

import com.android.messaging.BugleTestCase;
import com.android.messaging.FakeFactory;
import com.android.messaging.datamodel.data.ParticipantData;

import org.mockito.Mockito;

/** Send preconditions must respect the conversation's SIM selection on dual-SIM phones. */
public class UiUtilsTest extends BugleTestCase {
    private PhoneUtils mPhoneUtils;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        FakeFactory.register(getTestContext());
        mPhoneUtils = PhoneUtils.getDefault();
        Mockito.when(mPhoneUtils.isSmsCapable()).thenReturn(true);
        Mockito.when(mPhoneUtils.isDefaultSmsApp()).thenReturn(true);
        Mockito.when(mPhoneUtils.getHasPreferredSmsSim()).thenReturn(false);
    }

    public void testSelectedSimDoesNotRequireSystemSmsDefault() {
        assertTrue(UiUtils.isReadyForAction(3));
        assertTrue(UiUtils.isReadyForAction(4));
    }

    public void testNoSelectedSimStillRequiresSystemSmsDefault() {
        assertFalse(UiUtils.isReadyForAction(ParticipantData.DEFAULT_SELF_SUB_ID));
        assertFalse(UiUtils.isReadyForAction());
        Mockito.when(mPhoneUtils.getHasPreferredSmsSim()).thenReturn(true);
        assertTrue(UiUtils.isReadyForAction(ParticipantData.DEFAULT_SELF_SUB_ID));
        assertTrue(UiUtils.isReadyForAction());
    }

    public void testSelectedSimDoesNotBypassDefaultAppRequirement() {
        Mockito.when(mPhoneUtils.isDefaultSmsApp()).thenReturn(false);
        assertFalse(UiUtils.isReadyForAction(3));
    }

    public void testSelectedSimDoesNotBypassSmsCapabilityRequirement() {
        Mockito.when(mPhoneUtils.isSmsCapable()).thenReturn(false);
        assertFalse(UiUtils.isReadyForAction(3));
    }
}
