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
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.head.Head;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.NotFoundEndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.FilterByConstraintFromRequestQueryBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.RequireConstraintFromRequestQueryBuilder;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import io.evitadb.externalApi.rest.traffic.RestQueryLabels;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.head;
import static io.evitadb.api.query.QueryConstraints.label;

/**
 * Handles request for single entity
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class GetEntityHandler extends EntityHandler<CollectionRestHandlingContext> {

	public GetEntityHandler(@Nonnull CollectionRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
	}

	@Override
	@Nonnull
	protected CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull RestEndpointExecutionContext executionContext) {
		return executionContext.executeAsyncInRequestThreadPool(
			() -> {
				final ExecutedEvent requestExecutedEvent = executionContext.requestExecutedEvent();

				final Map<String, Object> parametersFromRequest = getParametersFromRequest(executionContext);
				requestExecutedEvent.finishInputDeserialization();

				final Query query = requestExecutedEvent.measureInternalEvitaDBInputReconstruction(() -> Query.query(
					buildHead(executionContext),
					FilterByConstraintFromRequestQueryBuilder.buildFilterByForSingleEntity(parametersFromRequest, this.restHandlingContext.getEntitySchema()),
					RequireConstraintFromRequestQueryBuilder.buildRequire(parametersFromRequest)
				));

				log.debug("Generated evitaDB query for single entity fetch of type `{}` is `{}`.", this.restHandlingContext.getEntitySchema(), query);

				final Optional<EntityClassifier> entity = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
					executionContext.session().queryOne(query, EntityClassifier.class));
				requestExecutedEvent.finishOperationExecution();

				final Optional<Object> result = entity.map(it -> convertResultIntoSerializableObject(executionContext, it));
				requestExecutedEvent.finishResultSerialization();

				return result
					.map(it -> (EndpointResponse) new SuccessEndpointResponse(it))
					.orElse(new NotFoundEndpointResponse());
			}
		);
	}

	@Nullable
	protected Head buildHead(@Nonnull RestEndpointExecutionContext executionContext) {
		final List<HeadConstraint> headConstraints = new LinkedList<>();
		headConstraints.add(collection(this.restHandlingContext.getEntityType()));
		headConstraints.add(label(Label.LABEL_SOURCE_TYPE, RestQueryLabels.REST_SOURCE_TYPE_VALUE));

		executionContext.trafficSourceQueryRecordingId()
			.ifPresent(uuid -> headConstraints.add(label(Label.LABEL_SOURCE_QUERY, uuid)));

		return head(headConstraints.toArray(HeadConstraint[]::new));
	}

	@Nonnull
	@Override
	public Set<HttpMethod> getSupportedHttpMethods() {
		return Set.of(HttpMethod.GET);
	}
}
