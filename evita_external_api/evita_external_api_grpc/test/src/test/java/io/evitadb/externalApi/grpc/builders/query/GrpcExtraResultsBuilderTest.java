/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.grpc.builders.query;

import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaEntityResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents.ParentsByReference;
import io.evitadb.api.requestResponse.extraResult.Histogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.externalApi.grpc.builders.query.extraResults.GrpcExtraResultsBuilder;
import io.evitadb.externalApi.grpc.generated.GrpcExtraResults;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.wildfly.common.Assert.assertFalse;
import static org.wildfly.common.Assert.assertTrue;

/**
 * This test verifies functionalities of methods in {@link GrpcExtraResultsBuilder} class.
 *
 * @author Tomáš Pozler, 2022
 */
class GrpcExtraResultsBuilderTest {

	@Test
	void buildExtraResults() {
		final String type = "test1";
		final Query query = Query.query(
			collection("test"),
			require(
				entityFetch(
					dataInLocales(Locale.US)
				),
				hierarchyParentsOfSelf(),
				attributeHistogram(20, type)
			)
		);
		final HierarchyParents integerHierarchyParents = new HierarchyParents(
			new ParentsByReference(Map.of(1, Map.of(1, new EntityClassifier[]{
				new EntityReference(Entities.CATEGORY, 1),
				new EntityReference(Entities.CATEGORY, 2),
				new EntityReference(Entities.CATEGORY, 3)
			}))),
			Collections.emptyMap()
		);
		final Histogram histogram = new Histogram(
			new Bucket[]{
				new Bucket(0, BigDecimal.valueOf(1.5), 3),
				new Bucket(1, BigDecimal.valueOf(2.5), 5),
				new Bucket(2, BigDecimal.valueOf(3.5), 4),
				new Bucket(3, BigDecimal.valueOf(4.8), 6),
				new Bucket(4, BigDecimal.valueOf(8.6), 10),
			},
			BigDecimal.valueOf(10)
		);
		final EvitaEntityResponse response = new EvitaEntityResponse(query, new PaginatedList<>(0, 0, 0), integerHierarchyParents, new AttributeHistogram(Map.of(type, histogram)));
		final GrpcExtraResults extraResults = GrpcExtraResultsBuilder.buildExtraResults(response);

		assertFalse(extraResults.hasPriceHistogram());
		assertTrue(extraResults.getSelfHierarchyParents().getHierarchyParentsByReferenceCount() > 0);
		assertTrue(extraResults.getAttributeHistogramCount() > 0);
		assertTrue(extraResults.getHierarchyStatisticsMap().isEmpty());
		assertTrue(extraResults.getFacetGroupStatisticsList().isEmpty());
	}
}