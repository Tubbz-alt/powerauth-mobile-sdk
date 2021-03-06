/*
 * Copyright 2020 Wultra s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.getlime.security.powerauth.integration.tests;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import io.getlime.security.powerauth.core.ActivationStatus;
import io.getlime.security.powerauth.exception.PowerAuthErrorCodes;
import io.getlime.security.powerauth.integration.support.AsyncHelper;
import io.getlime.security.powerauth.integration.support.PowerAuthTestHelper;
import io.getlime.security.powerauth.integration.support.model.Activation;
import io.getlime.security.powerauth.integration.support.model.ActivationDetail;
import io.getlime.security.powerauth.networking.response.CreateActivationResult;
import io.getlime.security.powerauth.networking.response.IActivationRemoveListener;
import io.getlime.security.powerauth.networking.response.ICreateActivationListener;
import io.getlime.security.powerauth.sdk.PowerAuthSDK;
import io.getlime.security.powerauth.system.PA2System;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class StandardActivationTest {

    private PowerAuthTestHelper testHelper;
    private PowerAuthSDK powerAuthSDK;
    private ActivationHelper activationHelper;

    @Before
    public void setUp() throws Exception {
        testHelper = new PowerAuthTestHelper.Builder().build();
        powerAuthSDK = testHelper.getSharedSdk();
        activationHelper = new ActivationHelper(testHelper);
    }

    @After
    public void tearDown() {
        if (activationHelper != null) {
            activationHelper.cleanupAfterTest();
        }
    }

    // Using PowerAuthActivation

    @Test
    public void testCreateWithActivationCode() throws Exception {
        activationHelper.createStandardActivation(false, null);
        // Validate valid and invalid password
        boolean passwordValid = activationHelper.validateUserPassword(activationHelper.getValidPassword());
        assertTrue(passwordValid);
        passwordValid = activationHelper.validateUserPassword(activationHelper.getInvalidPassword());
        assertFalse(passwordValid);
    }

    @Test
    public void testCreateWithActivationCodeAndSignature() throws Exception {
        activationHelper.createStandardActivation(true, null);
        // Validate valid and invalid password
        boolean passwordValid = activationHelper.validateUserPassword(activationHelper.getValidPassword());
        assertTrue(passwordValid);
        passwordValid = activationHelper.validateUserPassword(activationHelper.getInvalidPassword());
        assertFalse(passwordValid);
    }

    @Test
    public void testCreateWithExtraAttributes() throws Exception {
        final String extras = "extra,attributes";
        final ActivationDetail activationDetail = activationHelper.createStandardActivation(true, extras);
        // Validate extras
        assertEquals(extras, activationDetail.getExtras());
        // Validate platform & device info
        assertEquals("android", activationDetail.getPlatform());
        assertEquals(PA2System.getDeviceInfo(), activationDetail.getDeviceInfo());
    }

    // Using legacy method

    @Test
    public void testLegacyCreateWithActivationCode() throws Exception {
        legacyCreateActivation(false);
        // Validate valid and invalid password
        boolean passwordValid = activationHelper.validateUserPassword(activationHelper.getValidPassword());
        assertTrue(passwordValid);
        passwordValid = activationHelper.validateUserPassword(activationHelper.getInvalidPassword());
        assertFalse(passwordValid);
    }

    @Test
    public void testLegacyCreateWithActivationCodeAndSignature() throws Exception {
        legacyCreateActivation(true);
        // Validate valid and invalid password
        boolean passwordValid = activationHelper.validateUserPassword(activationHelper.getValidPassword());
        assertTrue(passwordValid);
        passwordValid = activationHelper.validateUserPassword(activationHelper.getInvalidPassword());
        assertFalse(passwordValid);
    }

    /**
     * Create activation with using legacy methods (e.g. without PowerAuthActivation object).
     * @param codeWithSignature true if activation should use code and signature.
     * @throws Exception In case of failure.
     */
    private void legacyCreateActivation(boolean codeWithSignature) throws Exception {
        // Initial expectations
        assertFalse(powerAuthSDK.hasValidActivation());
        assertFalse(powerAuthSDK.hasPendingActivation());
        assertTrue(powerAuthSDK.canStartActivation());

        final List<String> passwords = activationHelper.prepareAuthentications();

        // Initialize activation on the server
        final Activation activation = activationHelper.initActivation();

        // Create activation locally
        final String activationCode;
        if (codeWithSignature) {
            activationCode = activation.getActivationCode() + "#" + activation.getActivationSignature();
        } else {
            activationCode = activation.getActivationCode();
        }
        final CreateActivationResult createActivationResult = AsyncHelper.await(new AsyncHelper.Execution<CreateActivationResult>() {
            @Override
            public void execute(@NonNull final AsyncHelper.ResultCatcher<CreateActivationResult> resultCatcher) throws Exception {
                powerAuthSDK.createActivation(testHelper.getDeviceInfo(), activationCode, new ICreateActivationListener() {
                    @Override
                    public void onActivationCreateSucceed(@NonNull CreateActivationResult result) {
                        resultCatcher.completeWithResult(result);
                    }

                    @Override
                    public void onActivationCreateFailed(@NonNull Throwable t) {
                        resultCatcher.completeWithError(t);
                    }
                });
                assertFalse(powerAuthSDK.hasValidActivation());
                assertTrue(powerAuthSDK.hasPendingActivation());
                assertFalse(powerAuthSDK.canStartActivation());
            }
        });

        assertFalse(powerAuthSDK.hasValidActivation());
        assertTrue(powerAuthSDK.hasPendingActivation());
        assertFalse(powerAuthSDK.canStartActivation());

        // Commit activation locally
        int resultCode = powerAuthSDK.commitActivationWithPassword(testHelper.getContext(), passwords.get(0), null);
        if (resultCode != PowerAuthErrorCodes.PA2Succeed) {
            throw new Exception("PowerAuthSDK.commit failed with error code " + resultCode);
        }

        assertTrue(powerAuthSDK.hasValidActivation());
        assertFalse(powerAuthSDK.hasPendingActivation());
        assertFalse(powerAuthSDK.canStartActivation());

        // Fetch status to test whether it's in "pending commit" state.
        ActivationStatus activationStatus = activationHelper.fetchActivationStatus();
        if (activationStatus.state != ActivationStatus.State_Pending_Commit) {
            throw new Exception("Activation is in invalid state after creation. State = " + activationStatus.state);
        }

        // Compare public key fingerprints
        final ActivationDetail activationDetail = activationHelper.getActivationDetail();
        if (!activationDetail.getDevicePublicKeyFingerprint().equals(createActivationResult.getActivationFingerprint())) {
            throw new Exception("Public key fingerprints doesn't match between server and client.");
        }

        // Commit activation on the server.
        testHelper.getServerApi().activationCommit(activation);

        // Fetch status to validate whether activation is now active
        activationStatus = activationHelper.fetchActivationStatus();
        if (activationStatus.state != ActivationStatus.State_Active) {
            throw new Exception("Activation is in invalid state after commit. State = " + activationStatus.state);
        }
    }

    // Remove activation

    @Test
    public void testRemoveActivationLocal() throws Exception {
        activationHelper.createStandardActivation(true, null);
        // Remove activation
        powerAuthSDK.removeActivationLocal(testHelper.getContext(), true);
        // Back to Initial expectations
        assertFalse(powerAuthSDK.hasValidActivation());
        assertFalse(powerAuthSDK.hasPendingActivation());
        assertTrue(powerAuthSDK.canStartActivation());
    }

    @Test
    public void testRemoveActivationWithAuthentication() throws Exception {

        activationHelper.createStandardActivation(true, null);

        boolean removed = AsyncHelper.await(new AsyncHelper.Execution<Boolean>() {
            @Override
            public void execute(@NonNull final AsyncHelper.ResultCatcher<Boolean> resultCatcher) throws Exception {
                // Now remove activation
                powerAuthSDK.removeActivationWithAuthentication(testHelper.getContext(), activationHelper.getValidAuthentication(), new IActivationRemoveListener() {
                    @Override
                    public void onActivationRemoveSucceed() {
                        resultCatcher.completeWithResult(true);
                    }

                    @Override
                    public void onActivationRemoveFailed(Throwable t) {
                        resultCatcher.completeWithError(t);
                    }
                });
            }
        });
        assertTrue(removed);

        // Back to initial expectations
        assertFalse(powerAuthSDK.hasValidActivation());
        assertFalse(powerAuthSDK.hasPendingActivation());
        assertTrue(powerAuthSDK.canStartActivation());
    }

}
