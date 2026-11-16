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

package io.evitadb.core.expression.trigger;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaBucketedMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation;
import io.evitadb.core.expression.trigger.IndexingExpressionInputCells.Cell;
import io.evitadb.core.expression.trigger.IndexingExpressionInputValidator.IndexingExpressionKind;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static io.evitadb.core.expression.trigger.IndexingExpressionInputCells.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.EXPRESSION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies, cell by cell, which reads `IndexingExpressionInputValidator` admits in each expression slot of a reference
 * schema, and that a refusal names the expression, its slot, the reference, the entity type, the scope, the refused
 * read and the reason. The cells are the table of `IndexingExpressionInputCells`: histogram conditions admit only
 * reads evitaDB keeps the histogram current with, histogram values only the four attribute sources, facet conditions
 * everything except the reject list.
 *
 * Expressions are parsed with `ExpressionFactory.parse` and validated directly; no entity schema, catalog or engine is
 * involved. The nested `SchemaLevelEntryPoints` covers the entry points that check whole mutation payloads and
 * reference schemas, built in memory.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Inputs an indexing expression may read")
@Tag(ENGINE)
@Tag(EXPRESSION)
class IndexingExpressionInputValidatorTest {

	private static final IndexingExpressionKind BUCKETED_PARTIALLY = IndexingExpressionKind.BUCKETED_PARTIALLY;
	private static final IndexingExpressionKind ASSIGNED_WHEN = IndexingExpressionKind.ASSIGNED_WHEN;
	private static final IndexingExpressionKind HISTOGRAM_VALUE = IndexingExpressionKind.HISTOGRAM_VALUE;
	private static final IndexingExpressionKind FACETED_PARTIALLY = IndexingExpressionKind.FACETED_PARTIALLY;
	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "brand";
	private static final String HISTOGRAM_NAME = "shares";
	private static final String VALUE = "$reference.attributes['share']";
	private static final String ACCEPTED_CONDITION = "$entity.attributes['status'] == 'A'";
	private static final String REFUSED_CONDITION = "$entity.version >= 3";

	/**
	 * Cells of the histogram conditions, each in the `bucketedPartially` and the `assignedWhen` slot.
	 *
	 * @return the cells
	 */
	@Nonnull
	static Stream<Arguments> histogramConditionCells() {
		final List<Arguments> arguments = new ArrayList<>(HISTOGRAM_CONDITION_CELLS.size() * 2);
		for (final Cell cell : HISTOGRAM_CONDITION_CELLS) {
			arguments.add(Arguments.of(cell.withKind(BUCKETED_PARTIALLY)));
			arguments.add(Arguments.of(cell.withKind(ASSIGNED_WHEN)));
		}
		return arguments.stream();
	}

	/**
	 * Cells placing a refused read in every position the walk must reach: under negation and parentheses, on the
	 * right of `&&` and `??`, as a function argument, behind `?.`, and nested in a predicate, a spread mapping or a
	 * key. With the histogram rules an `$entity` or `$reference` root nested there is refused for being nested before
	 * anything else; the facet rules admit nested roots, so a nested read is refused for its own reason there, and
	 * they admit `$entity.scope`.
	 *
	 * @return the cells, the histogram ones in both histogram slots
	 */
	@Nonnull
	static Stream<Arguments> positionCells() {
		final List<Arguments> arguments = new ArrayList<>(96);
		final String[][] histogramReads = {
			{"$entity.version", ENGINE_MAINTAINED, NESTED_ROOT},
			{"$entity.scope", UNTRACKED, NESTED_ROOT},
			{"random()", RANDOM, RANDOM},
			{"$unknown", VARIABLE, VARIABLE}
		};
		for (final Cell cell : positions(histogramReads)) {
			arguments.add(Arguments.of(cell.withKind(BUCKETED_PARTIALLY)));
			arguments.add(Arguments.of(cell.withKind(ASSIGNED_WHEN)));
		}
		final String[][] facetReads = {
			{"$entity.version", ENGINE_MAINTAINED, ENGINE_MAINTAINED},
			{"random()", RANDOM, RANDOM},
			{"$unknown", VARIABLE, VARIABLE}
		};
		for (final Cell cell : positions(facetReads)) {
			arguments.add(Arguments.of(cell.withKind(FACETED_PARTIALLY)));
		}
		return arguments.stream();
	}

	/**
	 * Places each read in every position, and `$entity?.version` behind `?.`.
	 *
	 * @param reads each read with the reason it is refused for at the top level and the reason nested in a predicate,
	 *              a spread mapping or a key
	 * @return the cells
	 */
	@Nonnull
	private static List<Cell> positions(@Nonnull String[][] reads) {
		final List<Cell> cells = new ArrayList<>(reads.length * 9 + 1);
		for (final String[] read : reads) {
			final String operand = read[0];
			cells.add(refused("P-top " + operand, operand + " == 1", operand, read[1]));
			cells.add(refused("P-not " + operand, "!(" + operand + " == 1)", operand, read[1]));
			cells.add(refused("P-par " + operand, "((" + operand + ")) == 1", operand, read[1]));
			cells.add(refused("P-and " + operand, "true && " + operand + " == 1", operand, read[1]));
			cells.add(refused("P-coalesce " + operand, "($entity.attributes['delta'] ?? " + operand + ") == 1",
				operand, read[1]));
			cells.add(refused("P-function " + operand, "abs(" + operand + ") > 1", operand, read[1]));
			cells.add(refused("P-predicate " + operand, "$entity.attributes['tags'].any($ == " + operand + ")",
				operand, read[2]));
			cells.add(refused("P-spread " + operand, "$entity.attributes['tags'].*[" + operand + "].size() > 0",
				operand, read[2]));
			cells.add(refused("P-key " + operand, "$entity.attributes['tags'][" + operand + "] == 'x'",
				operand, read[2]));
		}
		cells.add(refused("P-nullsafe", "$entity?.version == 1", "$entity?.version", ENGINE_MAINTAINED));
		return cells;
	}

	/**
	 * Cells of histogram value expressions.
	 *
	 * @return the cells
	 */
	@Nonnull
	static Stream<Arguments> histogramValueCells() {
		return HISTOGRAM_VALUE_CELLS.stream().map(Arguments::of);
	}

	/**
	 * Cells of facet conditions.
	 *
	 * @return the cells
	 */
	@Nonnull
	static Stream<Arguments> facetConditionCells() {
		return FACET_CONDITION_CELLS.stream().map(Arguments::of);
	}

	/**
	 * Proves that each histogram condition cell gets its verdict in both histogram slots: an accepted cell passes,
	 * a refused one is refused naming the read and the reason of the cell.
	 *
	 * @param cell the cell, in the `bucketedPartially` or the `assignedWhen` slot
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("histogramConditionCells")
	@DisplayName("a histogram condition reads only data evitaDB keeps the histogram current with")
	void shouldAdmitOnlyTrackedReadsInHistogramConditions(@Nonnull Cell cell) {
		verify(cell);
	}

	/**
	 * Proves that the walk reaches a refused read wherever it stands in the expression, and refuses it for the
	 * reason that position calls for.
	 *
	 * @param cell the refused read placed in one position, in one slot
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("positionCells")
	@DisplayName("a refused read is found at every position of the expression")
	void shouldFindRefusedReadAtEveryPosition(@Nonnull Cell cell) {
		verify(cell);
	}

	/**
	 * Proves that a histogram value expression is accepted exactly in the forms of a single attribute read, and that
	 * any other form is refused as an unsupported form.
	 *
	 * @param cell the value expression cell
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("histogramValueCells")
	@DisplayName("a histogram value is one attribute source, optionally with a numeric default")
	void shouldAdmitOnlyAttributeSourcesAsHistogramValues(@Nonnull Cell cell) {
		verify(cell);
	}

	/**
	 * Proves that a facet condition is refused only for a read on the reject list, naming the read and the reason,
	 * and that every other read passes.
	 *
	 * @param cell the facet condition cell
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("facetConditionCells")
	@DisplayName("a facet condition is refused only for the reads on the reject list")
	void shouldRefuseOnlyRejectListReadsInFacetConditions(@Nonnull Cell cell) {
		verify(cell);
	}

	/**
	 * Proves the exact refusal message of each slot: every part - the slot, the histogram where the slot has one, the
	 * expression, the reference, the entity type, the scope, the read and the reason - in its place.
	 *
	 * @param kind the slot
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(IndexingExpressionKind.class)
	@DisplayName("a refusal names the slot, the histogram, the expression, the reference, the type and the scope")
	void shouldNameEveryPartOfTheRefusalInOrder(@Nonnull IndexingExpressionKind kind) {
		final String condition = "$entity.version >= 3";
		final String value = "$reference.attributes['share'] + 1";
		final String expression = kind == HISTOGRAM_VALUE ? value : condition;
		final String where = " of reference `linkName` of entity type `ownerType` in scope ARCHIVED";
		final String versionRefusal = " cannot read `$entity.version`: evitaDB maintains this value itself " +
			"rather than taking it from your mutations, so an index built on it could not be kept current.";
		final String expected = switch (kind) {
			case BUCKETED_PARTIALLY -> "The `bucketedPartially` condition `" + condition + "`" + where + versionRefusal;
			case ASSIGNED_WHEN ->
				"The `assignedWhen` condition of histogram `bucketName` `" + condition + "`" + where + versionRefusal;
			case HISTOGRAM_VALUE -> "The value expression of histogram `bucketName` `" + value + "`" + where +
				" has an unsupported form: a histogram value must be a single attribute read - " +
				"`$reference.attributes['x']`, `$reference.localizedAttributes['x']`, " +
				"`$reference.referencedEntity.attributes['x']` or " +
				"`$reference.referencedEntity.localizedAttributes['x']` - optionally followed by `?? <number>`.";
			case FACETED_PARTIALLY -> "The `facetedPartially` condition `" + condition + "`" + where + versionRefusal;
		};

		final InvalidSchemaMutationException refusal = assertThrows(
			InvalidSchemaMutationException.class,
			() -> IndexingExpressionInputValidator.validate(
				ExpressionFactory.parse(expression), kind, "ownerType", "linkName",
				kind == ASSIGNED_WHEN || kind == HISTOGRAM_VALUE ? "bucketName" : null, Scope.ARCHIVED
			)
		);

		assertEquals(expected, refusal.getMessage());
	}

	/**
	 * The entry points that check whole payloads and whole reference schemas: each skips what holds no expression,
	 * checks every scope entry, histogram and mutation, and names the one it refuses.
	 */
	@Nested
	@DisplayName("the schema-level entry points")
	class SchemaLevelEntryPoints {

		/**
		 * Proves that a payload holding only entries that clear an expression, and a mutation that carries none, is
		 * accepted without a check failing on the missing expression.
		 */
		@Test
		@DisplayName("skip an entry clearing an expression and a mutation carrying none")
		void shouldSkipClearingEntriesAndMutationsWithoutExpression() {
			final ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
				ENTITY_TYPE,
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME, new Scope[]{Scope.LIVE},
					new ScopedFacetedPartially[]{new ScopedFacetedPartially(Scope.LIVE, null)}
				),
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(Scope.LIVE, HISTOGRAM_NAME, null, null)
					},
					new ScopedBucketedPartially[]{new ScopedBucketedPartially(Scope.LIVE, null)}
				),
				new ModifyEntitySchemaDescriptionMutation("products")
			);

			assertDoesNotThrow(() -> IndexingExpressionInputValidator.validateEntitySchemaMutation(mutation));
		}

		/**
		 * Proves that the payload check goes past an accepted first scope entry and names the scope of the refused
		 * second one.
		 */
		@Test
		@DisplayName("name the scope of a refused second scope entry")
		void shouldNameScopeOfRefusedSecondScopeEntry() {
			final ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
				ENTITY_TYPE,
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME, new Scope[]{Scope.LIVE, Scope.ARCHIVED},
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, ExpressionFactory.parse(ACCEPTED_CONDITION)),
						new ScopedFacetedPartially(Scope.ARCHIVED, ExpressionFactory.parse(REFUSED_CONDITION))
					}
				)
			);

			final InvalidSchemaMutationException refusal = assertThrows(
				InvalidSchemaMutationException.class,
				() -> IndexingExpressionInputValidator.validateEntitySchemaMutation(mutation)
			);

			assertTrue(refusal.getMessage().contains("in scope ARCHIVED cannot read `$entity.version`"),
				refusal.getMessage());
		}

		/**
		 * Proves that the payload check goes past an accepted first mutation of a batch and names the reference of the
		 * refused second one.
		 */
		@Test
		@DisplayName("name the reference of a refused second mutation of a batch")
		void shouldNameReferenceOfRefusedSecondMutation() {
			final ModifyEntitySchemaMutation mutation = new ModifyEntitySchemaMutation(
				ENTITY_TYPE,
				bucketedPartially("first", ACCEPTED_CONDITION),
				bucketedPartially("second", REFUSED_CONDITION)
			);

			final InvalidSchemaMutationException refusal = assertThrows(
				InvalidSchemaMutationException.class,
				() -> IndexingExpressionInputValidator.validateEntitySchemaMutation(mutation)
			);

			assertTrue(refusal.getMessage().contains("of reference `second` of entity type `" + ENTITY_TYPE + "`"),
				refusal.getMessage());
		}

		/**
		 * Proves that the check of a stored reference schema goes past an accepted first histogram of a scope and
		 * names the second one, whose value expression is refused.
		 */
		@Test
		@DisplayName("name the second histogram of a scope when its value is refused")
		void shouldNameRefusedSecondHistogramOfScope() {
			final ReferenceSchemaContract referenceSchema = referenceSchema(
				null,
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(Scope.LIVE, "first", ExpressionFactory.parse(VALUE), null),
					new ScopedHistogramIndexDefinition(
						Scope.LIVE, "second", ExpressionFactory.parse(VALUE + " + 1"), null
					)
				},
				null
			);

			final InvalidSchemaMutationException refusal = assertThrows(
				InvalidSchemaMutationException.class,
				() -> IndexingExpressionInputValidator.validateHistogramExpressions(ENTITY_TYPE, referenceSchema)
			);

			assertTrue(refusal.getMessage().startsWith("The value expression of histogram `second` "),
				refusal.getMessage());
			assertTrue(refusal.getMessage().contains("in scope LIVE has an unsupported form"), refusal.getMessage());
		}

		/**
		 * Proves that the check of a stored reference schema checks the `assignedWhen` condition of a histogram
		 * outside the live scope too, and names that scope.
		 */
		@Test
		@DisplayName("name the scope of a refused histogram condition outside the live scope")
		void shouldNameNonLiveScopeOfRefusedHistogramCondition() {
			final ReferenceSchemaContract referenceSchema = referenceSchema(
				null,
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(
						Scope.LIVE, HISTOGRAM_NAME, ExpressionFactory.parse(VALUE),
						ExpressionFactory.parse(ACCEPTED_CONDITION)
					),
					new ScopedHistogramIndexDefinition(
						Scope.ARCHIVED, HISTOGRAM_NAME, ExpressionFactory.parse(VALUE),
						ExpressionFactory.parse(REFUSED_CONDITION)
					)
				},
				new ScopedBucketedPartially[]{
					new ScopedBucketedPartially(Scope.LIVE, ExpressionFactory.parse(ACCEPTED_CONDITION))
				}
			);

			final InvalidSchemaMutationException refusal = assertThrows(
				InvalidSchemaMutationException.class,
				() -> IndexingExpressionInputValidator.validateHistogramExpressions(ENTITY_TYPE, referenceSchema)
			);

			assertTrue(refusal.getMessage().startsWith("The `assignedWhen` condition of histogram `" + HISTOGRAM_NAME),
				refusal.getMessage());
			assertTrue(refusal.getMessage().contains("in scope ARCHIVED cannot read `$entity.version`"),
				refusal.getMessage());
		}

		/**
		 * Proves that the check of a stored reference schema checks the facet condition outside the live scope too,
		 * and names that scope.
		 */
		@Test
		@DisplayName("name the scope of a refused facet condition outside the live scope")
		void shouldNameNonLiveScopeOfRefusedFacetCondition() {
			final ReferenceSchemaContract referenceSchema = referenceSchema(
				new ScopedFacetedPartially[]{
					new ScopedFacetedPartially(Scope.LIVE, ExpressionFactory.parse(ACCEPTED_CONDITION)),
					new ScopedFacetedPartially(Scope.ARCHIVED, ExpressionFactory.parse(REFUSED_CONDITION))
				},
				null,
				null
			);

			final InvalidSchemaMutationException refusal = assertThrows(
				InvalidSchemaMutationException.class,
				() -> IndexingExpressionInputValidator.validateFacetExpressions(ENTITY_TYPE, referenceSchema)
			);

			assertTrue(refusal.getMessage().startsWith("The `facetedPartially` condition "), refusal.getMessage());
			assertTrue(refusal.getMessage().contains("in scope ARCHIVED cannot read `$entity.version`"),
				refusal.getMessage());
		}

		/**
		 * Proves that the check of a stored reference schema goes past the accepted `bucketedPartially` condition of
		 * the live scope and names the scope of the refused one outside it.
		 */
		@Test
		@DisplayName("name the scope of a refused bucketedPartially condition outside the live scope")
		void shouldNameNonLiveScopeOfRefusedBucketedPartiallyCondition() {
			final ReferenceSchemaContract referenceSchema = referenceSchema(
				null,
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(Scope.LIVE, HISTOGRAM_NAME, ExpressionFactory.parse(VALUE), null)
				},
				new ScopedBucketedPartially[]{
					new ScopedBucketedPartially(Scope.LIVE, ExpressionFactory.parse(ACCEPTED_CONDITION)),
					new ScopedBucketedPartially(Scope.ARCHIVED, ExpressionFactory.parse(REFUSED_CONDITION))
				}
			);

			final InvalidSchemaMutationException refusal = assertThrows(
				InvalidSchemaMutationException.class,
				() -> IndexingExpressionInputValidator.validateHistogramExpressions(ENTITY_TYPE, referenceSchema)
			);

			assertTrue(refusal.getMessage().startsWith("The `bucketedPartially` condition "), refusal.getMessage());
			assertTrue(refusal.getMessage().contains("in scope ARCHIVED cannot read `$entity.version`"),
				refusal.getMessage());
		}

		/**
		 * Proves that the payload check reads both expressions of a histogram a mutation sets - the value expression
		 * and the `assignedWhen` condition - and names the histogram and the slot of the refused one.
		 */
		@Test
		@DisplayName("check the value and the condition of a histogram a mutation sets")
		void shouldCheckHistogramValueAndAssignedWhenOfMutationPayload() {
			final ModifyEntitySchemaMutation refusedValue = new ModifyEntitySchemaMutation(
				ENTITY_TYPE,
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(
							Scope.LIVE, HISTOGRAM_NAME, ExpressionFactory.parse(VALUE + " + 1"), null
						)
					}
				)
			);
			final ModifyEntitySchemaMutation refusedCondition = new ModifyEntitySchemaMutation(
				ENTITY_TYPE,
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(
							Scope.LIVE, HISTOGRAM_NAME, ExpressionFactory.parse(VALUE),
							ExpressionFactory.parse(REFUSED_CONDITION)
						)
					}
				)
			);

			final InvalidSchemaMutationException valueRefusal = assertThrows(
				InvalidSchemaMutationException.class,
				() -> IndexingExpressionInputValidator.validateEntitySchemaMutation(refusedValue)
			);
			final InvalidSchemaMutationException conditionRefusal = assertThrows(
				InvalidSchemaMutationException.class,
				() -> IndexingExpressionInputValidator.validateEntitySchemaMutation(refusedCondition)
			);

			assertTrue(
				valueRefusal.getMessage().startsWith("The value expression of histogram `" + HISTOGRAM_NAME + "` "),
				valueRefusal.getMessage()
			);
			assertTrue(valueRefusal.getMessage().contains("has an unsupported form"), valueRefusal.getMessage());
			assertTrue(
				conditionRefusal.getMessage().startsWith(
					"The `assignedWhen` condition of histogram `" + HISTOGRAM_NAME + "` "
				),
				conditionRefusal.getMessage()
			);
			assertTrue(conditionRefusal.getMessage().contains("cannot read `$entity.version`"),
				conditionRefusal.getMessage());
		}

		/**
		 * Creates a mutation setting a histogram and a `bucketedPartially` condition on a reference.
		 *
		 * @param referenceName the reference
		 * @param condition     the condition text
		 * @return the mutation
		 */
		@Nonnull
		private static SetReferenceSchemaBucketedMutation bucketedPartially(
			@Nonnull String referenceName, @Nonnull String condition
		) {
			return new SetReferenceSchemaBucketedMutation(
				referenceName,
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(Scope.LIVE, HISTOGRAM_NAME, ExpressionFactory.parse(VALUE), null)
				},
				new ScopedBucketedPartially[]{
					new ScopedBucketedPartially(Scope.LIVE, ExpressionFactory.parse(condition))
				}
			);
		}

		/**
		 * Builds the `brand` reference schema, indexed and faceted in both scopes, with the given expressions.
		 *
		 * @param facetConditions  the scoped `facetedPartially` conditions, or `null`
		 * @param histograms       the scoped histograms, or `null`
		 * @param bucketConditions the scoped `bucketedPartially` conditions, or `null`
		 * @return the reference schema
		 */
		@Nonnull
		private static ReferenceSchemaContract referenceSchema(
			@Nullable ScopedFacetedPartially[] facetConditions,
			@Nullable ScopedHistogramIndexDefinition[] histograms,
			@Nullable ScopedBucketedPartially[] bucketConditions
		) {
			return ReferenceSchema._internalBuild(
				REFERENCE_NAME, NamingConvention.generate(REFERENCE_NAME), null, null,
				REFERENCE_NAME, NamingConvention.generate(REFERENCE_NAME), false, Cardinality.ZERO_OR_MORE,
				null, null, false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
					new ScopedReferenceIndexType(Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				new Scope[]{Scope.LIVE, Scope.ARCHIVED},
				facetConditions, histograms, bucketConditions,
				Collections.emptyMap(), Collections.emptyMap(), ConflictResolutionOverride.INHERITED,
				null, ReferenceSchemaContract.DEFAULT_SEARCHABLE_THROUGH_WEIGHT
			);
		}
	}

	/**
	 * Validates the cell's expression in a fixed reference and asserts the verdict and, for a refusal, the message.
	 *
	 * @param cell the cell
	 */
	private static void verify(@Nonnull Cell cell) {
		final Expression parsed = ExpressionFactory.parse(cell.expression());
		if (!cell.refused()) {
			assertDoesNotThrow(
				() -> validate(cell, parsed), cell.id() + ": `" + cell.expression() + "` must be accepted"
			);
			return;
		}
		final InvalidSchemaMutationException refusal = assertThrows(
			InvalidSchemaMutationException.class, () -> validate(cell, parsed),
			cell.id() + ": `" + cell.expression() + "` must be refused"
		);
		final String message = refusal.getMessage();
		assertTrue(message.contains(cell.reason()), cell.id() + ": the reason is missing in: " + message);
		assertTrue(message.contains("`" + parsed.toExpressionString() + "`"),
			cell.id() + ": the expression is missing in: " + message);
		assertTrue(
			message.contains("`" + REFERENCE_NAME + "`") && message.contains("`" + ENTITY_TYPE + "`") &&
				message.contains(Scope.LIVE.name()),
			cell.id() + ": the reference, the type or the scope is missing in: " + message
		);
		if (cell.read() == null) {
			assertTrue(message.contains(" has an unsupported form: "),
				cell.id() + ": the message must say the expression has an unsupported form: " + message);
		} else {
			assertTrue(message.contains("cannot read `" + cell.read() + "`"),
				cell.id() + ": the read `" + cell.read() + "` is missing in: " + message);
		}
		if (cell.kind() == ASSIGNED_WHEN || cell.kind() == HISTOGRAM_VALUE) {
			assertTrue(message.contains("`" + HISTOGRAM_NAME + "`"),
				cell.id() + ": the histogram is missing in: " + message);
		}
	}

	/**
	 * Validates the parsed expression in the cell's slot of the `brand` reference of `product`, in scope LIVE.
	 *
	 * @param cell   the cell
	 * @param parsed the parsed expression
	 */
	private static void validate(@Nonnull Cell cell, @Nonnull Expression parsed) {
		IndexingExpressionInputValidator.validate(
			parsed, cell.kind(), ENTITY_TYPE, REFERENCE_NAME,
			cell.kind() == ASSIGNED_WHEN || cell.kind() == HISTOGRAM_VALUE ? HISTOGRAM_NAME : null,
			Scope.LIVE
		);
	}

}
