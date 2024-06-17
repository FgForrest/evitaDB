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

	@Label("Instance type")
	@Name("instanceType")
	@ExportMetricLabel
	@Nonnull
	private String instanceType;

	@Label("Build type")
	@Name("buildType")
	@ExportMetricLabel
	@Nonnull
	private String buildType;

	@Label("Catalog")
	@Name("catalogName")
	@ExportMetricLabel
	@Nullable
	private final String catalogName;

	@Label("Duration of build of a single REST API")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 2.5)
	private long instanceBuildDuration;

	@Label("Duration of OpenAPI schema build of a single API")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 2.5)
	private long schemaBuildDuration;

	@Label("Number of lines in built OpenAPI schema DSL")
	@ExportMetric(metricType = MetricType.GAUGE)
	private long schemaDslLines;

	@Label("Number of registered endpoints in built OpenAPI schema")
	@ExportMetric(metricType = MetricType.GAUGE)
	private long registeredEndpoints;

	public BuiltEvent(@Nonnull RestInstanceType instanceType,
	                  @Nonnull BuildType buildType,
					  long instanceBuildDuration,
	                  long schemaBuildDuration,
	                  long schemaDslLines,
	                  long registeredEndpoints) {
		this(
			null,
			instanceType,
			buildType,
			instanceBuildDuration,
			schemaBuildDuration,
			schemaDslLines,
			registeredEndpoints
		);
	}

	public BuiltEvent(@Nonnull String catalogName,
	                  @Nonnull RestInstanceType instanceType,
	                  @Nonnull BuildType  buildType,
					  long instanceBuildDuration,
	                  long schemaBuildDuration,
	                  long schemaDslLines,
	                  long registeredEndpoints) {
		this.catalogName = catalogName;
		this.instanceType = instanceType.name();
		this.buildType = buildType.name();
		this.instanceBuildDuration = instanceBuildDuration;
		this.schemaBuildDuration = schemaBuildDuration;
		this.schemaDslLines = schemaDslLines;
		this.registeredEndpoints = registeredEndpoints;
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
