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

package io.evitadb.core.collection;

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestTags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The WARM_UP counterpart of {@link EntityAtomicMutationRollbackFunctionalTest}: it drives the very same failing
 * entity through a catalog that is still being bulk loaded, where writes go IN PLACE to the index delegates instead of
 * to a transaction's diff layers.
 *
 * Its job is to pin down, as executable assertions, exactly how far the warm-up path is from the atomicity the
 * transactional path already provides — so that each phase of the port can be measured against it rather than argued
 * about. The scenario is the proven one: a batch of three entities in which the middle one violates a unique
 * constraint after its index writes have already been applied.
 *
 * **The divergence with the switch off**, which the tests below still assert: the failed entity's primary key stays in
 * the collection's membership index (it is returned by a query) while its body storage part was never written
 * (fetching it yields nothing) — the same query therefore reports four products by reference and three by content.
 * Recovery is documented as "compensate on the client or rebuild the catalog" in
 * `documentation/user/en/deep-dive/bulk-vs-incremental-indexing.md`.
 *
 * Both switch positions are exercised. With {@link WarmUpSavepoint} switched on the divergence is now gone for this
 * scenario: the reference query and the body fetch agree on the same three products. That flip is this line of work's
 * acceptance criterion and this class is where it is recorded — see the on-test for exactly which structures the
 * scenario touches, and therefore what it does and does not yet prove.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Per-entity atomic mutation rollback in warm-up mode")
@Tag(TestTags.ENGINE)
@Tag(TestTags.INDEXING)
// the warm-up atomicity switch is a process-wide static and test classes in this module run concurrently in one JVM;
// @Isolated keeps a flipped switch from reaching an unrelated class
@Isolated
class EntityAtomicMutationRollbackWarmUpFunctionalTest implements EvitaTestSupport {
	private static final String ATTRIBUTE_CODE = "code";
	private TestPaths paths;
	private Evita evita;
	private boolean originalAtomicity;

	@BeforeEach
	void setUp() {
		this.originalAtomicity = WarmUpSavepoint.isEnabled();
		this.paths = createTestPaths("EntityAtomicMutationRollbackWarmUpFunctionalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);

		// define a product schema carrying a unique code and seed product #1 (code "A"). The catalog deliberately stays
		// in WARM_UP - it is never taken live - so every write below goes in place to the index delegates
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::unique)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_CODE, "A")
				);
			}
		);
	}

	@AfterEach
	void tearDown() {
		WarmUpSavepoint.setEnabled(this.originalAtomicity);
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Warm-up atomicity switched off")
	class WithoutWarmUpAtomicity {

		@Test
		@DisplayName("The failed entity leaves an orphan primary key with no body behind")
		@Tag(TestTags.ATTRIBUTE)
		void shouldLeaveOrphanPrimaryKeyBehindWhenAtomicityIsOff() {
			WarmUpSavepoint.setEnabled(false);
			runFailingBatch();

			// #3 was registered in the collection's membership index before its unique-code check threw, and nothing
			// reverted it; its body storage part is only written on the success path, so it was never stored
			assertProductReferencesAre(1, 2, 3, 4);
			assertFetchedProductsAre(1, 2, 4);
			assertProductAbsent(3);
		}

		@Test
		@DisplayName("The unique attribute index is nonetheless left consistent")
		@Tag(TestTags.ATTRIBUTE)
		void shouldKeepUniqueIndexConsistentWhenAtomicityIsOff() {
			WarmUpSavepoint.setEnabled(false);
			runFailingBatch();

			// the failure struck as the unique index rejected the duplicate, so that index never took #3's entry - the
			// damage is confined to the structures written before the check, which is what makes it easy to miss
			assertCodeResolvesTo("A", 1);
			assertCodeResolvesTo("B", 2);
			assertCodeResolvesTo("C", 4);
		}
	}

	@Nested
	@DisplayName("Warm-up atomicity switched on")
	class WithWarmUpAtomicity {

		/**
		 * Pins the reach of the mechanism as it stands, which for this scenario is complete: the reference query and
		 * the body fetch agree, so no orphan primary key is left behind.
		 *
		 * What the scenario actually exercises, and why that is the whole of it: the failing entity reaches the unique
		 * index only after its primary key has been written to the collection's membership bitmap, and
		 * `EntityIndex#upsertAttribute` runs the unique insert BEFORE the filter one, while the unique insert itself
		 * checks the value is free before writing its own tree. So the duplicate code aborts the mutation with exactly
		 * three kinds of state touched — the membership bitmap, the collection's storage diff layer, and the indexes'
		 * dirty flags — and all three journal their warm-up writes.
		 *
		 * That also says what this test does NOT prove. It never reaches a B+ tree, so a scenario that fails LATER —
		 * past the filter, sort, range or price writes — is not covered by it and is not yet recoverable either.
		 */
		@Test
		@DisplayName("The failed entity leaves no orphan primary key behind")
		@Tag(TestTags.ATTRIBUTE)
		void shouldLeaveNoOrphanPrimaryKeyWhenAtomicityIsOn() {
			WarmUpSavepoint.setEnabled(true);
			runFailingBatch();

			assertProductReferencesAre(1, 2, 4);
			assertFetchedProductsAre(1, 2, 4);
			assertProductAbsent(3);
		}

		@Test
		@DisplayName("The surrounding batch is unaffected and the catalog keeps accepting writes")
		@Tag(TestTags.ATTRIBUTE)
		void shouldKeepWritingAfterTheFailedEntityWhenAtomicityIsOn() {
			WarmUpSavepoint.setEnabled(true);
			runFailingBatch();

			// everything the batch legitimately wrote survived the bracketed failure, and the unique index is intact
			assertCodeResolvesTo("A", 1);
			assertCodeResolvesTo("B", 2);
			assertCodeResolvesTo("C", 4);

			// a later session still flushes: the rollback completed, so nothing poisoned the warm-up buffers
			EntityAtomicMutationRollbackWarmUpFunctionalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 5).setAttribute(ATTRIBUTE_CODE, "D")
					);
				}
			);
			assertCodeResolvesTo("D", 5);
		}
	}

	/**
	 * Runs the scenario in a single warm-up session: product #2 succeeds, #3 fails on the duplicate code "A" after its
	 * index writes were applied, #4 succeeds. The failure is swallowed the way a bulk loader would swallow it, so the
	 * session goes on to close and flush.
	 */
	private void runFailingBatch() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 2).setAttribute(ATTRIBUTE_CODE, "B")
				);

				assertThrows(
					UniqueValueViolationException.class,
					() -> session.upsertEntity(
						// duplicate of entity #1's code → fails after partially touching the indexes
						session.createNewEntity(Entities.PRODUCT, 3).setAttribute(ATTRIBUTE_CODE, "A")
					)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 4).setAttribute(ATTRIBUTE_CODE, "C")
				);
			}
		);
	}

	/**
	 * Asserts that a plain product query — which is answered from the collection's membership index alone, without
	 * reading a single storage part — returns exactly the supplied primary keys.
	 *
	 * @param expectedPrimaryKeys the complete set of primary keys the index is expected to hold
	 */
	private void assertProductReferencesAre(int... expectedPrimaryKeys) {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<EntityReference> references = session.queryList(
					query(collection(Entities.PRODUCT)),
					EntityReference.class
				);
				final int[] actualPrimaryKeys = references.stream()
					.mapToInt(EntityReference::getPrimaryKey)
					.sorted()
					.toArray();
				assertEquals(
					Arrays.toString(sorted(expectedPrimaryKeys)),
					Arrays.toString(actualPrimaryKeys),
					"The membership index must hold exactly the expected primary keys!"
				);
				return null;
			}
		);
	}

	/**
	 * Asserts that the same product query, this time asked to materialize the entity bodies, yields exactly the
	 * supplied primary keys. A primary key present in the index but missing a body storage part silently drops out
	 * here — which is what makes the gap between this and {@link #assertProductReferencesAre(int...)} the sharp
	 * observation of the divergence.
	 *
	 * @param expectedPrimaryKeys the complete set of primary keys expected to have a stored body
	 */
	private void assertFetchedProductsAre(int... expectedPrimaryKeys) {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<SealedEntity> entities = session.queryList(
					query(collection(Entities.PRODUCT), require(entityFetchAll())),
					SealedEntity.class
				);
				final int[] actualPrimaryKeys = entities.stream()
					.mapToInt(it -> it.getPrimaryKeyOrThrowException())
					.sorted()
					.toArray();
				assertEquals(
					Arrays.toString(sorted(expectedPrimaryKeys)),
					Arrays.toString(actualPrimaryKeys),
					"Only the entities whose body was actually stored may be materialized!"
				);
				return null;
			}
		);
	}

	/**
	 * Asserts that no entity body can be read for the given primary key.
	 *
	 * @param primaryKey the primary key that must have no stored body
	 */
	private void assertProductAbsent(int primaryKey) {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertFalse(
					session.getEntity(Entities.PRODUCT, primaryKey).isPresent(),
					"Entity #" + primaryKey + " must not be readable - its body was never stored!"
				);
				return null;
			}
		);
	}

	/**
	 * Asserts that querying the unique {@link #ATTRIBUTE_CODE} attribute for the given value resolves to exactly the
	 * expected primary key.
	 *
	 * @param code               the unique attribute value to look up
	 * @param expectedPrimaryKey the primary key the value must resolve to
	 */
	private void assertCodeResolvesTo(@Nonnull String code, int expectedPrimaryKey) {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<EntityReference> reference = session.queryOne(
					query(
						collection(Entities.PRODUCT),
						filterBy(attributeEquals(ATTRIBUTE_CODE, code))
					),
					EntityReference.class
				);
				assertTrue(reference.isPresent(), "Code `" + code + "` must resolve to an entity!");
				assertEquals(
					new EntityReference(Entities.PRODUCT, expectedPrimaryKey),
					reference.get()
				);
				return null;
			}
		);
	}

	/**
	 * Returns a sorted copy of the supplied primary keys, so the assertions do not depend on the order they were
	 * written in.
	 *
	 * @param primaryKeys the primary keys to sort
	 * @return a sorted copy
	 */
	@Nonnull
	private static int[] sorted(int... primaryKeys) {
		final int[] copy = primaryKeys.clone();
		Arrays.sort(copy);
		return copy;
	}

}
