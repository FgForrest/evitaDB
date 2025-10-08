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

package io.evitadb.externalApi.rest.metric.event.instance;

import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import io.evitadb.api.observability.annotation.HistogramSettings;
import io.evitadb.externalApi.rest.io.RestInstanceType;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Event fired when REST API instance was successfully built.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@Name(AbstractRestInstanceEvent.PACKAGE_NAME + ".Built")
@Description("Event that is fired when a REST API instance is built.")
@ExportInvocationMetric(label = "REST API instance built total")
@Label("REST API instance built")
@Getter
public class BuiltEvent extends AbstractRestInstanceEvent {

	/**
	 * Type of used REST instance.
	 */
	@Label("REST instance type")
	@Description("Domain of the REST API used in connection with this event/metric: SYSTEM, or CATALOG")
	@ExportMetricLabel
	@Nonnull
	private final String restInstanceType;

	/**
	 * Type of the build.
	 */
	@Label("Build type")
	@Description("Type of the instance build: NEW or REFRESH")
	@ExportMetricLabel
	@Nonnull
	private final String buildType;

	/**
	 * The catalog name the build event is associated with.
	 */
	@Label("Catalog")
	@Description("The name of the catalog to which this event/metric is associated.")
	@ExportMetricLabel
	@Nullable
	private final String catalogName;

	@Label("API build duration")
	@Description("Duration of build of a single API in milliseconds.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 2.5)
	private final long restInstanceBuildDurationMilliseconds;

	@Label("REST schema build duration")
	@Description("Duration of build of a single REST API schema in milliseconds.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 2.5)
	private final long restSchemaBuildDurationMilliseconds;

	@Label("Number of lines")
	@Description("Number of lines generated in the built REST schema DSL.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long restSchemaDslLines;

	@Label("Endpoints count")
	@Description("Number of registered endpoints in built OpenAPI schema")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long registeredRestEndpoints;

	public BuiltEvent(
		@Nonnull RestInstanceType restInstanceType,
		@Nonnull BuildType buildType,
		long restInstanceBuildDurationMilliseconds,
		long restSchemaBuildDurationMilliseconds,
		long restSchemaDslLines,
		long registeredRestEndpoints
	) {
		this(
			null,
			restInstanceType,
			buildType,
			restInstanceBuildDurationMilliseconds,
			restSchemaBuildDurationMilliseconds,
			restSchemaDslLines,
			registeredRestEndpoints
		);
	}

	public BuiltEvent(
		@Nullable String catalogName,
		@Nonnull RestInstanceType restInstanceType,
		@Nonnull BuildType buildType,
		long restInstanceBuildDurationMilliseconds,
		long restSchemaBuildDurationMilliseconds,
		long restSchemaDslLines,
		long registeredRestEndpoints
	) {
		this.catalogName = catalogName;
		this.restInstanceType = restInstanceType.name();
		this.buildType = buildType.name();
		this.restInstanceBuildDurationMilliseconds = restInstanceBuildDurationMilliseconds;
		this.restSchemaBuildDurationMilliseconds = restSchemaBuildDurationMilliseconds;
		this.restSchemaDslLines = restSchemaDslLines;
		this.registeredRestEndpoints = registeredRestEndpoints;
	}

	/**
	 * Defines how the schema is being built.
	 */
	public enum BuildType {
		/**
		 * Building new schema for new catalog
		 */
		NEW,
		/**
		 * Rebuilding existing schema for already existing catalog
		 */
		REFRESH
	}
}
