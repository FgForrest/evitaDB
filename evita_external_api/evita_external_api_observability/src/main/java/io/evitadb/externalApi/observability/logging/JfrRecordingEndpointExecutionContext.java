/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.observability.logging;

import com.linecorp.armeria.common.HttpRequest;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.http.EndpointExecutionContext;
import io.evitadb.externalApi.observability.exception.ObservabilityInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Implementation of {@link EndpointExecutionContext} for Observation API.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class JfrRecordingEndpointExecutionContext extends EndpointExecutionContext {

	@Nullable private String requestBodyContentType;
	@Nullable private String preferredResponseContentType;

	public JfrRecordingEndpointExecutionContext(
		@Nonnull HttpRequest httpRequest,
		@Nonnull Evita evita
	) {
		super(httpRequest, evita);
	}

	@Override
	public void provideRequestBodyContentType(@Nonnull String contentType) {
		Assert.isPremiseValid(
			this.requestBodyContentType == null,
			() -> new ObservabilityInternalError("Request body content type already provided.")
		);
		this.requestBodyContentType = contentType;
	}

	@Nullable
	@Override
	public String requestBodyContentType() {
		return this.requestBodyContentType;
	}

	@Override
	public void providePreferredResponseContentType(@Nonnull String contentType) {
		Assert.isPremiseValid(
			this.preferredResponseContentType == null,
			() -> new ObservabilityInternalError("Preferred response content type already provided.")
		);
		this.preferredResponseContentType = contentType;
	}

	@Nullable
	@Override
	public String preferredResponseContentType() {
		return this.preferredResponseContentType;
	}

};
