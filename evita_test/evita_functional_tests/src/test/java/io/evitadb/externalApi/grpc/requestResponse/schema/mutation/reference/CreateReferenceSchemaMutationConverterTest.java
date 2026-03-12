/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.reference;

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link CreateReferenceSchemaMutationConverter} verifying gRPC round-trip
 * conversion including facetedPartially expressions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("CreateReferenceSchemaMutationConverter (gRPC)")
class CreateReferenceSchemaMutationConverterTest {

	private static CreateReferenceSchemaMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = CreateReferenceSchemaMutationConverter.INSTANCE;
	}

	/**
	 * Verifies basic round-trip with the old 10-arg constructor.
	 */
	@Test
	@DisplayName("should round-trip basic mutation")
	void shouldConvertMutation() {
		final CreateReferenceSchemaMutation mutation1 = new CreateReferenceSchemaMutation(
			"tags",
			"desc",
			"depr",
			Cardinality.ONE_OR_MORE,
			"tag",
			false,
			"tagGroup",
			false,
			true,
			true
		);
		assertEquals(mutation1, converter.convert(converter.convert(mutation1)));

		final CreateReferenceSchemaMutation mutation2 = new CreateReferenceSchemaMutation(
			"tags",
			null,
			null,
			null,
			"tag",
			false,
			null,
			false,
			true,
			true
		);
		assertEquals(mutation2, converter.convert(converter.convert(mutation2)));
	}

	/**
	 * Verifies that a mutation with facetedPartially expressions survives
	 * gRPC serialization and deserialization using the 12-arg constructor.
	 */
	@Test
	@DisplayName("should round-trip mutation with facetedPartially expression")
	void shouldConvertMutationWithFacetedPartially() {
		final Expression expression = ExpressionFactory.parse("1 > 0");
		final CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
			"tags",
			"desc",
			"depr",
			Cardinality.ZERO_OR_MORE,
			"tag",
			false,
			"tagGroup",
			false,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(
					Scope.LIVE, ReferenceIndexType.FOR_FILTERING
				)
			},
			null,
			new Scope[]{Scope.LIVE},
			new ScopedFacetedPartially[]{
				new ScopedFacetedPartially(Scope.LIVE, expression)
			}
		);

		final CreateReferenceSchemaMutation roundTripped =
			converter.convert(converter.convert(mutation));

		assertEquals(mutation, roundTripped);
		assertNotNull(roundTripped.getFacetedPartiallyInScopes());
		assertEquals(1, roundTripped.getFacetedPartiallyInScopes().length);
		assertEquals(Scope.LIVE, roundTripped.getFacetedPartiallyInScopes()[0].scope());
		assertNotNull(roundTripped.getFacetedPartiallyInScopes()[0].expression());
		assertEquals(
			expression.toExpressionString(),
			roundTripped.getFacetedPartiallyInScopes()[0].expression()
				.toExpressionString()
		);
	}

	/**
	 * Verifies that facetedPartially entries for multiple scopes with different
	 * expressions survive round-trip.
	 */
	@Test
	@DisplayName("should round-trip mutation with multiple scopes in facetedPartially")
	void shouldRoundTripMultipleScopesWithFacetedPartially() {
		final Expression liveExpr = ExpressionFactory.parse("1 > 0");
		final Expression archivedExpr = ExpressionFactory.parse("2 > 1");
		final CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
			"tags",
			"desc",
			null,
			Cardinality.ZERO_OR_MORE,
			"tag",
			false,
			null,
			false,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				new ScopedReferenceIndexType(Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING)
			},
			null,
			new Scope[]{Scope.LIVE, Scope.ARCHIVED},
			new ScopedFacetedPartially[]{
				new ScopedFacetedPartially(Scope.LIVE, liveExpr),
				new ScopedFacetedPartially(Scope.ARCHIVED, archivedExpr)
			}
		);

		final CreateReferenceSchemaMutation roundTripped =
			converter.convert(converter.convert(mutation));

		assertEquals(mutation, roundTripped);
		assertNotNull(roundTripped.getFacetedPartiallyInScopes());
		assertEquals(2, roundTripped.getFacetedPartiallyInScopes().length);
	}

	/**
	 * Verifies that a scope entry with null expression survives round-trip.
	 */
	@Test
	@DisplayName("should round-trip mutation with null expression in facetedPartially")
	void shouldRoundTripNullExpressionInFacetedPartially() {
		final CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
			"tags",
			null,
			null,
			Cardinality.ZERO_OR_MORE,
			"tag",
			false,
			null,
			false,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
			},
			null,
			new Scope[]{Scope.LIVE},
			new ScopedFacetedPartially[]{
				new ScopedFacetedPartially(Scope.LIVE, null)
			}
		);

		final CreateReferenceSchemaMutation roundTripped =
			converter.convert(converter.convert(mutation));

		assertNotNull(roundTripped.getFacetedPartiallyInScopes());
		assertEquals(1, roundTripped.getFacetedPartiallyInScopes().length);
		assertEquals(Scope.LIVE, roundTripped.getFacetedPartiallyInScopes()[0].scope());
		assertNull(roundTripped.getFacetedPartiallyInScopes()[0].expression());
	}
}
