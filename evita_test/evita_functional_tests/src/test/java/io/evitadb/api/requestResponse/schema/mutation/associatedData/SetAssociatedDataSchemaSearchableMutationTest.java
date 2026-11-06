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

package io.evitadb.api.requestResponse.schema.mutation.associatedData;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.mutation.AssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Optional;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link SetAssociatedDataSchemaSearchableMutation} - the full statement of the searchability axis
 * of associated data: the searchable scopes and the text expression together.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("SetAssociatedDataSchemaSearchableMutation")
@Tag(CONTRACT)
@Tag(SCHEMA)
class SetAssociatedDataSchemaSearchableMutationTest {
	private static final String ASSOCIATED_DATA_NAME = "article";
	private static final Expression TITLE = ExpressionFactory.parse("$value.title");

	/**
	 * Builds an existing associated data schema.
	 *
	 * @param searchableInScopes the searchable scopes
	 * @param textExpression     the text expression
	 * @return the schema
	 */
	@Nonnull
	private static AssociatedDataSchemaContract existing(
		@Nonnull Scope[] searchableInScopes,
		@Nullable Expression textExpression
	) {
		return AssociatedDataSchema._internalBuild(
			ASSOCIATED_DATA_NAME, "description", null, ComplexDataObject.class, true, true,
			searchableInScopes, textExpression, ConflictResolutionOverride.ENTITY,
			FulltextAnalyzerSchemaContract.TEXT, AttributeSchemaContract.DEFAULT_LENGTH_PIVOT
		);
	}

	@Nested
	@DisplayName("Mutating the associated data schema")
	class Mutate {

		@Test
		@DisplayName("should set the scopes and the expression and carry everything else through")
		void shouldSetScopesAndExpression() {
			final AssociatedDataSchemaContract mutated = new SetAssociatedDataSchemaSearchableMutation(
				ASSOCIATED_DATA_NAME, new Scope[]{Scope.LIVE, Scope.ARCHIVED}, TITLE
			).mutate(existing(Scope.NO_SCOPE, null));
			assertEquals(EnumSet.of(Scope.LIVE, Scope.ARCHIVED), mutated.getSearchableInScopes());
			assertEquals(TITLE, mutated.getTextExpression());
			assertTrue(mutated.isSearchable());
			assertEquals("description", mutated.getDescription());
			assertTrue(mutated.isLocalized());
			assertTrue(mutated.isNullable());
			assertEquals(ConflictResolutionOverride.ENTITY, mutated.getConflictResolutionOverride());
		}

		@Test
		@DisplayName("should state the whole axis - an unnamed scope and a missing expression are cleared")
		void shouldClearWhatItDoesNotName() {
			final AssociatedDataSchemaContract mutated = new SetAssociatedDataSchemaSearchableMutation(
				ASSOCIATED_DATA_NAME, false
			).mutate(existing(new Scope[]{Scope.LIVE, Scope.ARCHIVED}, TITLE));
			assertTrue(mutated.getSearchableInScopes().isEmpty());
			assertFalse(mutated.isSearchableInAnyScope());
			assertNull(mutated.getTextExpression());
		}

		@Test
		@DisplayName("should return the very same schema when nothing changes")
		void shouldReturnSameSchemaWhenNothingChanges() {
			final AssociatedDataSchemaContract schema = existing(new Scope[]{Scope.LIVE}, TITLE);
			assertSame(
				schema,
				new SetAssociatedDataSchemaSearchableMutation(
					ASSOCIATED_DATA_NAME, new Scope[]{Scope.LIVE}, ExpressionFactory.parse("$value.title")
				).mutate(schema)
			);
		}

		@Test
		@DisplayName("should refuse to alter associated data the entity schema does not define")
		void shouldRefuseMissingAssociatedData() {
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getAssociatedData(ASSOCIATED_DATA_NAME)).thenReturn(Optional.empty());
			Mockito.when(entitySchema.getName()).thenReturn("product");
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> new SetAssociatedDataSchemaSearchableMutation(ASSOCIATED_DATA_NAME, true)
					.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema)
			);
		}

	}

	@Nested
	@DisplayName("Fulltext field settings")
	class FieldSettings {

		/**
		 * Every other mutation of an associated data schema, each of which must carry the fulltext field settings
		 * through.
		 *
		 * @return the label and the mutation of each case
		 */
		@Nonnull
		static Stream<Arguments> otherAssociatedDataMutations() {
			return Stream.of(
				Arguments.of("nullable", new SetAssociatedDataSchemaNullableMutation(ASSOCIATED_DATA_NAME, false)),
				Arguments.of("localized", new SetAssociatedDataSchemaLocalizedMutation(ASSOCIATED_DATA_NAME, false)),
				Arguments.of("type", new ModifyAssociatedDataSchemaTypeMutation(ASSOCIATED_DATA_NAME, String.class)),
				Arguments.of(
					"description", new ModifyAssociatedDataSchemaDescriptionMutation(ASSOCIATED_DATA_NAME, "changed")
				),
				Arguments.of(
					"deprecation notice",
					new ModifyAssociatedDataSchemaDeprecationNoticeMutation(ASSOCIATED_DATA_NAME, "changed")
				),
				Arguments.of("name", new ModifyAssociatedDataSchemaNameMutation(ASSOCIATED_DATA_NAME, "renamed")),
				Arguments.of(
					"conflict resolution",
					new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
						ASSOCIATED_DATA_NAME, ConflictResolutionOverride.GRANULAR
					)
				)
			);
		}

		@Test
		@DisplayName("should set the analyzer and the length pivot, and keep each when the mutation leaves it null")
		void shouldSetAnalyzerAndPivotAndKeepThemWhenNull() {
			final AssociatedDataSchemaContract configured = new SetAssociatedDataSchemaSearchableMutation(
				ASSOCIATED_DATA_NAME, new Scope[]{Scope.LIVE}, TITLE, "html-text", 400
			).mutate(existing(Scope.NO_SCOPE, null));
			assertEquals("html-text", configured.getAnalyzer());
			assertEquals(400, configured.getLengthPivot());

			final AssociatedDataSchemaContract rescoped = new SetAssociatedDataSchemaSearchableMutation(
				ASSOCIATED_DATA_NAME, Scope.values(), TITLE
			).mutate(configured);
			assertEquals(EnumSet.allOf(Scope.class), rescoped.getSearchableInScopes());
			assertEquals("html-text", rescoped.getAnalyzer());
			assertEquals(400, rescoped.getLengthPivot());
			assertSame(
				rescoped,
				new SetAssociatedDataSchemaSearchableMutation(ASSOCIATED_DATA_NAME, Scope.values(), TITLE, null, 400)
					.mutate(rescoped)
			);
		}

		@Test
		@DisplayName("should keep the analyzer and the length pivot of the earlier mutation unless restated")
		void shouldKeepEarlierAnalyzerAndPivotUnlessRestated() {
			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				new SetAssociatedDataSchemaSearchableMutation(ASSOCIATED_DATA_NAME, Scope.values(), TITLE, null, 40)
					.combineWith(
						Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class),
						new SetAssociatedDataSchemaSearchableMutation(
							ASSOCIATED_DATA_NAME, Scope.NO_SCOPE, null, "html-text", 400
						)
					);

			assertNotNull(result);
			assertNull(result.origin());
			assertEquals(
				new SetAssociatedDataSchemaSearchableMutation(
					ASSOCIATED_DATA_NAME, Scope.values(), TITLE, "html-text", 40
				),
				result.current()[0]
			);
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("otherAssociatedDataMutations")
		@DisplayName("should keep the analyzer and the length pivot through every other associated data mutation")
		void shouldKeepAnalyzerAndPivotThroughOtherMutation(
			@Nonnull String label, @Nonnull AssociatedDataSchemaMutation mutation
		) {
			final AssociatedDataSchemaContract configured = new SetAssociatedDataSchemaSearchableMutation(
				ASSOCIATED_DATA_NAME, new Scope[]{Scope.LIVE}, TITLE, "html-text", 400
			).mutate(existing(Scope.NO_SCOPE, null));

			final AssociatedDataSchemaContract mutated = mutation.mutate(configured);

			assertNotNull(mutated, label);
			assertEquals("html-text", mutated.getAnalyzer(), label + " dropped the analyzer");
			assertEquals(400, mutated.getLengthPivot(), label + " dropped the length pivot");
		}

	}

	@Nested
	@DisplayName("Combining with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should replace an earlier mutation of the same associated data")
		void shouldReplaceEarlierMutation() {
			final SetAssociatedDataSchemaSearchableMutation mutation =
				new SetAssociatedDataSchemaSearchableMutation(ASSOCIATED_DATA_NAME, true);
			final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class),
				new SetAssociatedDataSchemaSearchableMutation(ASSOCIATED_DATA_NAME, new Scope[]{Scope.ARCHIVED}, TITLE)
			);
			assertNotNull(result);
			assertNull(result.origin());
			assertSame(mutation, result.current()[0]);
		}

		@Test
		@DisplayName("should leave a mutation of other associated data alone")
		void shouldLeaveOtherAssociatedDataAlone() {
			assertNull(
				new SetAssociatedDataSchemaSearchableMutation(ASSOCIATED_DATA_NAME, true).combineWith(
					Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class),
					new SetAssociatedDataSchemaSearchableMutation("other", true)
				)
			);
		}

	}

	@Test
	@DisplayName("should describe the scopes and the expression")
	@Tag(CONTRACT)
	void shouldDescribeScopesAndExpression() {
		final String description = new SetAssociatedDataSchemaSearchableMutation(
			ASSOCIATED_DATA_NAME, new Scope[]{Scope.LIVE}, TITLE
		).toString();
		assertTrue(description.contains("searchable=(in scopes: [LIVE])"), description);
		assertTrue(description.contains("textExpression=$value.title"), description);
	}

}
