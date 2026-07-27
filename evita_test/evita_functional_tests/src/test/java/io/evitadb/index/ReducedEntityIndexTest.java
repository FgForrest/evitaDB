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

package io.evitadb.index;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Locale;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;

/**
 * Tests for {@link ReducedEntityIndex}. Extends {@link AbstractReducedEntityIndexTest} to inherit
 * common reduced entity index behavior tests (reference key resolution, hierarchy guards,
 * partitioning assertions, locale removal) and adds tests specific to ReducedEntityIndex:
 * constructor type validation and STM commit/rollback.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ReducedEntityIndex")
@Tag(INDEXING)
@Tag(MANAGEMENT)
class ReducedEntityIndexTest extends AbstractReducedEntityIndexTest<ReducedEntityIndex> {

	private static final int INDEX_PK = 1;
	private static final String REFERENCE_NAME = "CATEGORY";
	private static final int REFERENCED_PK = 1;

	@Nonnull
	@Override
	protected ReducedEntityIndex createInstance() {
		final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
			new ReferenceKey(REFERENCE_NAME, REFERENCED_PK)
		);
		return new ReducedEntityIndex(
			INDEX_PK,
			ENTITY_TYPE,
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, rrk)
		);
	}

	/**
	 * Tests that the constructor rejects invalid {@link EntityIndexType} values and accepts only
	 * {@link EntityIndexType#REFERENCED_ENTITY}.
	 */
	@Nested
	@DisplayName("Constructor type validation")
	class ConstructorTypeValidationTest {

		@Test
		@DisplayName("should accept REFERENCED_ENTITY type")
		void shouldAcceptReferencedEntityType() {
			final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
				new ReferenceKey(REFERENCE_NAME, REFERENCED_PK)
			);
			final ReducedEntityIndex created = new ReducedEntityIndex(
				INDEX_PK,
				ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, rrk)
			);

			assertNotNull(created);
			assertEquals(EntityIndexType.REFERENCED_ENTITY, created.getIndexKey().type());
		}

		@Test
		@DisplayName("should reject GLOBAL type")
		void shouldRejectGlobalType() {
			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> new ReducedEntityIndex(
					INDEX_PK,
					ENTITY_TYPE,
					new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
				)
			);
			assertTrue(
				exception.getMessage().contains("REFERENCED_ENTITY"),
				"Error message should mention the expected type"
			);
		}

		@Test
		@DisplayName("should reject REFERENCED_ENTITY_TYPE type")
		void shouldRejectReferencedEntityTypeType() {
			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> new ReducedEntityIndex(
					INDEX_PK,
					ENTITY_TYPE,
					new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "CATEGORY")
				)
			);
			assertTrue(
				exception.getMessage().contains("REFERENCED_ENTITY"),
				"Error message should mention the expected type"
			);
		}

		@Test
		@DisplayName("should reject REFERENCED_GROUP_ENTITY type")
		void shouldRejectReferencedGroupEntityType() {
			final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
				new ReferenceKey(REFERENCE_NAME, REFERENCED_PK)
			);
			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> new ReducedEntityIndex(
					INDEX_PK,
					ENTITY_TYPE,
					new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE, rrk)
				)
			);
			assertTrue(
				exception.getMessage().contains("REFERENCED_ENTITY"),
				"Error message should mention the expected type"
			);
		}
	}

	/**
	 * Tests for STM transactional commit behavior specific to {@link ReducedEntityIndex}.
	 */
	@Nested
	@DisplayName("STM commit")
	class StmCommitTest {

		@Test
		@DisplayName("should commit PK insert and preserve in new instance")
		void shouldCommitPkInsert() {
			assertStateAfterCommit(
				ReducedEntityIndexTest.this.index,
				original -> {
					original.insertPrimaryKeyIfMissing(10);
					original.insertPrimaryKeyIfMissing(20);
				},
				(original, committed) -> {
					// original should still be empty (no PKs were committed to it)
					assertTrue(original.getAllPrimaryKeys().isEmpty());
					// committed should have the PKs
					assertNotNull(committed);
					assertTrue(committed.getAllPrimaryKeys().contains(10));
					assertTrue(committed.getAllPrimaryKeys().contains(20));
				}
			);
		}

		@Test
		@DisplayName("should commit PK removal")
		void shouldCommitPkRemoval() {
			// pre-populate outside transaction
			ReducedEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10);

			assertStateAfterCommit(
				ReducedEntityIndexTest.this.index,
				original -> original.removePrimaryKey(10),
				(original, committed) -> {
					// original still has PK 10
					assertTrue(original.getAllPrimaryKeys().contains(10));
					// committed should not
					assertNotNull(committed);
					assertFalse(committed.getAllPrimaryKeys().contains(10));
				}
			);
		}

		@Test
		@DisplayName("should increment version when dirty")
		void shouldIncrementVersionWhenDirty() {
			assertStateAfterCommit(
				ReducedEntityIndexTest.this.index,
				original -> original.insertPrimaryKeyIfMissing(10),
				(original, committed) -> {
					assertEquals(1, original.version());
					assertNotNull(committed);
					assertEquals(2, committed.version());
				}
			);
		}

		@Test
		@DisplayName("should not increment version when clean")
		void shouldNotIncrementVersionWhenClean() {
			assertStateAfterCommit(
				ReducedEntityIndexTest.this.index,
				original -> {
					// no mutations
				},
				(original, committed) -> {
					assertEquals(1, original.version());
					assertNotNull(committed);
					assertEquals(1, committed.version());
				}
			);
		}

		@Test
		@DisplayName("should commit language changes")
		void shouldCommitLanguageChanges() {
			final EntitySchemaContract schema = createEvolvingSchema();

			assertStateAfterCommit(
				ReducedEntityIndexTest.this.index,
				original -> original.upsertLanguage(Locale.ENGLISH, 10, schema),
				(original, committed) -> {
					assertFalse(original.getLanguages().contains(Locale.ENGLISH));
					assertNotNull(committed);
					assertTrue(committed.getLanguages().contains(Locale.ENGLISH));
				}
			);
		}
	}

	/**
	 * Tests for STM transactional rollback behavior specific to {@link ReducedEntityIndex}.
	 */
	@Nested
	@DisplayName("STM rollback")
	class StmRollbackTest {

		@Test
		@DisplayName("should discard PK insert on rollback")
		void shouldDiscardPkInsertOnRollback() {
			assertStateAfterRollback(
				ReducedEntityIndexTest.this.index,
				original -> original.insertPrimaryKeyIfMissing(10),
				(original, committed) -> {
					assertTrue(original.getAllPrimaryKeys().isEmpty());
					assertNull(committed);
				}
			);
		}

		@Test
		@DisplayName("should discard language upsert on rollback")
		void shouldDiscardLanguageOnRollback() {
			final EntitySchemaContract schema = createEvolvingSchema();

			assertStateAfterRollback(
				ReducedEntityIndexTest.this.index,
				original -> original.upsertLanguage(Locale.ENGLISH, 10, schema),
				(original, committed) -> {
					assertFalse(original.getLanguages().contains(Locale.ENGLISH));
					assertNull(committed);
				}
			);
		}
	}

	/**
	 * Tests for {@link ReducedEntityIndex#toString()} output format.
	 */
	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should return descriptive string containing class name and index key")
		void shouldReturnDescriptiveToString() {
			final String result = ReducedEntityIndexTest.this.index.toString();

			assertNotNull(result);
			assertTrue(
				result.startsWith("ReducedEntityIndex"),
				"toString should start with class name, but was: " + result
			);
		}
	}

}
