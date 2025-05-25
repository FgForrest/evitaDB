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

package io.evitadb.externalApi.graphql.metric.event.instance;

import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import io.evitadb.api.observability.annotation.HistogramSettings;
import io.evitadb.externalApi.graphql.io.GraphQLInstanceType;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Event fired when GraphQL schema was successfully built.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@Name(AbstractGraphQLInstanceEvent.PACKAGE_NAME + ".Built")
@Description("Event that is fired when a GraphQL instance is built.")
@ExportInvocationMetric(label = "GraphQL instance built total")
@Label("GraphQL instance built")
@Getter
public class BuiltEvent extends AbstractGraphQLInstanceEvent {

	/**
	 * Type of used GQL instance.
	 */
	@Label("GraphQL instance type")
	@Description("Domain of the GraphQL API used in connection with this event/metric: SYSTEM, SCHEMA, or DATA")
	@ExportMetricLabel
	@Nonnull
	private final String graphQLInstanceType;

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
	private final long graphQLInstanceBuildDurationMilliseconds;

	@Label("GraphQL schema build duration")
	@Description("Duration of build of a single GraphQL API schema in milliseconds.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 2.5)
	private final long graphQLSchemaBuildDurationMilliseconds;

	@Label("Number of lines")
	@Description("Number of lines generated in the built GraphQL schema DSL.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long graphQLSchemaDslLines;

	public BuiltEvent(@Nonnull GraphQLInstanceType graphQLInstanceType,
	                  @Nonnull BuildType buildType,
	                  long graphQLInstanceBuildDurationMilliseconds,
	                  long graphQLSchemaBuildDurationMilliseconds,
	                  long graphQLSchemaDslLines) {
		this(
			null,
			graphQLInstanceType,
			buildType,
			graphQLInstanceBuildDurationMilliseconds,
			graphQLSchemaBuildDurationMilliseconds,
			graphQLSchemaDslLines
		);
	}

	public BuiltEvent(
		@Nullable String catalogName,
		@Nonnull GraphQLInstanceType graphQLInstanceType,
		@Nonnull BuildType buildType,
		long graphQLInstanceBuildDurationMilliseconds,
		long graphQLSchemaBuildDurationMilliseconds,
		long graphQLSchemaDslLines
	) {
		this.catalogName = catalogName;
		this.graphQLInstanceType = graphQLInstanceType.name();
		this.buildType = buildType.name();
		this.graphQLInstanceBuildDurationMilliseconds = graphQLInstanceBuildDurationMilliseconds;
		this.graphQLSchemaBuildDurationMilliseconds = graphQLSchemaBuildDurationMilliseconds;
		this.graphQLSchemaDslLines = graphQLSchemaDslLines;
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
