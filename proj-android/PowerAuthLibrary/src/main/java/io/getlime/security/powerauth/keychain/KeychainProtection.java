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

package io.getlime.security.powerauth.keychain;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;

import static io.getlime.security.powerauth.keychain.KeychainProtection.HARDWARE;
import static io.getlime.security.powerauth.keychain.KeychainProtection.NONE;
import static io.getlime.security.powerauth.keychain.KeychainProtection.SOFTWARE;
import static io.getlime.security.powerauth.keychain.KeychainProtection.STRONGBOX;
import static java.lang.annotation.RetentionPolicy.SOURCE;

/**
 * The {@code KeychainProtection} interface defines the level of {@link Keychain} content protection.
 * The level of the protection depends on Android KeyStore implementation available on the device.
 * If the KeyStore supports hardware backed keys, like StrongBox, then also the higher level of
 * protection is reported.
 * <p>
 * You can also enforce the minimum required level of keychain protection in {@link io.getlime.security.powerauth.sdk.PowerAuthKeychainConfiguration}.
 */
@Retention(SOURCE)
@IntDef({NONE, SOFTWARE, HARDWARE, STRONGBOX})
public @interface KeychainProtection {
    /**
     * The content of the keychain is not encrypted and therefore not protected. This level of
     * the protection is typically reported on devices older than Android Marshmallow, or in
     * case that the device has faulty KeyStore implementation.
     */
    int NONE = 1;

    /**
     * The content of the keychain is encrypted with key generated by Android KeyStore, but the key
     * is protected only on the operating system level. The security of the key material relies solely
     * on software measures, which means that a compromise of the Android OS (such as root exploit)
     * might up revealing this key.
     * <p>
     * If this level of protection is enforced in {@link io.getlime.security.powerauth.sdk.PowerAuthKeychainConfiguration},
     * then your application must target Android 6.0 and higher.
     */
    int SOFTWARE = 2;

    /**
     * The content of the keychain is encrypted with key generated by Android KeyStore and the key
     * is stored and managed by <a href="https://en.wikipedia.org/wiki/Trusted_execution_environment">Trusted Execution Environment</a>.
     */
    int HARDWARE = 3;

    /**
     * The content of the keychain is encrypted with key generated by Android KeyStore and the key
     * is stored inside of Secure Element (e.g. StrongBox). This is the highest level of Keychain
     * protection currently available.
     */
    int STRONGBOX = 4;
}
