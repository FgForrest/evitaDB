/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.lab.api.resolver.endpoint;

import com.linecorp.armeria.common.HttpMethod;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.system.dto.LivenessDto;
import io.evitadb.externalApi.rest.io.JsonRestHandler;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;

import javax.annotation.Nonnull;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Returns information about liveness of REST API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class LivenessHandler extends JsonRestHandler<LabApiHandlingContext> {

	public LivenessHandler(@Nonnull LabApiHandlingContext labApiHandlingContext) {
		super(labApiHandlingContext);
	}

	@Nonnull
	@Override
	protected CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull RestEndpointExecutionContext executionContext) {
		return CompletableFuture.supplyAsync(() -> {
			final ExecutedEvent requestExecutedEvent = executionContext.requestExecutedEvent();
			requestExecutedEvent.finishInputDeserialization();

			final LivenessDto liveness = new LivenessDto(true);
			requestExecutedEvent.finishOperationExecution();

			final Object result = convertResultIntoSerializableObject(executionContext, liveness);
			requestExecutedEvent.finishResultSerialization();

			return new SuccessEndpointResponse(result);
		});
	}

	@Nonnull
	@Override
	public Set<String> getSupportedHttpMethods() {
		return Set.of(HttpMethod.GET.name());
	}

	@Nonnull
	@Override
	public LinkedHashSet<String> getSupportedResponseContentTypes() {
		return DEFAULT_SUPPORTED_CONTENT_TYPES;
	}
}
