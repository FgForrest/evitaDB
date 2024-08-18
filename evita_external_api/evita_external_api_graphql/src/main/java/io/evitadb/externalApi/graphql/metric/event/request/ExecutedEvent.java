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
import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportDurationMetric;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import io.evitadb.api.observability.annotation.HistogramSettings;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.io.GraphQLInstanceType;
import io.evitadb.utils.Assert;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * JFR Event fired when GQL request is full executed and its response sent to client.
 *
 * @author Lukáš Hornych, 2024
 */
@Name(AbstractGraphQLRequestEvent.PACKAGE_NAME + ".Executed")
@Description("Event that is fired when a GraphQL request is executed.")
@ExportInvocationMetric(label = "GraphQL request executed total")
@ExportDurationMetric(label = "GraphQL request execution duration")
@Label("GraphQL request executed")
@Getter
public class ExecutedEvent extends AbstractGraphQLRequestEvent {

	/**
	 * Operation type specified by user in GQL request.
	 */
	@Label("GraphQL operation type")
	@Description("The type of operation specified in the GQL request: QUERY, MUTATION, or SUBSCRIPTION.")
	@ExportMetricLabel
	@Nullable
	String graphQLOperationType;

	/**
	 * The name of the catalog the transaction relates to.
	 */
	@Label("Catalog")
	@Name("catalogName")
	@Description("The name of the catalog to which this event/metric is associated.")
	@ExportMetricLabel
	@Nullable
	String catalogName;

	/**
	 * Operation name specified by user in GQL request.
	 */
	@Label("GraphQL operation")
	@Description("The name of the operation specified in the GQL request.")
	@ExportMetricLabel
	@Nullable
	String operationName;

	/**
	 * Response status of the request.
	 */
	@Label("Response status")
	@Description("The status of the response: OK or ERROR.")
	@Name("responseStatus")
	@ExportMetricLabel
	@Nonnull
	String responseStatus = ResponseStatus.OK.name();

	/**
	 * Duration of input deserialization in milliseconds.
	 */
	@Label("Input deserialization duration")
	@Description("Time to deserialize the input GQL request in milliseconds.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long inputDeserializationDurationMilliseconds;
	private final long processStarted;

	/**
	 * Duration of request preparation in milliseconds.
	 */
	@Label("Request preparation duration")
	@Description("Time to prepare the request execution in milliseconds.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long preparationDurationMilliseconds;
	private long preparationStarted;

	/**
	 * Duration of request parsing in milliseconds.
	 */
	@Label("Request parsing duration")
	@Description("Time to parse the request in milliseconds.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long parseDurationMilliseconds;
	private long parseStarted;

	/**
	 * Duration of request validation in milliseconds.
	 */
	@Label("Validation duration")
	@Description("Time to validate the request in milliseconds.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long validationDurationMilliseconds;
	private long validationStarted;

	/**
	 * Duration of operation execution in milliseconds.
	 */
	@Label("Execution duration")
	@Description("Time to execute the operation in milliseconds.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long operationExecutionDurationMilliseconds;
	private long operationExecutionStarted;

	@Label("evitaDB input reconstruction duration")
	@Description("Time to reconstruct all internal evitaDB inputs in milliseconds.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long internalEvitadbInputReconstructionDurationMilliseconds;


	/**
	 * Duration of all internal evitaDB executions in milliseconds.
	 */
	private long internalEvitadbExecutionDurationMilliseconds;

	/**
	 * Duration of result serialization in milliseconds.
	 */
	@Label("Result serializatio duration")
	@Description("Time to serialize the request result in milliseconds.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long resultSerializationDurationMilliseconds;
	private long resultSerializationStarted;

	/**
	 * Overall request execution duration in milliseconds for calculating API overhead.
	 */
	private long executionDurationMilliseconds;

	/**
	 * Request execution overhead in milliseconds.
	 */
	@Label("Request execution overhead")
	@Description("Time to execute the request in milliseconds without internal evitaDB execution.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long executionApiOverheadDurationMilliseconds;

	/**
	 * Number of root fields (queries, mutations) processed within a single GraphQL request.
	 */
	@Label("Request root fields count")
	@Description("Number of root fields (queries, mutations) processed within a single GraphQL request.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private int rootFieldsProcessed;

	public ExecutedEvent(@Nonnull GraphQLInstanceType instanceType) {
		super(instanceType);
		this.begin();
		this.processStarted = System.currentTimeMillis();
	}

	/**
	 * Provide operation type for this event. Can be called only once.
	 * @return this
	 */
	@Nonnull
	public ExecutedEvent provideOperationType(@Nonnull Operation operationType) {
		Assert.isPremiseValid(
			this.graphQLOperationType == null,
			() -> new GraphQLInternalError("Operation type is already set.")
		);
		this.graphQLOperationType = operationType.toString();
		return this;
	}

	/**
	 * Provide catalog name for this event. Can be called only once.
	 * @return this
	 */
	@Nonnull
	public ExecutedEvent provideCatalogName(@Nonnull String catalogName) {
		Assert.isPremiseValid(
			this.catalogName == null,
			() -> new GraphQLInternalError("Catalog name is already set.")
		);
		this.catalogName = catalogName;
		return this;
	}

	/**
	 * Provide operation name for this event. Can be called only once.
	 * @return this
	 */
	@Nonnull
	public ExecutedEvent provideOperationName(@Nonnull String operationName) {
		this.operationName = operationName;
		return this;
	}

	/**
	 * Provide response status for this event. Can be called only once. Default is {@link ResponseStatus#OK}
	 * @return this
	 */
	@Nonnull
	public ExecutedEvent provideResponseStatus(@Nonnull ResponseStatus responseStatus) {
		this.responseStatus = responseStatus.toString();
		return this;
	}

	@Nonnull
	public ExecutedEvent provideRootFieldsProcessed(int rootFieldsProcessed) {
		this.rootFieldsProcessed = rootFieldsProcessed;
		return this;
	}

	/**
	 * Measures duration of request deserialization from previous state. Should be called only once.
	 * @return this
	 */
	@Nonnull
	public ExecutedEvent finishInputDeserialization() {
		Assert.isPremiseValid(
			this.processStarted != 0,
			() -> new GraphQLInternalError("Process didn't started. Cannot measure input deserialization duration.")
		);
		final long now = System.currentTimeMillis();
		this.inputDeserializationDurationMilliseconds = now - this.processStarted;
		this.preparationStarted = now;
		return this;
	}

	/**
	 * Measures duration of preparation from previous state. Should be called only once.
	 * @return this
	 */
	@Nonnull
	public ExecutedEvent finishPreparation() {
		Assert.isPremiseValid(
			this.preparationStarted != 0,
			() -> new GraphQLInternalError("Preparation didn't started. Cannot measure preparation duration.")
		);
		final long now = System.currentTimeMillis();
		this.preparationDurationMilliseconds = now - this.preparationStarted;
		this.parseStarted = now;
		return this;
	}

	/**
	 * Measures duration of parsing from previous state. Should be called only once.
	 * @return this
	 */
	@Nonnull
	public ExecutedEvent finishParse() {
		Assert.isPremiseValid(
			this.parseStarted != 0,
			() -> new GraphQLInternalError("Parse didn't started. Cannot measure parse duration.")
		);
		final long now = System.currentTimeMillis();
		this.parseDurationMilliseconds = now - this.parseStarted;
		this.validationStarted = now;
		return this;
	}

	/**
	 * Measures duration of validation deserialization from previous state. Should be called only once.
	 * @return this
	 */
	@Nonnull
	public ExecutedEvent finishValidation() {
		Assert.isPremiseValid(
			this.validationStarted != 0,
			() -> new GraphQLInternalError("Validation didn't started. Cannot measure validation duration.")
		);
		final long now = System.currentTimeMillis();
		this.validationDurationMilliseconds = now - this.validationStarted;
		this.operationExecutionStarted = now;
		return this;
	}

	/**
	 * Measures duration of evitaDB input reconstruction within the supplier. Can be called mutliple times, all durations
	 * are summed.
	 * @return this
	 */
	public <T> T measureInternalEvitaDBInputReconstruction(@Nonnull Supplier<T> supplier) {
		final long started = System.currentTimeMillis();
		final T result = supplier.get();
		this.internalEvitadbInputReconstructionDurationMilliseconds += System.currentTimeMillis() - started;
		return result;
	}

	/**
	 * Measures duration of evitaDB execution within the supplier. Can be called mutliple times, all durations
	 * are summed.
	 * @return this
	 */
	public <T> T measureInternalEvitaDBExecution(@Nonnull Supplier<T> supplier) {
		final long started = System.currentTimeMillis();
		final T result = supplier.get();
		this.internalEvitadbExecutionDurationMilliseconds += System.currentTimeMillis() - started;
		return result;
	}

	/**
	 * Measures duration of operation execution from previous state. Should be called only once.
	 * @return this
	 */
	@Nonnull
	public ExecutedEvent finishOperationExecution() {
		final long now = System.currentTimeMillis();
		this.operationExecutionDurationMilliseconds = now - this.operationExecutionStarted;
		this.resultSerializationStarted = now;
		return this;
	}

	/**
	 * Measures duration of result serialization from previous state. Should be called only once.
	 * If not called, it is assumed that no result serialization was done and thus it took 0 seconds.
	 * @return this
	 */
	@Nonnull
	public ExecutedEvent finishResultSerialization() {
		this.resultSerializationDurationMilliseconds = System.currentTimeMillis() - this.resultSerializationStarted;
		return this;
	}

	/**
	 * Finish the event.
	 * @return this
	 */
	@Nonnull
	public ExecutedEvent finish() {
		this.end();

		Assert.isPremiseValid(
			this.processStarted != 0,
			() -> new GraphQLInternalError("Process didn't started. Cannot measure execution duration duration.")
		);
		if (this.operationExecutionStarted > 0 && this.operationExecutionDurationMilliseconds == 0) {
			finishOperationExecution();
		}
		this.executionDurationMilliseconds = System.currentTimeMillis() - this.processStarted;
		this.executionApiOverheadDurationMilliseconds = this.executionDurationMilliseconds - this.internalEvitadbExecutionDurationMilliseconds;

		return this;
	}

	/**
	 * Response status of GraphQL request
	 */
	public enum ResponseStatus {
		OK, ERROR
	}
}
