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

package io.evitadb.externalApi.api.catalog.dataApi.model.extraResult;

import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullRef;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableRef;

/**
 * Represents {@link EvitaResponse#getExtraResults()}.
 *
 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
 * descriptor are supposed to be dynamically registered to target generated DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ExtraResultsDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("*ExtraResults")
		.description("""
			Map of requested extra results besides actual found records.
			""")
		.build();

	PropertyDescriptor ATTRIBUTE_HISTOGRAM = PropertyDescriptor.builder()
		.name("attributeHistogram")
		.description("""
			Returns computed histograms for specific attributes based on filter constraints.
			            
			A histogram is an approximate representation of the distribution of numerical data. For detailed description please
			see [WikiPedia](https://en.wikipedia.org/wiki/Histogram).
			Histogram can be computed only for numeric based properties. It visualises which property values are more common
			in the returned data set and which are rare. Bucket count will never exceed requested bucket count specified in
			`requestedCount` but there
			may be less of them if there is no enough data for computation. Bucket thresholds are specified heuristically so that
			there are as few "empty buckets" as possible.
			            
			- buckets are defined by their lower bounds (inclusive)
			- the upper bound is the lower bound of the next bucket
			""")
		// type is expected to be a map with attribute names as keys and `Histogram` objects as values
		.build();
	PropertyDescriptor PRICE_HISTOGRAM = PropertyDescriptor.builder()
		.name("priceHistogram")
		.description("""
			Returns computed histogram for prices satisfactory to filter constraints.
			            
			A histogram is an approximate representation of the distribution of numerical data. For detailed description please
			see [WikiPedia](https://en.wikipedia.org/wiki/Histogram).
			Histogram can be computed only for numeric based properties. It visualises which property values are more common
			in the returned data set and which are rare. Bucket count will never exceed requested bucket count specified in
			`requestedCount` but there
			may be less of them if there is no enough data for computation. Bucket thresholds are specified heuristically so that
			there are as few "empty buckets" as possible.
			            
			- buckets are defined by their lower bounds (inclusive)
			- the upper bound is the lower bound of the next bucket
			""")
		.type(nullableRef(HistogramDescriptor.THIS))
		.build();
	PropertyDescriptor FACET_SUMMARY = PropertyDescriptor.builder()
		.name("facetSummary")
		.description("""
			Returns summary of all facets that match query filter excluding those inside `userFilter`.
			Object contains information about facet groups and individual facets in them as well as appropriate statistics for them.
			""")
		// type is expected to be a collection of `FacetGroupStatistics` objects
		.build();
	PropertyDescriptor HIERARCHY = PropertyDescriptor.builder()
		.name("hierarchy")
		.description("""
			Returns object containing hierarchical structure of entities referenced by the entities required by the query. It copies
			hierarchical structure of those entities and contains their identification or full body as well as information on
			cardinality of referencing entities.
			""")
		// type is expected to be a map with reference names as keys and list of `LevelInfo` objects as values
		.build();
	PropertyDescriptor QUERY_TELEMETRY = PropertyDescriptor.builder()
		.name("queryTelemetry")
		.description("""
			Returns object containing detailed information about query processing time and its decomposition to single operations.
			""")
		.type(nonNullRef(QueryTelemetryDescriptor.THIS))
		.build();

}
