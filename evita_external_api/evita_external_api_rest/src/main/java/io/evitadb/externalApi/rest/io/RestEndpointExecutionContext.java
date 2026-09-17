/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.externalApi.rest.io;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.query.require.SeparateEntityContentRequireContainer;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.event.ResponseStatus;
import io.evitadb.externalApi.http.EndpointExecutionContext;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of {@link EndpointExecutionContext} for REST API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class RestEndpointExecutionContext extends EndpointExecutionContext {

	@Nonnull private final ExecutedEvent requestExecutedEvent;

	@Nullable private EvitaSessionContract session;
	@Nullable private UUID trafficSourceQueryRecordingId;
	@Nullable private EntityFetchRequire entityRequirement;

	@Nullable private String requestBodyContentType;
	@Nullable private String preferredResponseContentType;

	public RestEndpointExecutionContext(
		@Nonnull HttpRequest httpRequest,
		@Nonnull Evita evita,
		@Nonnull ServiceRequestContext serviceRequestContext,
		@Nonnull ExecutedEvent requestExecutedEvent
	) {
		super(httpRequest, evita, serviceRequestContext);
		this.requestExecutedEvent = requestExecutedEvent;
	}

	@Nonnull
	public ExecutedEvent requestExecutedEvent() {
		return this.requestExecutedEvent;
	}

	@Nonnull
	public EvitaSessionContract session() {
		Assert.isPremiseValid(
			this.session != null,
			() -> new RestInternalError("Session is not available for this exchange.")
		);
		Assert.isPremiseValid(
			this.session.isActive(),
			() -> new RestInternalError("Session has been already closed. No one should access the session!")
		);
		return this.session;
	}

	/**
	 * Sets a session for this exchange. Can be set only once to avoid overwriting errors.
	 */
	public void provideSession(@Nonnull EvitaSessionContract session) {
		Assert.isPremiseValid(
			this.session == null,
			() -> new RestInternalError("Session cannot be overwritten when already set.")
		);
		this.session = session;
	}

	/**
	 * Returns source query recording ID for traffic tracking. If traffic tracking is disabled, ID is empty.
	 */
	@Nonnull
	public Optional<UUID> trafficSourceQueryRecordingId() {
		return Optional.ofNullable(this.trafficSourceQueryRecordingId);
	}

	/**
	 * Sets a source query recording ID for traffic tracking. Can be set only once to avoid overwriting errors.
	 */
	public void provideTrafficSourceQueryRecordingId(@Nullable UUID trafficSourceQueryRecordingId) {
		Assert.isPremiseValid(
			this.trafficSourceQueryRecordingId == null,
			() -> new RestInternalError("TrafficSourceQueryRecordingId cannot be overwritten when already set.")
		);
		this.trafficSourceQueryRecordingId = trafficSourceQueryRecordingId;
	}

	/**
	 * Returns the requirement the entities returned by this request were fetched with, or NULL when the endpoint
	 * resolved none - it may have been given none, or it may not work through a {@link Query} at all. The entity
	 * serializer needs it to tell an ancestor whose body was asked for and could not be materialized from one whose
	 * body was never requested; the two arrive as the same kind of object and are indistinguishable in the data.
	 *
	 * @return the requirement the returned entities were fetched with, or NULL when the endpoint resolved none
	 */
	@Nullable
	public EntityFetchRequire entityRequirement() {
		return this.entityRequirement;
	}

	/**
	 * Records the requirement the entities of this request are fetched with. Can be set only once to avoid
	 * overwriting errors; an endpoint that resolves no requirement simply never calls it.
	 *
	 * @param entityRequirement the requirement the entities of this request are fetched with
	 */
	public void provideEntityRequirement(@Nonnull EntityFetchRequire entityRequirement) {
		Assert.isPremiseValid(
			this.entityRequirement == null,
			() -> new RestInternalError("Entity requirement cannot be overwritten when already set.")
		);
		this.entityRequirement = entityRequirement;
	}

	/**
	 * Records the requirement the entities of this request are fetched with, read off the query that fetches them.
	 * A query fetching no entity bodies at all resolves no requirement and the call is a no-op.
	 *
	 * @param query the query the entities of this request are fetched by
	 */
	public void provideEntityRequirement(@Nonnull Query query) {
		final EntityFetch entityFetch = QueryUtils.findRequire(
			query, EntityFetch.class, SeparateEntityContentRequireContainer.class
		);
		if (entityFetch != null) {
			provideEntityRequirement(entityFetch);
		}
	}

	/**
	 * Closes the current session (and transaction) if it is open.
	 */
	public void closeSessionIfOpen() {
		if (this.session != null) {
			this.session.close();
		}
	}

	@Override
	public void provideRequestBodyContentType(@Nonnull String contentType) {
		Assert.isPremiseValid(
			this.requestBodyContentType == null,
			() -> new RestInternalError("Request body content type already provided.")
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
			() -> new RestInternalError("Preferred response content type already provided.")
		);
		this.preferredResponseContentType = contentType;
	}

	@Nullable
	@Override
	public String preferredResponseContentType() {
		return this.preferredResponseContentType;
	}

	@Override
	public void notifyError(@Nonnull Exception e) {
		this.requestExecutedEvent.provideResponseStatus(ResponseStatus.ERROR);
	}

	@Override
	public void close() {
		super.close();

		// the session may not be properly closed in case of exception during request handling
		closeSessionIfOpen();

		this.requestExecutedEvent.finish().commit();
	}
}
