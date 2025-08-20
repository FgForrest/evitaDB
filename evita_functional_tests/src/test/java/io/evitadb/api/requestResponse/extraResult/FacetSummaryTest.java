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

package io.evitadb.api.requestResponse.extraResult;

import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * This test verifies {@link FacetSummaryTest} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FacetSummaryTest {

	@Test
	void shouldBeEqual() {
		final FacetSummary one = createFacetSummary();
		final FacetSummary two = createFacetSummary();

		assertNotSame(one, two);
		assertEquals(one, two);
	}

	@Nonnull
	private static FacetSummary createFacetSummary() {
		final ReferenceSchema parameter = ReferenceSchema._internalBuild(
			"parameter",
			"parameter", false, Cardinality.ZERO_OR_MORE,
			"parameterGroup", false,
			new ScopedReferenceIndexType[] { new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING) },
			new Scope[]{Scope.LIVE}
		);
		return new FacetSummary(
			Arrays.asList(
				new FacetGroupStatistics(
					parameter,
					new EntityReference("parameterGroup", 1),
					14,
					Arrays.asList(
						new FacetStatistics(new EntityReference("parameter", 1), true, 5, null),
						new FacetStatistics(new EntityReference("parameter", 2), false, 6, new RequestImpact(6, 11, true)),
						new FacetStatistics(new EntityReference("parameter", 3), false, 3, new RequestImpact(3, 8, true))
					)
				),
				new FacetGroupStatistics(
					parameter,
					new EntityReference("parameterGroup", 2),
					14,
					Arrays.asList(
						new FacetStatistics(new EntityReference("parameter", 4), true, 5, null),
						new FacetStatistics(new EntityReference("parameter", 5), false, 6, new RequestImpact(6, 11, true)),
						new FacetStatistics(new EntityReference("parameter", 6), false, 3, new RequestImpact(3, 8, true))
					)
				)
			)
		);
	}

}
