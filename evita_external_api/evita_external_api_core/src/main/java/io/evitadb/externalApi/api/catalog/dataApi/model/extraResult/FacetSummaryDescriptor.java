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

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Represents {@link io.evitadb.api.requestResponse.extraResult.FacetSummary}.
 *
 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
 * descriptor are supposed to be dynamically registered to target generated DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface FacetSummaryDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("*FacetSummary")
		.description("""
			This DTO allows returning summary of all facets that match query filter excluding those inside `userFilter`.
			DTO contains information about facet groups and individual facets in them as well as appropriate statistics for them.
			""")
		.build();

	/**
	 * Represents {@link io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics}.
	 *
	 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
	 * descriptor are supposed to be dynamically registered to target generated DTO.
	 */
	interface FacetGroupStatisticsDescriptor {

		PropertyDescriptor GROUP_ENTITY = PropertyDescriptor.builder()
			.name("groupEntity")
			.description("""
				Contains referenced entity representing this group.
				""")
			// type is expected to be an entity object of target entity type
			.build();
		PropertyDescriptor COUNT = PropertyDescriptor.builder()
			.name("count")
			.description("""
				Contains number of distinct entities in the response that possess any reference in this group.
				""")
			.type(nonNull(Integer.class))
			.build();
		PropertyDescriptor FACET_STATISTICS = PropertyDescriptor.builder()
			.name("facetStatistics")
			.description("""
				Contains statistics of individual facets.
				""")
			// type is expected to be a `FacetStatistics` object
			.build();

		ObjectDescriptor THIS = ObjectDescriptor.builder()
			.name("*FacetGroupStatistics")
			.description("""
				This DTO contains information about single facet group and statistics of the facets that relates to it.
				""")
			.staticProperties(List.of(COUNT))
			.build();
	}

	/**
	 * Represents {@link io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetStatistics}.
	 *
	 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
	 * descriptor are supposed to be dynamically registered to target generated DTO.
	 */
	interface FacetStatisticsDescriptor {

		PropertyDescriptor FACET_ENTITY = PropertyDescriptor.builder()
			.name("facetEntity")
			.description("""
				Contains referenced entity representing.
				""")
			// type is expected to be entity object
			.build();
		PropertyDescriptor REQUESTED = PropertyDescriptor.builder()
			.name("requested")
			.description("""
				Contains TRUE if the facet was part of the query filtering constraints.
				""")
			.type(nonNull(Boolean.class))
			.build();
		PropertyDescriptor COUNT = PropertyDescriptor.builder()
			.name("count")
			.description("""
				Contains number of distinct entities in the response that possess of this reference.
				""")
			.type(nonNull(Integer.class))
			.build();
		PropertyDescriptor IMPACT = PropertyDescriptor.builder()
			.name("impact")
			.description("""
				This field is not null only when this facet is not requested.
				Contains projected impact on the current response if this facet is also requested in filtering constraints.
				""")
			.type(nullableRef(FacetRequestImpactDescriptor.THIS))
			.build();

		ObjectDescriptor THIS = ObjectDescriptor.builder()
			.name("*FacetStatistics")
			.description("""
				This DTO contains information about single facet statistics of the entities that are present in the response.
				""")
			.staticProperties(List.of(REQUESTED, COUNT, IMPACT))
			.build();
	}

	/**
	 * Represents {@link io.evitadb.api.requestResponse.extraResult.FacetSummary.RequestImpact}.
	 *
	 * Note: this descriptor has static structure.
	 */
	interface FacetRequestImpactDescriptor {

		PropertyDescriptor DIFFERENCE = PropertyDescriptor.builder()
			.name("difference")
			.description("""
				Projected number of entities that are added or removed from result if the query is altered by adding this
				facet to filtering constraint in comparison to current result.
				""")
			.type(nonNull(Integer.class))
			.build();
		PropertyDescriptor MATCH_COUNT = PropertyDescriptor.builder()
			.name("matchCount")
			.description("""
				Projected number of filtered entities if the query is altered by adding this facet to filtering constraint.
				""")
			.type(nonNull(Integer.class))
			.build();
		PropertyDescriptor HAS_SENSE = PropertyDescriptor.builder()
			.name("hasSense")
			.description("""
				Selection has sense - TRUE if there is at least one entity still present in the result if the query is
				altered by adding this facet to filtering constraint.
				""")
			.type(nonNull(Boolean.class))
			.build();


		ObjectDescriptor THIS = ObjectDescriptor.builder()
			.name("FacetRequestImpact")
			.description("""
				 This DTO contains information about the impact of adding respective facet into the filtering constraint. This
				 would lead to expanding or shrinking the result response in certain way, that is described in this DTO.
				 This implementation contains only the bare difference and the match count.
				""")
			.staticProperties(List.of(DIFFERENCE, MATCH_COUNT, HAS_SENSE))
			.build();
	}
}
