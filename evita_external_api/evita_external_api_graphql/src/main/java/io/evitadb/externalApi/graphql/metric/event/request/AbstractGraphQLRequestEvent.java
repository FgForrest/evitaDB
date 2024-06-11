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

package io.evitadb.externalApi.graphql.metric.event.request;

import graphql.language.OperationDefinition.Operation;
import io.evitadb.api.observability.annotation.EventGroup;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.io.GraphQLInstanceType;
import io.evitadb.utils.Assert;
import jdk.jfr.Category;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Ancestor for GraphQL request processing events.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@EventGroup(AbstractGraphQLRequestEvent.PACKAGE_NAME)
@Category({"evitaDB", "API", "GraphQL", "Request"})
@Getter
public class AbstractGraphQLRequestEvent<E extends AbstractGraphQLRequestEvent<E>> extends CustomMetricsExecutionEvent {

	protected static final String PACKAGE_NAME = "io.evitadb.externalApi.graphql.request";

	/**
	 * Type of used GQL instance.
	 */
	@Label("Instance type")
	@Name("instanceType")
	@ExportMetricLabel
	@Nullable
	final String instanceType;

	/**
	 * Operation type specified by user in GQL request.
	 */
	@Label("Operation type")
	@Name("operationType")
	@ExportMetricLabel
	@Nullable
	String operationType;

	/**
	 * The name of the catalog the transaction relates to.
	 */
	@Label("Catalog")
	@Name("catalogName")
	@ExportMetricLabel
	@Nullable
	String catalogName;

	/**
	 * Operation name specified by user in GQL request.
	 */
	@Label("GraphQL operation")
	@Name("operationName")
	@ExportMetricLabel
	@Nullable
	String operationName;

	@Label("Response status")
	@Name("responseStatus")
	@ExportMetricLabel
	@Nonnull
	String responseStatus = ResponseStatus.OK.name();

	protected AbstractGraphQLRequestEvent(@Nullable GraphQLInstanceType instanceType) {
		this.instanceType = instanceType.toString();
	}

	/**
	 * Provide operation type for this event. Can be called only once.
	 * @return this
	 */
	@Nonnull
	public E provideOperationType(@Nonnull Operation operationType) {
		Assert.isPremiseValid(
			this.operationType == null,
			() -> new GraphQLInternalError("Operation type is already set.")
		);
		this.operationType = operationType.toString();
		//noinspection unchecked
		return (E) this;
	}

	/**
	 * Provide catalog name for this event. Can be called only once.
	 * @return this
	 */
	@Nonnull
	public E provideCatalogName(@Nonnull String catalogName) {
		Assert.isPremiseValid(
			this.catalogName == null,
			() -> new GraphQLInternalError("Catalog name is already set.")
		);
		this.catalogName = catalogName;
		//noinspection unchecked
		return (E) this;
	}

	/**
	 * Provide operation name for this event. Can be called only once.
	 * @return this
	 */
	@Nonnull
	public E provideOperationName(@Nonnull String operationName) {
		this.operationName = operationName;
		//noinspection unchecked
		return (E) this;
	}

	/**
	 * Provide response status for this event. Can be called only once. Default is {@link ResponseStatus#OK}
	 * @return this
	 */
	@Nonnull
	public E provideResponseStatus(@Nonnull ResponseStatus responseStatus) {
		this.responseStatus = responseStatus.toString();
		//noinspection unchecked
		return (E) this;
	}

	/**
	 * Response status of GraphQL request
	 */
	public enum ResponseStatus {
		OK, ERROR
	}
}
