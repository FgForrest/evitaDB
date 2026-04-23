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
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaBucketedMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link SetReferenceSchemaBucketedMutationConverter} verifying gRPC round-trip
 * conversion of bucketed mutations including bucketed histogram definitions and
 * bucketedPartially expressions.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("SetReferenceSchemaBucketedMutationConverter")
class SetReferenceSchemaBucketedMutationConverterTest {

	private static SetReferenceSchemaBucketedMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = SetReferenceSchemaBucketedMutationConverter.INSTANCE;
	}

	/**
	 * Verifies basic round-trip with non-inherited bucketed containing nameOfTheIndex and valueExpression.
	 * When using the 2-arg constructor (null bucketedPartially), the round-trip produces EMPTY
	 * bucketedPartially because the non-inherited flag forces coalescing null to EMPTY.
	 */
	@Test
	@DisplayName("should round-trip non-inherited bucketed with nameOfTheIndex and valueExpression")
	void shouldRoundTripNonInheritedBucketedWithNameAndExpression() {
		final Expression valueExpression = ExpressionFactory.parse("1 > 0");
		final SetReferenceSchemaBucketedMutation mutation = new SetReferenceSchemaBucketedMutation(
			"tags",
			new ScopedHistogramIndexDefinition[]{
				new ScopedHistogramIndexDefinition(Scope.LIVE, "priceHistogram", valueExpression)
			},
			ScopedBucketedPartially.EMPTY
		);

		final SetReferenceSchemaBucketedMutation roundTripped = converter.convert(converter.convert(mutation));

		assertEquals(mutation, roundTripped);
		assertNotNull(roundTripped.getBucketedInScopes());
		assertEquals(1, roundTripped.getBucketedInScopes().length);
		assertEquals(Scope.LIVE, roundTripped.getBucketedInScopes()[0].scope());
		assertEquals("priceHistogram", roundTripped.getBucketedInScopes()[0].nameOfTheIndex());
		assertNotNull(roundTripped.getBucketedInScopes()[0].valueExpression());
		assertEquals(
			valueExpression.toExpressionString(),
			roundTripped.getBucketedInScopes()[0].valueExpression().toExpressionString()
		);
	}

	/**
	 * Verifies that a mutation with both bucketed and bucketedPartially expressions survives
	 * gRPC serialization and deserialization.
	 */
	@Test
	@DisplayName("should round-trip mutation with bucketedPartially expression")
	void shouldRoundTripWithBucketedPartiallyExpression() {
		final Expression expression = ExpressionFactory.parse("1 > 0");
		final SetReferenceSchemaBucketedMutation mutation = new SetReferenceSchemaBucketedMutation(
			"tags",
			new ScopedHistogramIndexDefinition[]{
				new ScopedHistogramIndexDefinition(Scope.LIVE, "priceHistogram", null)
			},
			new ScopedBucketedPartially[]{
				new ScopedBucketedPartially(Scope.LIVE, expression)
			}
		);

		final SetReferenceSchemaBucketedMutation roundTripped = converter.convert(converter.convert(mutation));

		assertEquals(mutation, roundTripped);
		assertNotNull(roundTripped.getBucketedPartiallyInScopes());
		assertEquals(1, roundTripped.getBucketedPartiallyInScopes().length);
		assertEquals(Scope.LIVE, roundTripped.getBucketedPartiallyInScopes()[0].scope());
		assertNotNull(roundTripped.getBucketedPartiallyInScopes()[0].expression());
		assertEquals(
			expression.toExpressionString(),
			roundTripped.getBucketedPartiallyInScopes()[0].expression().toExpressionString()
		);
	}

	/**
	 * Verifies that a mutation with null bucketed fields round-trips with
	 * both bucketedInScopes and bucketedPartiallyInScopes coalesced to EMPTY
	 * (bucketed is not an inheritable property).
	 */
	@Test
	@DisplayName("should round-trip null bucketed fields as EMPTY")
	void shouldRoundTripNullBucketedFieldsAsEmpty() {
		final SetReferenceSchemaBucketedMutation mutation = new SetReferenceSchemaBucketedMutation(
			"tags",
			(ScopedHistogramIndexDefinition[]) null,
			null
		);

		final SetReferenceSchemaBucketedMutation roundTripped = converter.convert(converter.convert(mutation));

		assertNotNull(roundTripped.getBucketedInScopes());
		assertEquals(0, roundTripped.getBucketedInScopes().length);
		assertNotNull(roundTripped.getBucketedPartiallyInScopes());
		assertEquals(0, roundTripped.getBucketedPartiallyInScopes().length);
	}

	/**
	 * Verifies that an empty bucketedInScopes array (non-inherited, explicitly "not bucketed in any
	 * scope") round-trips as EMPTY and not null, preserving the semantic distinction from inherited.
	 */
	@Test
	@DisplayName("should round-trip empty bucketedInScopes as EMPTY not null")
	void shouldRoundTripEmptyBucketedInScopesAsEmpty() {
		final SetReferenceSchemaBucketedMutation mutation = new SetReferenceSchemaBucketedMutation(
			"tags",
			ScopedHistogramIndexDefinition.EMPTY,
			ScopedBucketedPartially.EMPTY
		);

		final SetReferenceSchemaBucketedMutation roundTripped = converter.convert(converter.convert(mutation));

		// after B1 fix: empty arrays should come back as EMPTY, not null
		assertNotNull(roundTripped.getBucketedInScopes());
		assertEquals(0, roundTripped.getBucketedInScopes().length);
		assertNotNull(roundTripped.getBucketedPartiallyInScopes());
		assertEquals(0, roundTripped.getBucketedPartiallyInScopes().length);
	}

	/**
	 * Verifies that bucketed entries for multiple scopes with different expressions survive round-trip.
	 */
	@Test
	@DisplayName("should round-trip mutation with multiple scopes and different expressions")
	void shouldRoundTripMultipleScopesWithDifferentExpressions() {
		final Expression liveExpr = ExpressionFactory.parse("1 > 0");
		final Expression archivedExpr = ExpressionFactory.parse("2 > 1");
		final SetReferenceSchemaBucketedMutation mutation = new SetReferenceSchemaBucketedMutation(
			"tags",
			new ScopedHistogramIndexDefinition[]{
				new ScopedHistogramIndexDefinition(Scope.LIVE, "priceHistogram", liveExpr),
				new ScopedHistogramIndexDefinition(Scope.ARCHIVED, "archivedHistogram", archivedExpr)
			},
			new ScopedBucketedPartially[]{
				new ScopedBucketedPartially(Scope.LIVE, liveExpr),
				new ScopedBucketedPartially(Scope.ARCHIVED, archivedExpr)
			}
		);

		final SetReferenceSchemaBucketedMutation roundTripped = converter.convert(converter.convert(mutation));

		assertEquals(mutation, roundTripped);
		assertNotNull(roundTripped.getBucketedInScopes());
		assertEquals(2, roundTripped.getBucketedInScopes().length);
		assertNotNull(roundTripped.getBucketedPartiallyInScopes());
		assertEquals(2, roundTripped.getBucketedPartiallyInScopes().length);
	}

	/**
	 * Verifies that a scope entry with null valueExpression (scope present, no value expression)
	 * survives round-trip.
	 */
	@Test
	@DisplayName("should round-trip mutation with null valueExpression in histogram")
	void shouldRoundTripNullValueExpressionInHistogram() {
		final SetReferenceSchemaBucketedMutation mutation = new SetReferenceSchemaBucketedMutation(
			"tags",
			new ScopedHistogramIndexDefinition[]{
				new ScopedHistogramIndexDefinition(Scope.LIVE, "priceHistogram", null)
			}
		);

		final SetReferenceSchemaBucketedMutation roundTripped = converter.convert(converter.convert(mutation));

		assertNotNull(roundTripped.getBucketedInScopes());
		assertEquals(1, roundTripped.getBucketedInScopes().length);
		assertEquals(Scope.LIVE, roundTripped.getBucketedInScopes()[0].scope());
		assertEquals("priceHistogram", roundTripped.getBucketedInScopes()[0].nameOfTheIndex());
		assertNull(roundTripped.getBucketedInScopes()[0].valueExpression());
	}

	/**
	 * Verifies that a scope entry with null expression in bucketedPartially (scope present, no filter)
	 * survives round-trip.
	 */
	@Test
	@DisplayName("should round-trip mutation with null expression in bucketedPartially")
	void shouldRoundTripNullExpressionInBucketedPartially() {
		final SetReferenceSchemaBucketedMutation mutation = new SetReferenceSchemaBucketedMutation(
			"tags",
			new ScopedHistogramIndexDefinition[]{
				new ScopedHistogramIndexDefinition(Scope.LIVE, "priceHistogram", null)
			},
			new ScopedBucketedPartially[]{
				new ScopedBucketedPartially(Scope.LIVE, null)
			}
		);

		final SetReferenceSchemaBucketedMutation roundTripped = converter.convert(converter.convert(mutation));

		assertNotNull(roundTripped.getBucketedPartiallyInScopes());
		assertEquals(1, roundTripped.getBucketedPartiallyInScopes().length);
		assertEquals(Scope.LIVE, roundTripped.getBucketedPartiallyInScopes()[0].scope());
		assertNull(roundTripped.getBucketedPartiallyInScopes()[0].expression());
	}

	/**
	 * Standard round-trip test verifying the full mutation equals after conversion.
	 */
	@Test
	@DisplayName("should round-trip standard bucketed mutation")
	void shouldRoundTripStandardBucketedMutation() {
		final Expression valueExpression = ExpressionFactory.parse("1 > 0");
		final Expression partialExpression = ExpressionFactory.parse("2 > 1");
		final SetReferenceSchemaBucketedMutation mutation = new SetReferenceSchemaBucketedMutation(
			"tags",
			new ScopedHistogramIndexDefinition[]{
				new ScopedHistogramIndexDefinition(Scope.LIVE, "priceHistogram", valueExpression)
			},
			new ScopedBucketedPartially[]{
				new ScopedBucketedPartially(Scope.LIVE, partialExpression)
			}
		);

		final SetReferenceSchemaBucketedMutation roundTripped = converter.convert(converter.convert(mutation));

		assertEquals(mutation.getName(), roundTripped.getName());
		assertArrayEquals(mutation.getBucketedInScopes(), roundTripped.getBucketedInScopes());
		assertArrayEquals(mutation.getBucketedPartiallyInScopes(), roundTripped.getBucketedPartiallyInScopes());
		assertEquals(mutation, roundTripped);
	}

	/**
	 * Verifies that a mutation with null bucketedInScopes but explicit bucketedPartially
	 * round-trips with bucketedInScopes coalesced to EMPTY (bucketed is not inheritable)
	 * and bucketedPartially preserved.
	 */
	@Test
	@DisplayName("should round-trip null bucketed with explicit bucketedPartially")
	void shouldRoundTripNullBucketedWithExplicitBucketedPartially() {
		final Expression partialExpression = ExpressionFactory.parse("$active == 1");
		final SetReferenceSchemaBucketedMutation mutation = new SetReferenceSchemaBucketedMutation(
			"tags",
			(ScopedHistogramIndexDefinition[]) null,
			new ScopedBucketedPartially[]{
				new ScopedBucketedPartially(Scope.LIVE, partialExpression)
			}
		);

		final SetReferenceSchemaBucketedMutation roundTripped = converter.convert(converter.convert(mutation));

		// bucketedInScopes coalesces to EMPTY (bucketed is not inheritable)
		assertNotNull(roundTripped.getBucketedInScopes(),
			"bucketedInScopes should be EMPTY after round-trip");
		assertEquals(0, roundTripped.getBucketedInScopes().length);
		// bucketedPartiallyInScopes should be preserved
		assertNotNull(roundTripped.getBucketedPartiallyInScopes(),
			"bucketedPartiallyInScopes should not be null after round-trip");
		assertEquals(1, roundTripped.getBucketedPartiallyInScopes().length);
		assertEquals(Scope.LIVE, roundTripped.getBucketedPartiallyInScopes()[0].scope());
		assertNotNull(roundTripped.getBucketedPartiallyInScopes()[0].expression());
		assertEquals(
			partialExpression.toExpressionString(),
			roundTripped.getBucketedPartiallyInScopes()[0].expression().toExpressionString()
		);
	}
}
