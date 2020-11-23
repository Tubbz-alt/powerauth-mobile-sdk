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

package io.getlime.security.powerauth.integration.support.endpoints;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.gson.reflect.TypeToken;

import io.getlime.security.powerauth.integration.support.model.ActivationDetail;

public class GetActivationStatusEndpoint implements IServerApiEndpoint<GetActivationStatusEndpoint.Response> {

    @NonNull
    @Override
    public String getRelativePath() {
        return "/rest/v3/activation/status";
    }

    @Nullable
    @Override
    public TypeToken<Response> getResponseType() {
        return TypeToken.get(Response.class);
    }

    // Request

    public static class Request {

        private String activationId;
        private String challenge;

        public String getActivationId() {
            return activationId;
        }

        public void setActivationId(String activationId) {
            this.activationId = activationId;
        }

        public String getChallenge() {
            return challenge;
        }

        public void setChallenge(String challenge) {
            this.challenge = challenge;
        }
    }

    // Response

    public static class Response extends ActivationDetail {
    }
}
