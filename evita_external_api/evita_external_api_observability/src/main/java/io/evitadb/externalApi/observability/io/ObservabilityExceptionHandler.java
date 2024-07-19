/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.observability.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.server.HttpService;
import io.evitadb.externalApi.http.JsonApiExceptionHandler;
import io.evitadb.externalApi.observability.ObservabilityProvider;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

/**
 * Handles exception that occurred during processing of HTTP request outside JfrRecordingEndpointHandler execution.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
@Slf4j
public class ObservabilityExceptionHandler extends JsonApiExceptionHandler {

	public ObservabilityExceptionHandler(@Nonnull ObjectMapper objectMapper, @Nonnull HttpService next) {
		super(objectMapper, next);
	}

	@Nonnull
	@Override
	protected String getExternalApiCode() {
		return ObservabilityProvider.CODE;
	}
}
