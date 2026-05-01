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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics;

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.exception.HierarchyNotIndexedException;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor.ProcessingScope;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for [AbstractHierarchyTranslator.resolveSingleHierarchicalScope] verifying scope-resolution rules
 * for hierarchy statistics planning: exactly one scope must be requested, the schema's hierarchy must be
 * indexed in that scope, and any error must reference the schema actually being inspected.
 *
 * The test class lives in the same package as the production class so it can call the package-protected
 * (`protected static`) helper directly without reflection.
 *
 * @author evitaDB
 */
@DisplayName("AbstractHierarchyTranslator — resolveSingleHierarchicalScope")
class AbstractHierarchyTranslatorTest {

	private static final String QUERIED_SCHEMA_NAME = "Product";
	private static final String REFERENCED_SCHEMA_NAME = "Category";

	@Nested
	@DisplayName("Happy path")
	class HappyPathTest {

		@Test
		@DisplayName("should return single scope when exactly one is requested and indexed")
		void shouldReturnSingleScopeWhenExactlyOneRequestedAndIndexed() {
			// given a schema whose hierarchy is indexed in the LIVE scope
			final EntitySchemaContract schema = mockSchema(QUERIED_SCHEMA_NAME, Scope.LIVE, true);
			final ProcessingScope processingScope = mockProcessingScopeWith(Set.of(Scope.LIVE));

			// when the helper resolves the single hierarchical scope
			final Scope resolved = AbstractHierarchyTranslator.resolveSingleHierarchicalScope(
				processingScope, schema
			);

			// then the requested scope is returned
			assertSame(Scope.LIVE, resolved);
		}
	}

	@Nested
	@DisplayName("Error handling")
	class ErrorHandlingTest {

		@Test
		@DisplayName("should throw EvitaInvalidUsageException when multiple scopes are requested")
		void shouldThrowEvitaInvalidUsageExceptionWhenMultipleScopesRequested() {
			// given a schema and a processing scope that demands two scopes simultaneously
			final EntitySchemaContract schema = mockSchema(QUERIED_SCHEMA_NAME, Scope.LIVE, true);
			final ProcessingScope processingScope = mockProcessingScopeWith(EnumSet.allOf(Scope.class));

			// when resolving the single hierarchical scope, then the helper must reject the multi-scope request
			final EvitaInvalidUsageException ex = assertThrows(
				EvitaInvalidUsageException.class,
				() -> AbstractHierarchyTranslator.resolveSingleHierarchicalScope(processingScope, schema)
			);

			final String message = ex.getMessage();
			assertTrue(
				message.contains(QUERIED_SCHEMA_NAME),
				() -> "Exception message should reference the schema name `" + QUERIED_SCHEMA_NAME + "`, was: " + message
			);
			assertTrue(
				message.contains("two distinct trees"),
				() -> "Exception message should mention `two distinct trees`, was: " + message
			);
		}

		@Test
		@DisplayName("should throw HierarchyNotIndexedException when scope is not indexed")
		void shouldThrowHierarchyNotIndexedExceptionWhenScopeNotIndexed() {
			// given a schema whose hierarchy is NOT indexed in the requested scope
			final EntitySchemaContract schema = mockSchema(QUERIED_SCHEMA_NAME, Scope.LIVE, false);
			final ProcessingScope processingScope = mockProcessingScopeWith(Set.of(Scope.LIVE));

			// when resolving the single hierarchical scope, then the helper must reject the un-indexed scope
			final HierarchyNotIndexedException ex = assertThrows(
				HierarchyNotIndexedException.class,
				() -> AbstractHierarchyTranslator.resolveSingleHierarchicalScope(processingScope, schema)
			);

			final String message = ex.getMessage();
			assertTrue(
				message.contains(QUERIED_SCHEMA_NAME),
				() -> "Exception message should reference the schema name `" + QUERIED_SCHEMA_NAME + "`, was: " + message
			);
		}

		@Test
		@DisplayName("should reference referenced-entity schema (not queried schema) for hierarchy-of-reference use case")
		void shouldUseSchemaPassedAsArgumentInExceptionForHierarchyOfReferenceUseCase() {
			// REGRESSION test: previously HierarchyOfReferenceTranslator passed the *queried* schema into the
			// exception even though the un-indexed hierarchy belonged to the *referenced* entity schema. The
			// helper now uses whichever schema is passed as the argument — and must continue to do so for
			// hierarchy-of-reference translators that walk the referenced entity's hierarchy.
			final EntitySchemaContract queriedSchema = mockSchema(QUERIED_SCHEMA_NAME, Scope.LIVE, true);
			final EntitySchemaContract referencedSchema = mockSchema(REFERENCED_SCHEMA_NAME, Scope.LIVE, false);
			final ProcessingScope processingScope = mockProcessingScopeWith(Set.of(Scope.LIVE));

			// sanity check: the queried schema IS indexed, so resolving against it must succeed — proving that the
			// helper's verdict is driven solely by the schema argument it is handed
			assertEquals(
				Scope.LIVE,
				AbstractHierarchyTranslator.resolveSingleHierarchicalScope(processingScope, queriedSchema),
				"Resolving against the indexed queried schema should succeed and return the requested scope"
			);

			// when resolving the hierarchical scope against the REFERENCED entity schema
			final HierarchyNotIndexedException ex = assertThrows(
				HierarchyNotIndexedException.class,
				() -> AbstractHierarchyTranslator.resolveSingleHierarchicalScope(processingScope, referencedSchema)
			);

			// then the exception must reference the referenced entity schema, not the queried one
			final String message = ex.getMessage();
			assertTrue(
				message.contains(REFERENCED_SCHEMA_NAME),
				() -> "Exception should mention referenced schema `" + REFERENCED_SCHEMA_NAME + "`, was: " + message
			);
			assertEquals(
				-1, message.indexOf(QUERIED_SCHEMA_NAME),
				"Exception must not mention the queried schema `" + QUERIED_SCHEMA_NAME + "` — that was the bug"
			);
		}
	}

	/**
	 * Builds a Mockito-backed [EntitySchemaContract] stub whose `getName()` and `isHierarchyIndexedInScope(scope)`
	 * answers are wired to the supplied values. Every other method returns the Mockito default — sufficient for
	 * tests that only inspect these two answers.
	 *
	 * @param name           name reported by the schema
	 * @param indexedScope   scope that should report as either indexed or not, controlled by `indexed`
	 * @param indexed        whether the named scope should report as having an indexed hierarchy
	 * @return the configured mock schema
	 */
	@Nonnull
	private static EntitySchemaContract mockSchema(
		@Nonnull String name,
		@Nonnull Scope indexedScope,
		boolean indexed
	) {
		final EntitySchemaContract schema = mock(EntitySchemaContract.class);
		when(schema.getName()).thenReturn(name);
		when(schema.isHierarchyIndexedInScope(indexedScope)).thenReturn(indexed);
		return schema;
	}

	/**
	 * Builds a Mockito-backed [ProcessingScope] stub whose `getScopes()` returns the supplied set. We mock here
	 * (rather than instantiating the record directly) because the record would otherwise drag in a
	 * `Deque<Set<Scope>>` and supplier wiring that is irrelevant to the helper under test.
	 *
	 * @param scopes the scope set the processing scope should report
	 * @return the configured mock processing scope
	 */
	@Nonnull
	private static ProcessingScope mockProcessingScopeWith(@Nonnull Set<Scope> scopes) {
		final ProcessingScope processingScope = mock(ProcessingScope.class);
		when(processingScope.getScopes()).thenReturn(scopes);
		return processingScope;
	}

}
