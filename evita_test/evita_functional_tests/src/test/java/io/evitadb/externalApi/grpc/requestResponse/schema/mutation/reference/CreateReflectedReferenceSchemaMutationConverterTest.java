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
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.REFERENCE;

/**
 * Tests for {@link CreateReflectedReferenceSchemaMutationConverter} verifying gRPC
 * round-trip conversion of reflected reference mutations including facetedPartially
 * and bucketed expressions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("CreateReflectedReferenceSchemaMutationConverter (gRPC)")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
@Tag(REFERENCE)
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
				null,
				ScopedHistogramIndexDefinition.EMPTY,
				ScopedBucketedPartially.EMPTY,
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
				ScopedHistogramIndexDefinition.EMPTY,
				ScopedBucketedPartially.EMPTY,
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
				null,
				null,
				null,
				null,
				null,
				null,
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

		final CreateReflectedReferenceSchemaMutation roundTripped =
			converter.convert(converter.convert(mutation));

		assertNull(roundTripped.getFacetedInScopes());
		assertNull(roundTripped.getFacetedPartiallyInScopes());
		// bucketed is not inheritable — null coalesces to EMPTY
		assertNotNull(roundTripped.getBucketedInScopes());
		assertEquals(0, roundTripped.getBucketedInScopes().length);
		assertNotNull(roundTripped.getBucketedPartiallyInScopes());
		assertEquals(0, roundTripped.getBucketedPartiallyInScopes().length);
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
				ScopedHistogramIndexDefinition.EMPTY,
				ScopedBucketedPartially.EMPTY,
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{"order"}
			);

		final CreateReflectedReferenceSchemaMutation roundTripped =
			converter.convert(converter.convert(mutation));

		assertEquals(mutation, roundTripped);
		assertNotNull(roundTripped.getFacetedPartiallyInScopes());
		assertEquals(2, roundTripped.getFacetedPartiallyInScopes().length);
	}

	/**
	 * Verifies that a mutation with bucketed histogram definitions and bucketedPartially
	 * expressions survives gRPC round-trip.
	 */
	@Test
	@DisplayName("should round-trip mutation with bucketed fields")
	void shouldConvertMutationWithBucketedFields() {
		final Expression valueExpr = ExpressionFactory.parse("$price * $quantity");
		final Expression bucketedPartiallyExpr = ExpressionFactory.parse("$active == 1");
		final CreateReflectedReferenceSchemaMutation mutation =
			new CreateReflectedReferenceSchemaMutation(
				"tags",
				"desc",
				null,
				Cardinality.ZERO_OR_MORE,
				"tag",
				"originalTags",
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				new Scope[]{Scope.LIVE},
				null,
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(
						Scope.LIVE, "priceHistogram", valueExpr,
						null
					)
				},
				new ScopedBucketedPartially[]{
					new ScopedBucketedPartially(Scope.LIVE, bucketedPartiallyExpr)
				},
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{"order"}
			);

		final CreateReflectedReferenceSchemaMutation roundTripped =
			converter.convert(converter.convert(mutation));

		assertEquals(mutation, roundTripped);
		assertNotNull(roundTripped.getBucketedInScopes());
		assertEquals(1, roundTripped.getBucketedInScopes().length);
		assertEquals("priceHistogram", roundTripped.getBucketedInScopes()[0].nameOfTheIndex());
		assertNotNull(roundTripped.getBucketedInScopes()[0].valueExpression());
		assertEquals(
			valueExpr.toExpressionString(),
			roundTripped.getBucketedInScopes()[0].valueExpression().toExpressionString()
		);

		assertNotNull(roundTripped.getBucketedPartiallyInScopes());
		assertEquals(1, roundTripped.getBucketedPartiallyInScopes().length);
		assertEquals(Scope.LIVE, roundTripped.getBucketedPartiallyInScopes()[0].scope());
		assertNotNull(roundTripped.getBucketedPartiallyInScopes()[0].expression());
	}

	/**
	 * Verifies that null bucketed fields round-trip as EMPTY (bucketed is not inheritable).
	 */
	@Test
	@DisplayName("should round-trip null bucketed fields as EMPTY")
	void shouldRoundTripNullBucketedFieldsAsEmpty() {
		final CreateReflectedReferenceSchemaMutation mutation =
			new CreateReflectedReferenceSchemaMutation(
				"tags",
				"desc",
				null,
				null,
				"tag",
				"originalTags",
				null,
				null,
				new Scope[]{Scope.LIVE},
				null,
				null,
				null,
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{"order"}
			);

		final CreateReflectedReferenceSchemaMutation roundTripped =
			converter.convert(converter.convert(mutation));

		// bucketed is not inheritable — null coalesces to EMPTY
		assertNotNull(roundTripped.getBucketedInScopes());
		assertEquals(0, roundTripped.getBucketedInScopes().length);
		assertNotNull(roundTripped.getBucketedPartiallyInScopes());
		assertEquals(0, roundTripped.getBucketedPartiallyInScopes().length);
	}

	/**
	 * Pins the gRPC encode path for the per-histogram `assignedWhen` partition selector
	 * on `CreateReflectedReferenceSchemaMutation`. The encoder helper used to silently
	 * drop this field — proto round-tripping would return `null` regardless of the input
	 * — so this test exercises a non-null assignedWhen end-to-end.
	 */
	@Test
	@DisplayName("should round-trip assignedWhen partition selector on bucketed histogram")
	void shouldRoundTripAssignedWhenOnBucketedHistogram() {
		final Expression valueExpr = ExpressionFactory.parse("$price * $quantity");
		final Expression assignedWhen = ExpressionFactory.parse("$active == 1");
		final CreateReflectedReferenceSchemaMutation mutation =
			new CreateReflectedReferenceSchemaMutation(
				"tags",
				"desc",
				null,
				Cardinality.ZERO_OR_MORE,
				"tag",
				"originalTags",
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				new Scope[]{Scope.LIVE},
				null,
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(
						Scope.LIVE, "priceHistogram", valueExpr, assignedWhen
					)
				},
				null,
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{"order"}
			);

		final CreateReflectedReferenceSchemaMutation roundTripped =
			converter.convert(converter.convert(mutation));

		// the round-trip normalizes a null `bucketedPartially` field to EMPTY (bucketed is not
		// inheritable), so we assert per-field rather than via the full-mutation equals
		assertNotNull(roundTripped.getBucketedInScopes());
		assertEquals(1, roundTripped.getBucketedInScopes().length);
		assertNotNull(
			roundTripped.getBucketedInScopes()[0].assignedWhen(),
			"assignedWhen must survive gRPC round-trip"
		);
		assertEquals(
			assignedWhen.toExpressionString(),
			roundTripped.getBucketedInScopes()[0].assignedWhen().toExpressionString()
		);
	}
}
