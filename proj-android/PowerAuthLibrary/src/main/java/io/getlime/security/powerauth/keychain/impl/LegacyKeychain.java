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

package io.getlime.security.powerauth.keychain.impl;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Base64;

import io.getlime.security.powerauth.keychain.Keychain;

/**
 * The {@code LegacyKeychain} class implements {@link Keychain} interface with no content
 * encryption. The class is used on all devices that doesn't support KeyStore reliably
 * (e.g. on all systems older than Android "M".)
 */
public class LegacyKeychain implements Keychain {

    private final String identifier;
    private final Context context;

    /**
     * Default constructor, initialize keychain with given identifier.
     * @param context Android context.
     * @param identifier Identifier.
     */
    public LegacyKeychain(@NonNull Context context, @NonNull String identifier) {
        this.context = context;
        this.identifier = identifier;
    }

    @NonNull
    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public boolean isEncrypted() {
        return false;
    }

    @Override
    public boolean isReservedKey(@NonNull String key) {
        return ReservedKeyImpl.isReservedKey(key);
    }

    // Byte array accessors

    @Override
    public synchronized boolean containsDataForKey(@NonNull String key) {
        return getValue(key) != null;
    }

    @Override
    public synchronized void removeDataForKey(@NonNull String key) {
        ReservedKeyImpl.failOnReservedKey(key);
        getSharedPreferences()
                .edit()
                .remove(key)
                .apply();
    }

    @Override
    public synchronized void removeAll() {
        getSharedPreferences()
                .edit()
                .clear()
                .apply();
    }

    @Nullable
    @Override
    public synchronized byte[] dataForKey(@NonNull String key) {
        final String serializedData = getValue(key);
        if (serializedData != null) {
            final byte[] data = Base64.decode(serializedData, Base64.DEFAULT);
            return data.length > 0 ? data : null;
        }
        return null;
    }

    @Override
    public synchronized void putDataForKey(@Nullable byte[] data, @NonNull String key) {
        final String serializedData = data != null ? Base64.encodeToString(data, Base64.DEFAULT) : null;
        setValue(key, serializedData);
    }

    // String accessors

    @Nullable
    @Override
    public synchronized String stringForKey(@NonNull String key) {
        return getValue(key);
    }

    @Override
    public synchronized void putStringForKey(@Nullable String string, @NonNull String key) {
        setValue(key, string);
    }

    // Private methods

    /**
     * @return Underlying {@code SharedPreferences} that contains content of keychain.
     */
    private @NonNull SharedPreferences getSharedPreferences() {
        return context.getSharedPreferences(identifier, Context.MODE_PRIVATE);
    }

    /**
     * Return value stored in the shared preferences.
     *
     * @param key Key to be used for string retrieval.
     * @return Stored value in case there are some data under given key, null otherwise.
     */
    private @Nullable String getValue(@NonNull String key) {
        ReservedKeyImpl.failOnReservedKey(key);
        return getSharedPreferences().getString(key, null);
    }

    /**
     * Put value to the shared preferences.
     *
     * @param key Key to be used for storing string.
     * @param value String to be stored. If value is null then it's equal to {@code removeDataForKey()}.
     */
    private void setValue(@NonNull String key, @Nullable String value) {
        ReservedKeyImpl.failOnReservedKey(key);
        getSharedPreferences()
                .edit()
                .putString(key, value)
                .apply();
    }
}
