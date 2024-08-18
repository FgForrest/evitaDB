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

package io.evitadb.externalApi.rest.metric.event.request;

import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportDurationMetric;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import io.evitadb.api.observability.annotation.HistogramSettings;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.RestInstanceType;
import io.evitadb.utils.Assert;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * JFR Event that is fired when a REST request is executed.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@Name(AbstractRestRequestEvent.PACKAGE_NAME + ".Executed")
@Description("Event that is fired when a REST request is executed.")
@ExportInvocationMetric(label = "REST request executed total")
@ExportDurationMetric(label = "REST request execution duration")
@Label("REST request executed")
@Getter
public class ExecutedEvent extends AbstractRestRequestEvent {

	/**
	 * Operation type specified by user in REST request.
	 */
	@Label("REST operation type")
	@Description("The type of operation that was executed. One of: QUERY, MUTATION.")
	@ExportMetricLabel
	@Nullable
	final String restOperationType;

	/**
	 * The name of the catalog the transaction relates to.
	 */
	@Label("Catalog")
	@Description("The name of the catalog to which this event/metric is associated.")
	@ExportMetricLabel
	@Nullable
	final String catalogName;

	/**
	 * The name of the entity collection the transaction relates to.
	 */
	@Label("Entity type")
	@Description("The name of the related entity type (collection).")
	@ExportMetricLabel
	@Nullable
	final String entityType;

	/**
	 * HTTP method of the request.
	 */
	@Label("HTTP method")
	@Description("The HTTP method of the request.")
	@ExportMetricLabel
	@Nonnull
	final String httpMethod;

	/**
	 * Operation ID specified by user in GQL request.
	 */
	@Label("Operation ID")
	@Description("The ID of the operation that was executed.")
	@ExportMetricLabel
	@Nonnull
	final String operationId;

	/**
	 * Response status of the request.
	 */
	@Label("Response status")
	@Description("The status of the response: OK or ERROR.")
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
	 * Duration of operation execution in milliseconds.
	 */
	@Label("Execution duration")
	@Description("Time to execute the operation in milliseconds.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long operationExecutionDurationMilliseconds;
	private long operationExecutionStarted;

	/**
	 * Duration of all internal evitaDB input reconstructions in milliseconds.
	 */
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

	public ExecutedEvent(@Nonnull RestInstanceType instanceType,
						 @Nonnull OperationType restOperationType,
						 @Nullable String catalogName,
						 @Nullable String entityType,
	                     @Nonnull String httpMethod,
	                     @Nonnull String operationId) {
		super(instanceType);
		this.restOperationType = restOperationType.name();
		this.catalogName = catalogName;
		this.entityType = entityType;
		this.httpMethod = httpMethod;
		this.operationId = operationId;
		this.begin();
		this.processStarted = System.currentTimeMillis();
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


	/**
	 * Measures duration of request deserialization from previous state. Should be called only once.
	 * @return this
	 */
	@Nonnull
	public ExecutedEvent finishInputDeserialization() {
		Assert.isPremiseValid(
			this.processStarted != 0,
			() -> new RestInternalError("Process didn't started. Cannot measure input deserialization duration.")
		);
		final long now = System.currentTimeMillis();
		this.inputDeserializationDurationMilliseconds = now - this.processStarted;
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
		Assert.isPremiseValid(
			this.operationExecutionStarted != 0,
			() -> new RestInternalError("Operation execution didn't started. Cannot measure operation execution duration.")
		);
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
		Assert.isPremiseValid(
			this.resultSerializationStarted != 0,
			() -> new RestInternalError("Result serialization didn't started. Cannot measure result serialization duration.")
		);
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
			() -> new RestInternalError("Process didn't started. Cannot measure execution duration.")
		);
		if (this.operationExecutionStarted > 0 && this.operationExecutionDurationMilliseconds == 0) {
			finishOperationExecution();
		}
		this.executionDurationMilliseconds = System.currentTimeMillis() - this.processStarted;
		this.executionApiOverheadDurationMilliseconds = this.executionDurationMilliseconds - this.internalEvitadbExecutionDurationMilliseconds;

		return this;
	}

	/**
	 * Defines what will be done with the manipulated data within request.
	 */
	public enum OperationType {
		QUERY, MUTATION
	}

	/**
	 * Response status of REST request
	 */
	public enum ResponseStatus {
		OK, ERROR
	}
}
