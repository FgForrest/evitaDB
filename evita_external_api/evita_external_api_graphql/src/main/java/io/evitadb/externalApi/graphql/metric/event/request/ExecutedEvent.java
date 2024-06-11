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

import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportDurationMetric;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetric;
import io.evitadb.api.observability.annotation.HistogramSettings;
import io.evitadb.externalApi.graphql.io.GraphQLInstanceType;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;
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
public class ExecutedEvent extends AbstractGraphQLRequestEvent<ExecutedEvent> {

	/**
	 * Process started timestamp.
	 */
	private final long processStarted;

	@Label("Input deserialization duration in milliseconds")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long inputDeserializationDurationMilliseconds;

	private long preparationStarted;
	@Label("Request execution preparation duration in milliseconds")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long preparationDurationMilliseconds;

	private long parseStarted;
	@Label("Request parsing duration in milliseconds")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long parseDurationMilliseconds;

	private long validationStarted;
	@Label("Request validation duration in milliseconds")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long validationDurationMilliseconds;

	private long operationExecutionStarted;
	@Label("Request operation execution duration in milliseconds")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long operationExecutionDurationMilliseconds;

	@Label("Duration of all internal evitaDB input (query, mutations, ...) reconstructions in milliseconds")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long internalEvitadbInputReconstructionDurationMilliseconds;

	/**
	 * Duration of all internal evitaDB executions in milliseconds.
	 */
	private long internalEvitadbExecutionDurationMilliseconds;

	private long resultSerializationStarted;
	@Label("Request result serialization duration in milliseconds")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long resultSerializationDurationMilliseconds;

	/**
	 * Overall request execution duration in milliseconds for calculating API overhead.
	 */
	private long executionDurationMilliseconds;

	@Label("Overall request execution API overhead duration in milliseconds")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long executionApiOverheadDurationMilliseconds;

	@Label("Number of root fields (queries, mutations) processed within single GraphQL request")
	@ExportMetric(metricType = MetricType.GAUGE)
	private int rootFieldsProcessed;

	public ExecutedEvent(@Nonnull GraphQLInstanceType instanceType) {
		super(instanceType);
		this.begin();
		this.processStarted = System.currentTimeMillis();
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
	public ExecutedEvent finishRequestDeserialization() {
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
		this.executionDurationMilliseconds = System.currentTimeMillis() - this.processStarted;
		this.executionApiOverheadDurationMilliseconds = this.executionDurationMilliseconds - this.internalEvitadbExecutionDurationMilliseconds;
		return this;
	}
}
