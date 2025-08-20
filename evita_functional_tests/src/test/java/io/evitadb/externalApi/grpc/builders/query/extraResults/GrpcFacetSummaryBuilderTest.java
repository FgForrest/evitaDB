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

package io.evitadb.externalApi.grpc.builders.query.extraResults;

import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.generated.GrpcExtraResults;
import io.evitadb.externalApi.grpc.generated.GrpcExtraResults.Builder;
import io.evitadb.externalApi.grpc.testUtils.GrpcAssertions;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * This test verifies functionalities of methods in {@link GrpcFacetSummaryBuilder} class.
 *
 * @author Tomáš Pozler, 2022
 */
public class GrpcFacetSummaryBuilderTest {

	@Test
	void buildFacetSummary() {
		final ReferenceSchema[] types = {
			ReferenceSchema._internalBuild(
				"test1", "test1", false, Cardinality.ONE_OR_MORE, "testGroup1", false, new ScopedReferenceIndexType[] { new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING) }, new Scope[] { Scope.LIVE }
			),
			ReferenceSchema._internalBuild(
				"test2", "test2", false, Cardinality.ONE_OR_MORE, "testGroup2", false, new ScopedReferenceIndexType[] { new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING) }, new Scope[] { Scope.LIVE }
			),
			ReferenceSchema._internalBuild(
				"test3", "test3", false, Cardinality.ONE_OR_MORE, null, false, new ScopedReferenceIndexType[] { new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING) }, new Scope[] { Scope.LIVE }
			),
		};

		final FacetSummary facetSummary = new FacetSummary(
			List.of(
				new FacetGroupStatistics(
					types[0],
					new EntityReference(Objects.requireNonNull(types[0].getReferencedGroupType()), 1),
					15,
					List.of(
						new FacetStatistics(new EntityReference(types[0].getReferencedEntityType(), 1), true, 5, new RequestImpact(1, 7, true)),
						new FacetStatistics(new EntityReference(types[0].getReferencedEntityType(), 2), false, 4, new RequestImpact(5, 6, true)),
						new FacetStatistics(new EntityReference(types[0].getReferencedEntityType(), 3), false, 5, new RequestImpact(6, 6, true)),
						new FacetStatistics(new EntityReference(types[0].getReferencedEntityType(), 4), false, 1, new RequestImpact(4, 58, true))
					)
				),
				new FacetGroupStatistics(
					types[1],
					createGroupEntity("testGroup2"),
					15,
					List.of(
						new FacetStatistics(createFacetEntity(types[1].getReferencedEntityType(), 1, "phone1"), true, 5, new RequestImpact(55, 7, true)),
						new FacetStatistics(createFacetEntity(types[1].getReferencedEntityType(), 2, "phone2"), false, 4, new RequestImpact(7, 8, true)),
						new FacetStatistics(createFacetEntity(types[1].getReferencedEntityType(), 3, "phone3"), false, 5, new RequestImpact(6, 6, true)),
						new FacetStatistics(createFacetEntity(types[1].getReferencedEntityType(), 4, "phone4"), false, 1, new RequestImpact(7, 4, true))
					)
				),
				new FacetGroupStatistics(
					types[2],
					null,
					29,
					List.of(
						new FacetStatistics(new EntityReference(types[2].getReferencedEntityType(), 1), true, 8, new RequestImpact(1, 5, true)),
						new FacetStatistics(new EntityReference(types[2].getReferencedEntityType(), 2), false, 9, new RequestImpact(2, 66, true)),
						new FacetStatistics(new EntityReference(types[2].getReferencedEntityType(), 3), false, 7, new RequestImpact(3, 76, true)),
						new FacetStatistics(new EntityReference(types[2].getReferencedEntityType(), 4), false, 5, new RequestImpact(4, 8, true))
					)
				)
			)
		);

		final Builder extraResults = GrpcExtraResults.newBuilder();
		GrpcFacetSummaryBuilder.buildFacetSummary(extraResults, facetSummary, null);

		GrpcAssertions.assertFacetSummary(facetSummary, extraResults.getFacetGroupStatisticsList());
	}

	@Nonnull
	public static SealedEntity createGroupEntity(String groupEntityType) {
		return new InitialEntityBuilder(
			new InternalEntitySchemaBuilder(
				CatalogSchema._internalBuild(TestConstants.TEST_CATALOG, Map.of(), EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE),
				EntitySchema._internalBuild(groupEntityType)
			)
				.withAttribute("code", String.class)
				.withAttribute("name", String.class)
				.toInstance(),
			2
		)
			.setAttribute("code", "phone")
			.setAttribute("name", "Phone")
			.toInstance();
	}

	@Nonnull
	public static SealedEntity createFacetEntity(@Nonnull String type, int pk, @Nonnull String code) {
		return new InitialEntityBuilder(
			new InternalEntitySchemaBuilder(
				CatalogSchema._internalBuild(TestConstants.TEST_CATALOG, Map.of(), EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE),
				EntitySchema._internalBuild(type)
			)
				.withAttribute("code", String.class)
				.toInstance(),
			pk
		)
			.setAttribute("code", code)
			.toInstance();
	}

}
