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
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link SetReferenceSchemaFacetedMutationConverter} verifying gRPC round-trip
 * conversion of faceted mutations including facetedPartially expressions.
 *
 * @author evitaDB
 */
@DisplayName("SetReferenceSchemaFacetedMutationConverter")
class SetReferenceSchemaFacetedMutationConverterTest {

	private static SetReferenceSchemaFacetedMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = SetReferenceSchemaFacetedMutationConverter.INSTANCE;
	}

	/**
	 * Verifies basic round-trip with the old boolean constructor.
	 */
	@Test
	@DisplayName("should round-trip basic boolean mutation")
	void shouldConvertMutation() {
		final SetReferenceSchemaFacetedMutation mutation1 = new SetReferenceSchemaFacetedMutation(
			"tags", true
		);
		assertEquals(mutation1, converter.convert(converter.convert(mutation1)));
	}

	/**
	 * Verifies that a mutation with per-scope facetedPartially expression survives
	 * gRPC serialization and deserialization.
	 */
	@Test
	@DisplayName("should round-trip mutation with facetedPartially expression")
	void shouldConvertMutationWithFacetedPartially() {
		final Expression expression = ExpressionFactory.parse("1 > 0");
		final SetReferenceSchemaFacetedMutation mutation =
			new SetReferenceSchemaFacetedMutation(
				"tags",
				new Scope[]{Scope.LIVE},
				new ScopedFacetedPartially[]{
					new ScopedFacetedPartially(Scope.LIVE, expression)
				}
			);

		final SetReferenceSchemaFacetedMutation roundTripped =
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
	 * Verifies that a mutation with empty facetedPartiallyInScopes (no expressions)
	 * round-trips correctly -- an empty repeated list on the gRPC side is converted
	 * back to null (intended: empty array becomes null after gRPC round-trip).
	 */
	@Test
	@DisplayName("should round-trip mutation with empty facetedPartially as null")
	void shouldConvertMutationWithEmptyFacetedPartially() {
		final SetReferenceSchemaFacetedMutation mutation =
			new SetReferenceSchemaFacetedMutation(
				"tags",
				new Scope[]{Scope.LIVE},
				ScopedFacetedPartially.EMPTY
			);

		final SetReferenceSchemaFacetedMutation roundTripped =
			converter.convert(converter.convert(mutation));

		assertNotNull(roundTripped.getFacetedInScopes());
		// empty array serializes to empty repeated list, which parseFacetedPartially
		// converts back to null
		assertNull(roundTripped.getFacetedPartiallyInScopes());
	}

	/**
	 * Verifies that facetedPartially entries for multiple scopes with different
	 * expressions survive round-trip.
	 */
	@Test
	@DisplayName("should round-trip mutation with multiple scopes")
	void shouldRoundTripMultipleScopes() {
		final Expression liveExpr = ExpressionFactory.parse("1 > 0");
		final Expression archivedExpr = ExpressionFactory.parse("2 > 1");
		final SetReferenceSchemaFacetedMutation mutation =
			new SetReferenceSchemaFacetedMutation(
				"tags",
				new Scope[]{Scope.LIVE, Scope.ARCHIVED},
				new ScopedFacetedPartially[]{
					new ScopedFacetedPartially(Scope.LIVE, liveExpr),
					new ScopedFacetedPartially(Scope.ARCHIVED, archivedExpr)
				}
			);

		final SetReferenceSchemaFacetedMutation roundTripped =
			converter.convert(converter.convert(mutation));

		assertEquals(mutation, roundTripped);
		assertNotNull(roundTripped.getFacetedPartiallyInScopes());
		assertEquals(2, roundTripped.getFacetedPartiallyInScopes().length);
	}

	/**
	 * Verifies that a scope entry with null expression (scope present but no
	 * partial faceting filter) survives round-trip.
	 */
	@Test
	@DisplayName("should round-trip mutation with null expression")
	void shouldRoundTripNullExpression() {
		final SetReferenceSchemaFacetedMutation mutation =
			new SetReferenceSchemaFacetedMutation(
				"tags",
				new Scope[]{Scope.LIVE},
				new ScopedFacetedPartially[]{
					new ScopedFacetedPartially(Scope.LIVE, null)
				}
			);

		final SetReferenceSchemaFacetedMutation roundTripped =
			converter.convert(converter.convert(mutation));

		assertNotNull(roundTripped.getFacetedPartiallyInScopes());
		assertEquals(1, roundTripped.getFacetedPartiallyInScopes().length);
		assertEquals(Scope.LIVE, roundTripped.getFacetedPartiallyInScopes()[0].scope());
		assertNull(roundTripped.getFacetedPartiallyInScopes()[0].expression());
	}

	/**
	 * Verifies that an inherited mutation (null facetedInScopes) round-trips with
	 * both facetedInScopes and facetedPartiallyInScopes as null.
	 */
	@Test
	@DisplayName("should round-trip inherited mutation")
	void shouldRoundTripInheritedMutation() {
		final SetReferenceSchemaFacetedMutation mutation =
			new SetReferenceSchemaFacetedMutation(
				"tags",
				(Scope[]) null,
				null
			);

		final SetReferenceSchemaFacetedMutation roundTripped =
			converter.convert(converter.convert(mutation));

		assertNull(roundTripped.getFacetedInScopes());
		assertNull(roundTripped.getFacetedPartiallyInScopes());
	}
}
