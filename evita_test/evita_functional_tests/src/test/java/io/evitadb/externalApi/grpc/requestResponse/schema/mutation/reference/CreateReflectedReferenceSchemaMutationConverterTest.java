/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReflectedReferenceSchemaMutation;
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
 * Tests for {@link CreateReflectedReferenceSchemaMutationConverter} verifying gRPC
 * round-trip conversion of reflected reference mutations including facetedPartially.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("CreateReflectedReferenceSchemaMutationConverter (gRPC)")
class CreateReflectedReferenceSchemaMutationConverterTest {

	private static CreateReflectedReferenceSchemaMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = CreateReflectedReferenceSchemaMutationConverter.INSTANCE;
	}

	/**
	 * Verifies basic round-trip without facetedPartially.
	 */
	@Test
	@DisplayName("should round-trip basic reflected reference mutation")
	void shouldConvertMutation() {
		final CreateReflectedReferenceSchemaMutation mutation =
			new CreateReflectedReferenceSchemaMutation(
				"tags",
				"desc",
				"depr",
				Cardinality.ZERO_OR_MORE,
				"tag",
				"originalTags",
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(
						Scope.LIVE, ReferenceIndexType.FOR_FILTERING
					)
				},
				null,
				new Scope[]{Scope.LIVE},
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{"order"}
			);

		final CreateReflectedReferenceSchemaMutation roundTripped =
			converter.convert(converter.convert(mutation));

		assertEquals(mutation, roundTripped);
	}

	/**
	 * Verifies that a mutation with facetedPartially expression survives
	 * gRPC serialization and deserialization.
	 */
	@Test
	@DisplayName("should round-trip mutation with facetedPartially expression")
	void shouldConvertMutationWithFacetedPartially() {
		final Expression expression = ExpressionFactory.parse("1 > 0");
		final CreateReflectedReferenceSchemaMutation mutation =
			new CreateReflectedReferenceSchemaMutation(
				"tags",
				"desc",
				"depr",
				Cardinality.ZERO_OR_MORE,
				"tag",
				"originalTags",
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(
						Scope.LIVE, ReferenceIndexType.FOR_FILTERING
					)
				},
				null,
				new Scope[]{Scope.LIVE},
				new ScopedFacetedPartially[]{
					new ScopedFacetedPartially(Scope.LIVE, expression)
				},
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{"order"}
			);

		final CreateReflectedReferenceSchemaMutation roundTripped =
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
	 * Verifies that when faceted is inherited (null facetedInScopes),
	 * facetedPartially is also null after round-trip.
	 */
	@Test
	@DisplayName("should preserve null facetedPartially when faceted is inherited")
	void shouldPreserveInheritedFacetedPartially() {
		final CreateReflectedReferenceSchemaMutation mutation =
			new CreateReflectedReferenceSchemaMutation(
				"tags",
				"desc",
				null,
				null,
				"tag",
				"originalTags",
				null,  // indexed inherited
				null,  // components inherited
				null,  // faceted inherited
				null,  // facetedPartially inherited
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

		final CreateReflectedReferenceSchemaMutation roundTripped =
			converter.convert(converter.convert(mutation));

		assertNull(roundTripped.getFacetedInScopes());
		assertNull(roundTripped.getFacetedPartiallyInScopes());
	}

	/**
	 * Verifies that facetedPartially entries for multiple scopes survive
	 * round-trip on a non-inherited reflected reference.
	 */
	@Test
	@DisplayName("should round-trip mutation with multiple scopes in facetedPartially")
	void shouldRoundTripMultipleScopesWithFacetedPartially() {
		final Expression liveExpr = ExpressionFactory.parse("1 > 0");
		final Expression archivedExpr = ExpressionFactory.parse("2 > 1");
		final CreateReflectedReferenceSchemaMutation mutation =
			new CreateReflectedReferenceSchemaMutation(
				"tags",
				"desc",
				null,
				Cardinality.ZERO_OR_MORE,
				"tag",
				"originalTags",
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
					new ScopedReferenceIndexType(Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				new Scope[]{Scope.LIVE, Scope.ARCHIVED},
				new ScopedFacetedPartially[]{
					new ScopedFacetedPartially(Scope.LIVE, liveExpr),
					new ScopedFacetedPartially(Scope.ARCHIVED, archivedExpr)
				},
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{"order"}
			);

		final CreateReflectedReferenceSchemaMutation roundTripped =
			converter.convert(converter.convert(mutation));

		assertEquals(mutation, roundTripped);
		assertNotNull(roundTripped.getFacetedPartiallyInScopes());
		assertEquals(2, roundTripped.getFacetedPartiallyInScopes().length);
	}
}
