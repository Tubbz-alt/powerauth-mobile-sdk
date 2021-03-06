/*
 * Copyright 2019 Wultra s.r.o.
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

package io.getlime.security.powerauth.biometry;

import android.content.Context;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.fragment.app.FragmentManager;
import android.util.Pair;

import io.getlime.security.powerauth.biometry.impl.BiometricAuthenticator;
import io.getlime.security.powerauth.biometry.impl.BiometricErrorDialogFragment;
import io.getlime.security.powerauth.biometry.impl.BiometricHelper;
import io.getlime.security.powerauth.biometry.impl.BiometricKeystore;
import io.getlime.security.powerauth.biometry.impl.BiometricResultDispatcher;
import io.getlime.security.powerauth.biometry.impl.IBiometricAuthenticator;
import io.getlime.security.powerauth.biometry.impl.PrivateRequestData;
import io.getlime.security.powerauth.biometry.impl.dummy.DummyBiometricAuthenticator;
import io.getlime.security.powerauth.biometry.impl.dummy.DummyBiometricKeystore;
import io.getlime.security.powerauth.biometry.impl.legacy.FingerprintAuthenticator;
import io.getlime.security.powerauth.exception.PowerAuthErrorCodes;
import io.getlime.security.powerauth.exception.PowerAuthErrorException;
import io.getlime.security.powerauth.networking.interfaces.ICancelable;
import io.getlime.security.powerauth.sdk.impl.CancelableTask;
import io.getlime.security.powerauth.sdk.impl.DefaultCallbackDispatcher;
import io.getlime.security.powerauth.sdk.impl.DummyCancelable;
import io.getlime.security.powerauth.system.PA2Log;

/**
 * The {@code BiometricAuthentication} class is a high level interface that provides interfaces related
 * to the biometric authentication. The class hides all technical details, so it can be safely
 * used also on the systems that doesn't provide biometric interfaces, or if the system has no
 * biometric sensor available.
 *
 * The class is internally used in the PowerAuth Mobile SDK, but can be utilized also by the
 * application developers.
 */
public class BiometricAuthentication {

  /**
     * Returns object representing a Keystore used to store biometry related key. If the biometric
     * authentication is not available on the authenticator, then returns a dummy implementation where
     * all interface methods fails, or does not provide the required information.
     *
     * @return Object implementing {@link IBiometricKeystore} interface.
     */
    public static @NonNull IBiometricKeystore getBiometricKeystore() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new BiometricKeystore();
        }
        return new DummyBiometricKeystore();
    }


    /**
     * Check whether biometric authentication is available on this authenticator and can be used
     * in this SDK.
     *
     * @param context Android {@link Context} object
     * @return true if this authenticator supports a biometric authentication, otherwise false.
     */
    public static boolean isBiometricAuthenticationAvailable(@NonNull final Context context) {
        synchronized (SharedContext.class) {
            return getContext().getAuthenticator(context).isAvailable();
        }
    }


    /**
     * Check whether biometric authentication is available on this authenticator and biometric data
     * are enrolled on the system.
     *
     * @param context Android {@link Context} object
     * @return Constant integer from {@link BiometricStatus} interface, representing status of
     *         biometry on the authenticator.
     */
    public static @BiometricStatus int canAuthenticate(@NonNull final Context context) {
        synchronized (SharedContext.class) {
            return getContext().getAuthenticator(context).canAuthenticate();
        }
    }

    /**
     * Performs biometric authentication.
     *
     * @param context Android {@link Context} object
     * @param fragmentManager Android {@link FragmentManager} object
     * @param request {@link BiometricAuthenticationRequest} object with data for biometric authentication
     * @param callback {@link IBiometricAuthenticationCallback} callback to receive authentication result.
     * @return Returns {@link ICancelable} object that allows you to cancel that authentication request.
     */
    @UiThread
    public static @NonNull ICancelable authenticate(@NonNull final Context context,
                                                    @NonNull final FragmentManager fragmentManager,
                                                    @NonNull final BiometricAuthenticationRequest request,
                                                    @NonNull final IBiometricAuthenticationCallback callback) {
        synchronized (SharedContext.class) {
            // Check whether there's already pending authentication request.
            final SharedContext ctx = getContext();
            if (!ctx.startBiometricAuthentication()) {
                // There's already pending biometric authentication request.
                return reportSimultaneousRequest(callback);
            }

            // Acquire authenticator from the shared context
            final IBiometricAuthenticator device = ctx.getAuthenticator(context);
            // Prepare essential authentication request data
            final BiometricResultDispatcher dispatcher = new BiometricResultDispatcher(callback, new DefaultCallbackDispatcher(), new BiometricResultDispatcher.IResultCompletion() {
                @Override
                public void onCompletion() {
                    // Clear the pending request flag.
                    synchronized (SharedContext.class) {
                        ctx.finishPendingBiometricAuthentication();
                    }
                }

                @Override
                public void onBiometricKeyUnavailable() {
                    // Remove the default key, because the biometric key is no longer available.
                    device.getBiometricKeystore().removeBiometricKeyEncryptor();
                }
            });
            final PrivateRequestData requestData = new PrivateRequestData(request, dispatcher, ctx.getBiometricDialogResources());

            // Validate request status
            @BiometricStatus int status = device.canAuthenticate();
            PowerAuthErrorException exception = null;
            if (status == BiometricStatus.OK) {
                try {
                    if (request.isForceGenerateNewKey() && !request.getBiometricKeyEncryptor().isAuthenticationRequiredOnEncryption()) {
                        // Biometric authentication is not actually required, because we're generating (e.g encrypting) the key
                        // and the encryptor doesn't require authentication for such task.
                        return justEncryptBiometricKey(request, dispatcher);
                    } else {
                        // Authenticate with device
                        return device.authenticate(context, fragmentManager, requestData);
                    }

                } catch (PowerAuthErrorException e) {
                    // Failed to authenticate. Show an error dialog and report that exception to the callback.
                    PA2Log.e("BiometricAuthentication.authenticate() failed with exception: " + e.getMessage());
                    exception = e;
                    status = BiometricStatus.NOT_AVAILABLE;

                } catch (IllegalArgumentException e) {
                    // Failed to authenticate due to a wrong configuration.
                    PA2Log.e("BiometricAuthentication.authenticate() failed with exception: " + e.getMessage());
                    exception = new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeWrongParameter, e.getMessage());
                    status = BiometricStatus.NOT_AVAILABLE;
                }
            }
            // Failed to use biometric authentication. At first, we should cleanup the possible stored
            // biometric key.
            device.getBiometricKeystore().removeBiometricKeyEncryptor();

            // Now show the error dialog, and report the exception later.
            if (exception == null) {
                exception = BiometricHelper.getExceptionForBiometricStatus(status);
            }
            return showErrorDialog(status, exception, context, fragmentManager, requestData);
        }
    }

    /**
     * This helper method only encrypts a raw key data with encryptor and dispatch result back to the
     * application. The encryptor should not require the biometric authentication on it's encrypt task.
     *
     * @param request Request object containing raw key data and encryptor.
     * @param dispatcher Biometric result dispatcher.
     * @return Result from {@link BiometricResultDispatcher#getCancelableTask()}.
     * @throws PowerAuthErrorException If cannot encrypt biometric key.
     */
    private static @NonNull ICancelable justEncryptBiometricKey(
            @NonNull BiometricAuthenticationRequest request,
            @NonNull BiometricResultDispatcher dispatcher) throws PowerAuthErrorException {

        // Initialize encryptor's cipher
        final IBiometricKeyEncryptor encryptor = request.getBiometricKeyEncryptor();
        final boolean initializationSuccess = encryptor.initializeCipher(true) != null;
        // Encrypt the key
        final BiometricKeyData keyData = initializationSuccess ? encryptor.encryptBiometricKey(request.getRawKeyData()) : null;
        if (keyData == null) {
            throw new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeBiometryNotAvailable, "Failed to encrypt biometric key.");
        }
        // In case of success, just dispatch the result back to the application
        dispatcher.dispatchSuccess(keyData);
        return dispatcher.getCancelableTask();
    }

    /**
     * Show dialog fragment with the error message in case that the biometric authentication fails at the authentication initialization phase.
     *
     * @param status {@link BiometricStatus} that caused the failure.
     * @param exception {@link PowerAuthErrorException} that will be reported to the callback.
     * @param context Android {@link Context} object
     * @param fragmentManager Fragment manager that manages created alert
     * @param requestData Private request data.
     * @return Returns {@link ICancelable} object that allows you to cancel that authentication request.
     */
    private static @NonNull ICancelable showErrorDialog(
            @BiometricStatus int status,
            @NonNull final PowerAuthErrorException exception,
            @NonNull final Context context,
            @NonNull final FragmentManager fragmentManager,
            @NonNull final PrivateRequestData requestData) {

        final CancelableTask cancelableTask = requestData.getDispatcher().getCancelableTask();

        final BiometricDialogResources resources = requestData.getResources();
        final Pair<Integer, Integer> titleDescription = BiometricHelper.getErrorDialogStringsForBiometricStatus(status, resources);

        final BiometricErrorDialogFragment dialogFragment = new BiometricErrorDialogFragment.Builder(context)
                .setTitle(titleDescription.first)
                .setMessage(titleDescription.second)
                .setCloseButton(resources.strings.ok, resources.colors.closeButtonText)
                .setIcon(resources.drawables.errorIcon)
                .setOnCloseListener(new BiometricErrorDialogFragment.OnCloseListener() {
                    @Override
                    public void onClose() {
                        requestData.getDispatcher().dispatchError(exception);
                    }
                })
                .build();
        // Handle cancel from the application
        requestData.getDispatcher().setOnCancelListener(new CancelableTask.OnCancelListener() {
            @Override
            public void onCancel() {
                dialogFragment.dismiss();
            }
        });

        // Show fragment
        dialogFragment.show(fragmentManager, BiometricErrorDialogFragment.FRAGMENT_DEFAULT_TAG);

        return cancelableTask;
    }

    /**
     * Report cancel to provided callback in case that this is the simultaneous biometric authentication request.
     * @param callback Callback to report the cancel.
     * @return Dummy {@link ICancelable} object that does nothing.
     */
    private static ICancelable reportSimultaneousRequest(@NonNull final IBiometricAuthenticationCallback callback) {
        PA2Log.e("Cannot execute more than one biometric authentication request at the same time. This request is going to be canceled.");
        // Report cancel to the main thread.
        new DefaultCallbackDispatcher().dispatchCallback(new Runnable() {
            @Override
            public void run() {
                // Report cancel.
                callback.onBiometricDialogCancelled(false);
            }
        });
        // Return dummy cancelable object.
        return new DummyCancelable();
    }

    /**
     * Sets shared {@link BiometricDialogResources} object to this class. You can use this method
     * to override a default resources provided by this SDK.
     *
     * @param resources New biometric dialog resources to be set.
     */
    public static void setBiometricDialogResources(@NonNull BiometricDialogResources resources) {
        synchronized (SharedContext.class) {
            getContext().setBiometricDialogResources(resources);
        }
    }

    /**
     * @return Shared instance of {@link BiometricDialogResources} object.
     */
    public @NonNull BiometricDialogResources getBiometricDialogResources() {
        synchronized (SharedContext.class) {
            return getContext().getBiometricDialogResources();
        }
    }

    /**
     * Set new {@code BiometricPrompt} based authentication disabled for this device and force to use
     * the legacy {@code FingerprintManager} authenticator. This is useful for situations when device's
     * manufacturer provides a faulty implementation of {@code BiometricPrompt} and therefore
     * PowerAuth SDK cannot use it for biometric authentication tasks.
     *
     * @param disabled Set {@code true} to disable new {@code BiometricPrompt} based authentication method.
     */
    public static void setBiometricPromptAuthenticationDisabled(boolean disabled) {
        synchronized (SharedContext.class) {
            getContext().setBiometricPromptAuthenticationDisabled(disabled);
        }
    }

    /**
     * @return {@code true} when {@code BiometricPrompt} based authentication is disabled for this device.
     */
    public static boolean isBiometricPromptAuthenticationDisabled() {
        synchronized (SharedContext.class) {
            return getContext().isBiometricPromptAuthenticationDisabled();
        }
    }

    /**
     * Return type of biometry supported on the system.
     *
     * @param context Android context object
     * @return {@link BiometryType} representing supported biometry on the system.
     */
    public static @BiometryType int getBiometryType(@NonNull Context context) {
        synchronized (SharedContext.class) {
            return getContext().getBiometryType(context);
        }
    }

    /**
     * The {@code SharedContext} nested class contains shared data, required for the biometric tasks.
     */
    private static class SharedContext {

        /**
         * Shared instance of this class.
         */
        private static final SharedContext INSTANCE = new SharedContext();

        /**
         * Contains shared {@link BiometricDialogResources} object.
         */
        private @NonNull BiometricDialogResources biometricDialogResources;

        /**
         * Contains {@link IBiometricAuthenticator} in case that keeping a reference to a permanent authenticator
         * may be tolerable. This is for example in cases that biometric functions are not available
         * on the authenticator.
         */
        private @Nullable IBiometricAuthenticator authenticator;

        /**
         * Contains {@code true} in case that legacy fingerprint authentication must be used on devices
         * supporting the new {@code BiometricPrompt}.
         */
        private boolean isBiometricPromptAuthenticationDisabled = false;

        /**
         * Contains {@code true} in case that there's already pending biometric authentication.
         */
        private boolean isPendingBiometricAuthentication = false;

        /**
         * Private {@code SharedContext} constructor.
         */
        private SharedContext() {
            biometricDialogResources = new BiometricDialogResources.Builder().build();
            authenticator = null;
        }

        /**
         * @param resources Sets new {@link BiometricDialogResources} object with resources for
         *                  fingerprint dialog resources.
         */
        void setBiometricDialogResources(@NonNull BiometricDialogResources resources) {
            biometricDialogResources = resources;
        }

        /**
         * @return {@link BiometricDialogResources} shared object with resources for fingerprint dialog.
         */
        @NonNull BiometricDialogResources getBiometricDialogResources() {
            return biometricDialogResources;
        }

        /**
         * @return {@code true} if new {@code BiometricPrompt} based authentication is disabled.
         */
        boolean isBiometricPromptAuthenticationDisabled() {
            return isBiometricPromptAuthenticationDisabled;
        }

        /**
         * Set new {@code BiometricPrompt} based authentication disabled.
         * @param disabled Set {@code true} to disable {@code BiometricPrompt} based authentication.
         */
        void setBiometricPromptAuthenticationDisabled(boolean disabled) {
            isBiometricPromptAuthenticationDisabled = disabled;
        }

        /**
         * Returns object implementing {@link IBiometricAuthenticator} interface. The returned implementation
         * depends on the version of Android system and on the authenticator's capabilities. If current system
         * doesn't support biometric related APIs, or if the authenticator itself has no biometric sensor
         * available, then returns a dummy implementation that reject all requested operations.
         *
         * @param context Android {@link Context} object
         * @return Object implementing {@link IBiometricAuthenticator} interface.
         */
        @NonNull
        IBiometricAuthenticator getAuthenticator(@NonNull final Context context) {
            // Check if authenticator has been already created
            if (authenticator != null) {
                return authenticator;
            }
            // If Android 9.0 "Pie" and newer, then try to build authenticator supporting BiometricPrompt.
            final boolean isBiometricPromptDisabled = isBiometricPromptAuthenticationDisabled
                    || BiometricHelper.shouldFallbackToFingerprintManager(context);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !isBiometricPromptDisabled) {
                final IBiometricAuthenticator newAuthenticator = BiometricAuthenticator.createAuthenticator(context, getBiometricKeystore());
                if (newAuthenticator != null) {
                    return newAuthenticator;
                }
            }
            // If Android 6.0 "Marshmallow" and newer, then try to build authenticator based on FingerprintManager.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                final IBiometricAuthenticator newAuthenticator = FingerprintAuthenticator.createAuthenticator(context, getBiometricKeystore());
                if (newAuthenticator != null) {
                    return newAuthenticator;
                }
            }
            // Otherwise return dummy authenticator, which provides no biometric functions.
            // In this case, we can cache the authenticator.
            authenticator = new DummyBiometricAuthenticator();
            return authenticator;
        }

        /**
         * Check whether there's a pending biometric authentication request. If no, then start
         * a new one.
         *
         * @return {@code false} if there's already pending biometric authentication request.
         */
        boolean startBiometricAuthentication() {
            if (isPendingBiometricAuthentication) {
                return false;
            }
            isPendingBiometricAuthentication = true;
            return true;
        }

        /**
         * Finish previously started biometric authentication request.
         */
        void finishPendingBiometricAuthentication() {
            isPendingBiometricAuthentication = false;
        }

        /**
         * Flag that indicates that value of {@link #biometryType} is already evaluated.
         */
        private boolean isBiometryTypeEvaluated = false;

        /**
         * Evaluated type of biometry supported on the device.
         */
        private @BiometryType int biometryType = BiometryType.NONE;

        /**
         * Return type of biometry supported on the system.
         *
         * @param context Android context object
         * @return {@link BiometryType} representing supported biometry on the system.
         */
        @BiometryType int getBiometryType(@NonNull Context context) {
            if (!isBiometryTypeEvaluated) {
                biometryType = getAuthenticator(context).getBiometryType(context);
                isBiometryTypeEvaluated = true;
            }
            return biometryType;
        }
    }

    /**
     * @return Object with shared data.
     */
    private static @NonNull SharedContext getContext() {
        return SharedContext.INSTANCE;
    }
}
