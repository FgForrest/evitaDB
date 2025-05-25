/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint;

import com.linecorp.armeria.common.HttpMethod;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.NotFoundEndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.DeleteEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.RequireConstraintFromRequestQueryBuilder;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Handles single entity delete request.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class DeleteEntityHandler extends EntityHandler<CollectionRestHandlingContext> {

	public DeleteEntityHandler(@Nonnull CollectionRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
	}

	@Override
	protected boolean modifiesData() {
		return true;
	}

	@Override
	@Nonnull
	protected CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull RestEndpointExecutionContext executionContext) {
		return executionContext.executeAsyncInTransactionThreadPool(
			() -> {
				final ExecutedEvent requestExecutedEvent = executionContext.requestExecutedEvent();

				final Map<String, Object> parametersFromRequest = getParametersFromRequest(executionContext);
				Assert.isTrue(
					parametersFromRequest.containsKey(DeleteEntityEndpointHeaderDescriptor.PRIMARY_KEY.name()),
					() -> new RestInvalidArgumentException("Primary key wasn't found in URL.")
				);
				requestExecutedEvent.finishInputDeserialization();

				final EntityContentRequire[] entityContentRequires = requestExecutedEvent.measureInternalEvitaDBInputReconstruction(() ->
					RequireConstraintFromRequestQueryBuilder.getEntityContentRequires(parametersFromRequest));

				final Optional<SealedEntity> deletedEntity = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
					executionContext.session().deleteEntity(
						this.restHandlingContext.getEntityType(),
						(Integer) parametersFromRequest.get(DeleteEntityEndpointHeaderDescriptor.PRIMARY_KEY.name()),
						entityContentRequires
					));
				requestExecutedEvent.finishOperationExecution();

				final Optional<Object> result = deletedEntity.map(it -> convertResultIntoSerializableObject(executionContext, it));
				requestExecutedEvent.finishResultSerialization();

				return result
					.map(it -> (EndpointResponse) new SuccessEndpointResponse(result.orElse(null)))
					.orElse(new NotFoundEndpointResponse());
			}
		);
	}

	@Nonnull
	@Override
	public Set<HttpMethod> getSupportedHttpMethods() {
		return Set.of(HttpMethod.DELETE);
	}
}
