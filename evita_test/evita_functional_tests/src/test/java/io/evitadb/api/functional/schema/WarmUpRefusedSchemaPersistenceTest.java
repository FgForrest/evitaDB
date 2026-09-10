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

package io.evitadb.api.functional.schema;

import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.exception.ConflictingEngineMutationException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaAcceleratedMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.dataType.Scope;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards #1466 - a schema change refused by catalog-schema validation in a `WARMING_UP` catalog must not reach the
 * disk, and must not load silently on the next open.
 *
 * The refusal alone does not achieve that, because the refusal and the write are performed by different actors.
 * {@code EvitaSession#closeInternal} validates before it flushes, so the refused session performs no write of its
 * own - but by then {@link io.evitadb.core.collection.EntityCollection#updateSchema} has already exchanged the
 * invalid schema into the running catalog and put an {@code EntitySchemaStoragePart} into the data-store buffer, and
 * warm-up has no undo for that exchange. Every later publisher would therefore write it out:
 * {@code Catalog#terminateInternally} on an ordinary {@code Evita#close()}, and any subsequent session close, a
 * read-only one included. What stops all of them at once is the unpublishable barrier the refusal raises - see
 * {@code Catalog#markUnpublishableDueToInvalidSchema}.
 *
 * The guarantee is not specific to one validation rule - {@code SealedCatalogSchema#validate()} fans out to every
 * entity and reference schema, so every rule reachable from there has the same exposure. Three independent rules are
 * therefore exercised below: the attribute filter-accelerator rule (the cheapest one to trigger from a raw mutation),
 * the reference rule that requires a managed referenced entity type to exist, and the reflected-reference rule that
 * requires the reference it reflects to resolve.
 *
 * The refusals also come from both directions, because the exposure does. An *additive* change declares something the
 * catalog cannot satisfy; a *removal* takes away what an existing declaration relied on, and it reaches the same
 * validation from the other side - see {@link InterlinkedCollectionRemoval}. Removal carries one hazard the additive
 * cases do not: it parks the collection's data file for retirement, so a publication that should not have happened
 * would not merely write a schema, it would unlink a file the previous bootstrap record still names.
 *
 * Each refusal is asserted on its **message**, never on the exception type alone - several unrelated refusals throw
 * `InvalidSchemaMutationException` and would satisfy a bare `assertThrows` while proving something else. And each
 * case is paired with a control that pushes the *same* schema element through legitimately and finds it on the far
 * side of a reopen, so an empty assertion cannot pass merely because the reopened schema was read wrongly. The
 * reopen is load-bearing twice over: it is the only honest way to ask what reached the disk, and it fails outright
 * if the close that precedes it did not release the engine's folder lock.
 *
 * {@link AttributeFilterAcceleratorRefusalTest} pins the same accelerator case from the schema API's side.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Schema refused in warm-up must not reach the disk")
@Tag(ENGINE)
@Tag(STORAGE)
@Tag(SCHEMA)
class WarmUpRefusedSchemaPersistenceTest implements EvitaTestSupport {

	private static final String ATTRIBUTE_CODE = "code";
	private static final String REFERENCE_BRAND = "brand";
	/** An entity type deliberately never defined in the catalog, so a managed reference to it cannot validate. */
	private static final String NON_EXISTING_ENTITY_TYPE = "nonExistingEntityType";
	/** Name of the reflected reference declared on the category collection. */
	private static final String REFERENCE_REFLECTED_PRODUCTS = "productsInCategory";
	/** Name of the reference on the product collection that the reflected reference above points at. */
	private static final String REFERENCE_CATEGORY = "category";
	/** A reference name deliberately never declared, so a reflected reference to it cannot resolve. */
	private static final String NON_EXISTING_REFERENCE_NAME = "nonExistingReference";
	/** Primary key of the single category the interlinked pair is wired through. */
	private static final int CATEGORY_PK = 1;
	/** Primary key of the entity written by a session that closes successfully, and therefore publishes. */
	private static final int PUBLISHED_PRODUCT_PK = 1;
	/** Primary key of the entity written by the session whose close is refused, and which therefore never publishes. */
	private static final int DISCARDED_PRODUCT_PK = 2;
	/**
	 * Upper bound on how long any single asynchronous catalog lifecycle step is waited for. Generous on purpose -
	 * a latch returns the instant the work completes, so the bound is only ever paid by a genuine hang, and a
	 * loaded machine can only push a positive wait toward expiry (`.claude/rules/testing.md`).
	 */
	private static final long AWAIT_BUDGET_MILLIS = 30_000L;
	/** Backoff between two attempts at an operation the engine refuses while another one is still in flight. */
	private static final long RETRY_BACKOFF_MILLIS = 20L;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("WarmUpRefusedSchemaPersistence");
		this.evita = new Evita(configuration());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("refused by the attribute filter-accelerator rule")
	class AcceleratorRule {

		@Test
		@Tag(ATTRIBUTE)
		@DisplayName("should not persist an accelerator declared on an attribute with no filter index")
		void shouldNotPersistAnAcceleratorDeclaredOnAnAttributeWithNoFilterIndex() {
			WarmUpRefusedSchemaPersistenceTest.this.defineProductWithPlainAttribute();

			// the builder cannot express this state at all - it refuses the chain while assembling it - so the
			// mutation has to be submitted raw. Every other route into the schema (gRPC, REST, GraphQL, the WAL)
			// carries mutations the same way, which is what makes this the shape worth pinning
			final InvalidSchemaMutationException exception =
				CatalogUnpublishableBarrierAssertions.assertRefusalRaisesTheBarrier(
					WarmUpRefusedSchemaPersistenceTest.this.evita, TEST_CATALOG,
					InvalidSchemaMutationException.class,
					() -> WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.updateEntitySchema(
								new ModifyEntitySchemaMutation(
									Entities.PRODUCT,
									new SetAttributeSchemaAcceleratedMutation(
										ATTRIBUTE_CODE,
										new ScopedAttributeFilterAccelerators(
											Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH
										)
									)
								)
							);
						}
					)
				);
			assertTrue(
				exception.getMessage().contains(AttributeFilterAccelerator.SUBSTRING_SEARCH.name()),
				() -> "The refusal must name the accelerator it refuses, but was: " + exception.getMessage()
			);
			assertTrue(
				exception.getMessage().contains("no filter index"),
				() -> "The refusal must name the missing filter index, but was: " + exception.getMessage()
			);

			final EntitySchemaContract reopenedSchema = WarmUpRefusedSchemaPersistenceTest.this.reopenAndReadSchema();
			assertEquals(
				Set.of(),
				reopenedSchema.getAttribute(ATTRIBUTE_CODE).orElseThrow().getAcceleratorsInScope(Scope.LIVE),
				"The refused accelerator must not survive the close that follows the refusal"
			);
		}

		@Test
		@Tag(ATTRIBUTE)
		@DisplayName("should persist an accelerator the same route accepts")
		void shouldPersistAnAcceleratorTheSameRouteAccepts() {
			// the control for the case above: the very same raw mutation, on an attribute that *is* filterable, must
			// come back from a reopen. Without it an empty accelerator set would also be the answer to a schema that
			// was read wrongly, or to an attribute that never carried the declaration in the first place
			WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
						.updateVia(session);
				}
			);
			WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.updateEntitySchema(
						new ModifyEntitySchemaMutation(
							Entities.PRODUCT,
							new SetAttributeSchemaAcceleratedMutation(
								ATTRIBUTE_CODE,
								new ScopedAttributeFilterAccelerators(
									Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH
								)
							)
						)
					);
				}
			);

			final EntitySchemaContract reopenedSchema = WarmUpRefusedSchemaPersistenceTest.this.reopenAndReadSchema();
			assertEquals(
				Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH),
				reopenedSchema.getAttribute(ATTRIBUTE_CODE).orElseThrow().getAcceleratorsInScope(Scope.LIVE),
				"An accepted accelerator must survive the reopen - otherwise the assertion above proves nothing"
			);
		}
	}

	@Nested
	@DisplayName("refused by the managed-reference rule")
	class ManagedReferenceRule {

		@Test
		@Tag(REFERENCE)
		@DisplayName("should not persist a managed reference to an entity type that does not exist")
		void shouldNotPersistAManagedReferenceToAnEntityTypeThatDoesNotExist() {
			// a second, entirely unrelated rule from the same `validate()` fan-out - the defect is a property of the
			// warm-up write path, not of the accelerator rule that first exposed it
			WarmUpRefusedSchemaPersistenceTest.this.defineProductWithPlainAttribute();

			final InvalidSchemaMutationException exception =
				CatalogUnpublishableBarrierAssertions.assertRefusalRaisesTheBarrier(
					WarmUpRefusedSchemaPersistenceTest.this.evita, TEST_CATALOG,
					InvalidSchemaMutationException.class,
					() -> WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.updateEntitySchema(
								new ModifyEntitySchemaMutation(
									Entities.PRODUCT,
									new CreateReferenceSchemaMutation(
										REFERENCE_BRAND, null, null, Cardinality.ZERO_OR_ONE,
										NON_EXISTING_ENTITY_TYPE, true,
										null, false,
										false, false
									)
								)
							);
						}
					)
				);
			// pinned to the wording of the rule under test, not merely to the exception type:
			// `CreateReferenceSchemaMutation` has refusals of its own that throw the same type, and a bare
			// `assertThrows` would accept any of them
			assertTrue(
				exception.getMessage().contains(NON_EXISTING_ENTITY_TYPE)
					&& exception.getMessage().contains("is not present in catalog"),
				() -> "The refusal must name the missing entity type, but was: " + exception.getMessage()
			);

			final EntitySchemaContract reopenedSchema = WarmUpRefusedSchemaPersistenceTest.this.reopenAndReadSchema();
			assertFalse(
				reopenedSchema.getReference(REFERENCE_BRAND).isPresent(),
				"The refused reference must not survive the close that follows the refusal"
			);
		}

		@Test
		@Tag(REFERENCE)
		@DisplayName("should persist a managed reference the same route accepts")
		void shouldPersistAManagedReferenceTheSameRouteAccepts() {
			// the control for the case above - the same raw mutation against an entity type that does exist
			WarmUpRefusedSchemaPersistenceTest.this.defineProductWithPlainAttribute();
			WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND)
						.withoutGeneratedPrimaryKey()
						.updateVia(session);
				}
			);
			WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.updateEntitySchema(
						new ModifyEntitySchemaMutation(
							Entities.PRODUCT,
							new CreateReferenceSchemaMutation(
								REFERENCE_BRAND, null, null, Cardinality.ZERO_OR_ONE,
								Entities.BRAND, true,
								null, false,
								false, false
							)
						)
					);
				}
			);

			final EntitySchemaContract reopenedSchema = WarmUpRefusedSchemaPersistenceTest.this.reopenAndReadSchema();
			assertTrue(
				reopenedSchema.getReference(REFERENCE_BRAND).isPresent(),
				"An accepted reference must survive the reopen - otherwise the assertion above proves nothing"
			);
		}
	}

	@Nested
	@DisplayName("refused by the unresolved reflected-reference rule")
	class ReflectedReferenceRule {

		@Test
		@Tag(REFERENCE)
		@DisplayName("should not persist a reflected reference whose target reference does not exist")
		void shouldNotPersistAReflectedReferenceWhoseTargetReferenceDoesNotExist() {
			// a THIRD rule from the same `validate()` fan-out, on the reflected-reference machinery this time - the
			// part of the schema whose validation reaches getters that can refuse to answer at all. Asserted on
			// `EvitaInvalidUsageException` because that is the family the refusal belongs to; measured, it arrives
			// as the `SchemaAlteringException` subtype, so this case does NOT on its own justify the wide catch at
			// the barrier - see the comment at `EvitaSession#closeInternal` for what does and does not
			WarmUpRefusedSchemaPersistenceTest.this.defineProductWithPlainAttribute();

			CatalogUnpublishableBarrierAssertions.assertRefusalRaisesTheBarrier(
				WarmUpRefusedSchemaPersistenceTest.this.evita, TEST_CATALOG,
				EvitaInvalidUsageException.class,
				() -> WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(Entities.CATEGORY)
							.withoutGeneratedPrimaryKey()
							.withReflectedReferenceToEntity(
								REFERENCE_REFLECTED_PRODUCTS, Entities.PRODUCT, NON_EXISTING_REFERENCE_NAME,
								whichIs -> whichIs.withAttributesInherited()
							)
							.updateVia(session);
					}
				)
			);

			final EntitySchemaContract reopenedSchema =
				WarmUpRefusedSchemaPersistenceTest.this.reopenAndReadSchema();
			assertFalse(
				reopenedSchema.getReference(REFERENCE_REFLECTED_PRODUCTS).isPresent(),
				"The refused reflected reference must not survive the close that follows the refusal"
			);
		}

		@Test
		@Tag(REFERENCE)
		@DisplayName("should persist a reflected reference whose target reference exists")
		void shouldPersistAReflectedReferenceWhoseTargetReferenceExists() {
			// the control for the case above - the same reflected reference, against a target that resolves
			WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.CATEGORY)
						.withoutGeneratedPrimaryKey()
						.updateVia(session);
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withReferenceToEntity(
							REFERENCE_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
							whichIs -> whichIs.indexedForFilteringAndPartitioning()
						)
						.updateVia(session);
				}
			);
			WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntitySchemaOrThrow(Entities.CATEGORY)
						.openForWrite()
						.withReflectedReferenceToEntity(
							REFERENCE_REFLECTED_PRODUCTS, Entities.PRODUCT, REFERENCE_CATEGORY,
							whichIs -> whichIs.withAttributesInherited()
						)
						.updateVia(session);
				}
			);

			WarmUpRefusedSchemaPersistenceTest.this.reopenAndReadSchema();
			WarmUpRefusedSchemaPersistenceTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(
						session.getEntitySchemaOrThrow(Entities.CATEGORY)
							.getReference(REFERENCE_REFLECTED_PRODUCTS).isPresent(),
						"An accepted reflected reference must survive the reopen - otherwise the assertion above " +
							"proves nothing"
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("removal of one side of an interlinked pair")
	class InterlinkedCollectionRemoval {

		@Test
		@Tag(REFERENCE)
		@DisplayName("should not persist the removal of the collection a reflected reference targets")
		void shouldNotPersistTheRemovalOfTheCollectionAReflectedReferenceTargets() {
			// removal reaches the same `validate()` fan-out as every additive case above, but from the other side:
			// nothing was declared, the target of an existing declaration was taken away. `Catalog#removeEntitySchema`
			// drops the collection from the live schema accessor BEFORE it asks `flushMidSessionIfSchemaValid`, so the
			// mid-session publication is skipped and the refusal lands on the session close.
			//
			// CALIBRATION - this case does NOT pin the skip, and must not be read as doing so. Measured by disabling
			// the `isSchemaValid()` guard: this test still passes, because a mid-session flush here is a no-op for a
			// second, independent reason. `Catalog#flush` derives `changeOccurred` from the CATALOG schema version,
			// and `updateSchema` exchanges that version only after `removeEntitySchema` has returned - so at the
			// moment of the flush no version has moved and the removed collection is already out of the map that
			// would report one. The sibling that does pin the skip is
			// `shouldNotPublishTheHalfTornPairWhenTheSameSessionThenDefinesAnotherCollection`, where the later
			// collection definition supplies the change the flush needs
			WarmUpRefusedSchemaPersistenceTest.this.defineInterlinkedProductAndCategory();
			final List<String> dataFilesBefore =
				WarmUpRefusedSchemaPersistenceTest.this.listCollectionDataFileNames();
			assertEquals(
				2, dataFilesBefore.size(),
				() -> "Both collections must have a data file before the removal, but was: " + dataFilesBefore
			);

			final InvalidSchemaMutationException exception =
				CatalogUnpublishableBarrierAssertions.assertRefusalRaisesTheBarrier(
					WarmUpRefusedSchemaPersistenceTest.this.evita, TEST_CATALOG,
					InvalidSchemaMutationException.class,
					() -> WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.deleteCollection(Entities.PRODUCT);
						}
					)
				);
			assertTrue(
				exception.getMessage().contains(REFERENCE_REFLECTED_PRODUCTS),
				() -> "The refusal must name the reflected reference left without a target, but was: " +
					exception.getMessage()
			);

			WarmUpRefusedSchemaPersistenceTest.this.reopenCatalog();
			assertEquals(
				Set.of(Entities.CATEGORY, Entities.PRODUCT),
				WarmUpRefusedSchemaPersistenceTest.this.readEntityTypes(),
				"A removal the close refuses must not survive that close"
			);
			// the durability half, which the schema assertion above cannot see: the removal also parked the
			// collection's data file for retirement, and a round that never publishes must DROP that retirement -
			// the file is still named by the bootstrap record this very reopen followed
			assertEquals(
				dataFilesBefore,
				WarmUpRefusedSchemaPersistenceTest.this.listCollectionDataFileNames(),
				"A refused removal must not unlink the data file the published bootstrap record still names"
			);
		}

		@Test
		@Tag(REFERENCE)
		@DisplayName("should not persist the removal of the collection a managed reference targets")
		void shouldNotPersistTheRemovalOfTheCollectionAManagedReferenceTargets() {
			// the mirror order, and a different rule: dropping the reflecting side leaves the ordinary managed
			// reference on the other side pointing at an entity type that is no longer in the catalog schema, which
			// `ReferenceSchema#validate` refuses rather than `ReflectedReferenceSchema#validate`
			WarmUpRefusedSchemaPersistenceTest.this.defineInterlinkedProductAndCategory();

			final InvalidSchemaMutationException exception =
				CatalogUnpublishableBarrierAssertions.assertRefusalRaisesTheBarrier(
					WarmUpRefusedSchemaPersistenceTest.this.evita, TEST_CATALOG,
					InvalidSchemaMutationException.class,
					() -> WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.deleteCollection(Entities.CATEGORY);
						}
					)
				);
			assertTrue(
				exception.getMessage().contains(Entities.CATEGORY),
				() -> "The refusal must name the entity type that is gone, but was: " + exception.getMessage()
			);

			WarmUpRefusedSchemaPersistenceTest.this.reopenCatalog();
			assertEquals(
				Set.of(Entities.CATEGORY, Entities.PRODUCT),
				WarmUpRefusedSchemaPersistenceTest.this.readEntityTypes(),
				"A removal the close refuses must not survive that close, whichever side of the pair it dropped"
			);
		}

		@Test
		@Tag(REFERENCE)
		@DisplayName("should not publish the half-torn pair when the same session then defines another collection")
		void shouldNotPublishTheHalfTornPairWhenTheSameSessionThenDefinesAnotherCollection() {
			// this is the case `flushMidSessionIfSchemaValid` exists for, and the one where skipping the flush is
			// load-bearing rather than merely tidy. A mid-session publication here would write a bootstrap record
			// naming a catalog header the removed collection is no longer in, AND release the retirement parked for
			// its data file - unlinking a file the previous record still named. The barrier raised at the close
			// afterwards cannot take either of those back.
			//
			// CALIBRATED: disabling the `isSchemaValid()` guard in `Catalog#flushMidSessionIfSchemaValid` fails this
			// test, and the reopened catalog then holds `[CATEGORY, BRAND]` - the removal published and the product
			// collection is gone from the disk. Re-measure that if the guard or `Catalog#flush`'s change detection is
			// ever reworked; the sibling cases above pass either way and will not notice
			WarmUpRefusedSchemaPersistenceTest.this.defineInterlinkedProductAndCategory();
			final List<String> dataFilesBefore =
				WarmUpRefusedSchemaPersistenceTest.this.listCollectionDataFileNames();

			CatalogUnpublishableBarrierAssertions.assertRefusalRaisesTheBarrier(
				WarmUpRefusedSchemaPersistenceTest.this.evita, TEST_CATALOG,
				InvalidSchemaMutationException.class,
				() -> WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.deleteCollection(Entities.PRODUCT);
						// the mid-session publication, in the very same session as the removal that broke the schema
						session.defineEntitySchema(Entities.BRAND)
							.withoutGeneratedPrimaryKey()
							.updateVia(session);
					}
				)
			);

			WarmUpRefusedSchemaPersistenceTest.this.reopenCatalog();
			assertEquals(
				Set.of(Entities.CATEGORY, Entities.PRODUCT),
				WarmUpRefusedSchemaPersistenceTest.this.readEntityTypes(),
				"A collection defined after the removal must not publish the half-torn pair with it"
			);
			// the assertion is that nothing was UNLINKED, not that the folder came back unchanged. The collection
			// this session defined created its own data file on the way, and that file survives the refusal - no
			// published bootstrap record names it, which makes it inert dead space that costs disk and never
			// correctness (`.claude/rules/durability-model.md`). An unlink is the other direction, and is real damage
			assertTrue(
				WarmUpRefusedSchemaPersistenceTest.this.listCollectionDataFileNames()
					.containsAll(dataFilesBefore),
				() -> "A collection defined after the removal must not release the retirement the removal parked, " +
					"but the folder no longer holds all of " + dataFilesBefore
			);
		}

		@Test
		@Tag(REFERENCE)
		@DisplayName("should persist the removal of both interlinked collections made in one session")
		void shouldPersistTheRemovalOfBothInterlinkedCollectionsMadeInOneSession() {
			assertBothSidesCanBeRemovedInOneSession(Entities.PRODUCT, Entities.CATEGORY);
		}

		@Test
		@Tag(REFERENCE)
		@DisplayName("should persist the removal of both interlinked collections dropped in the opposite order")
		void shouldPersistTheRemovalOfBothInterlinkedCollectionsDroppedInTheOppositeOrder() {
			assertBothSidesCanBeRemovedInOneSession(Entities.CATEGORY, Entities.PRODUCT);
		}

		/**
		 * The control for the three refusals above, and the guarantee they must not be read as contradicting: a
		 * circular pair CAN be dismantled, provided both removals happen inside one session.
		 *
		 * Neither order validates halfway through - whichever side goes first leaves the other one dangling - so this
		 * passes only because the skipped mid-session publication defers the decision to the close, by which point the
		 * schema is whole again. Without the control, every assertion above would also be satisfied by an engine that
		 * simply refused to drop a referenced collection at all.
		 *
		 * @param first  entity type to drop first; must not be null
		 * @param second entity type to drop second; must not be null
		 */
		private void assertBothSidesCanBeRemovedInOneSession(@Nonnull String first, @Nonnull String second) {
			WarmUpRefusedSchemaPersistenceTest.this.defineInterlinkedProductAndCategory();
			assertEquals(
				2, WarmUpRefusedSchemaPersistenceTest.this.listCollectionDataFileNames().size(),
				"Both collections must have a data file before the removal"
			);

			WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteCollection(first);
					session.deleteCollection(second);
				}
			);
			assertEquals(
				CatalogState.WARMING_UP,
				WarmUpRefusedSchemaPersistenceTest.this.evita.getCatalogState(TEST_CATALOG).orElse(null),
				"A session that leaves the schema whole must close without raising the barrier"
			);

			WarmUpRefusedSchemaPersistenceTest.this.reopenCatalog();
			assertEquals(
				Set.of(),
				WarmUpRefusedSchemaPersistenceTest.this.readEntityTypes(),
				"Both removals must survive the close that publishes them"
			);
			assertEquals(
				List.of(),
				WarmUpRefusedSchemaPersistenceTest.this.listCollectionDataFileNames(),
				"The record that superseded the pair must release both parked retirements"
			);
		}
	}

	@Nested
	@DisplayName("publication that happens before the session closes")
	class MidSessionPublication {

		@Test
		@Tag(REFERENCE)
		@DisplayName("should not persist the refused schema when the same session then defines another collection")
		void shouldNotPersistTheRefusedSchemaWhenTheSameSessionThenDefinesAnotherCollection() {
			// the barrier is raised when the session CLOSES, so anything that publishes EARLIER in the same session
			// publishes the refused schema before there is a barrier to stop it. Creating a collection is such a
			// publication: `Catalog#createEntitySchema` runs a full flush inline and joins it while still in warm-up
			WarmUpRefusedSchemaPersistenceTest.this.defineProductWithPlainAttribute();

			CatalogUnpublishableBarrierAssertions.assertRefusalRaisesTheBarrier(
				WarmUpRefusedSchemaPersistenceTest.this.evita, TEST_CATALOG,
				InvalidSchemaMutationException.class,
				() -> WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.updateEntitySchema(
							new ModifyEntitySchemaMutation(
								Entities.PRODUCT,
								new CreateReferenceSchemaMutation(
									REFERENCE_BRAND, null, null, Cardinality.ZERO_OR_ONE,
									NON_EXISTING_ENTITY_TYPE, true,
									null, false,
									false, false
								)
							)
						);
						// the mid-session publication, in the very same session as the offending mutation
						session.defineEntitySchema(Entities.CATEGORY)
							.withoutGeneratedPrimaryKey()
							.updateVia(session);
					}
				)
			);

			final EntitySchemaContract reopenedSchema = WarmUpRefusedSchemaPersistenceTest.this.reopenAndReadSchema();
			assertFalse(
				reopenedSchema.getReference(REFERENCE_BRAND).isPresent(),
				"A collection defined after the offending mutation must not publish the refused schema with it"
			);
		}

		@Test
		@Tag(REFERENCE)
		@DisplayName("should not publish the refused schema when the same session goes live")
		void shouldNotPublishTheRefusedSchemaWhenTheSameSessionGoesLive() {
			// `goLiveAndClose` closes the session through its own termination path rather than through
			// `EvitaSession#closeInternal`, so the catalog-schema validation that guards an ordinary warm-up close
			// does not run for the session that calls it
			WarmUpRefusedSchemaPersistenceTest.this.defineProductWithPlainAttribute();

			// the barrier here is raised by the go-live operator, and the deactivation it schedules races that same
			// operator's finalization for the catalog's conflict key - so awaiting it also pins the retry in
			// `Catalog#attemptDeactivation`
			CatalogUnpublishableBarrierAssertions.assertRefusalRaisesTheBarrier(
				WarmUpRefusedSchemaPersistenceTest.this.evita, TEST_CATALOG,
				InvalidSchemaMutationException.class,
				() -> WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.updateEntitySchema(
							new ModifyEntitySchemaMutation(
								Entities.PRODUCT,
								new CreateReferenceSchemaMutation(
									REFERENCE_BRAND, null, null, Cardinality.ZERO_OR_ONE,
									NON_EXISTING_ENTITY_TYPE, true,
									null, false,
									false, false
								)
							)
						);
						session.goLiveAndClose();
					}
				)
			);

			final EntitySchemaContract reopenedSchema = WarmUpRefusedSchemaPersistenceTest.this.reopenAndReadSchema();
			assertFalse(
				reopenedSchema.getReference(REFERENCE_BRAND).isPresent(),
				"Going live must not publish a schema the engine would refuse to accept"
			);
		}
	}

	@Nested
	@DisplayName("recovery from the refusal")
	class Recovery {

		@Test
		@Tag(MANAGEMENT)
		@DisplayName("should deactivate the catalog and hand back the last published state on activation")
		void shouldDeactivateTheCatalogAndHandBackTheLastPublishedStateOnActivation() {
			// published, because the session that wrote them closed successfully: the schema and one entity
			WarmUpRefusedSchemaPersistenceTest.this.defineProductWithPlainAttribute();
			WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, PUBLISHED_PRODUCT_PK).upsertVia(session);
				}
			);

			// never published: a second entity, and then the schema change this session's close refuses. Both are
			// applied in memory - the refusal comes from validating the whole schema at close - and neither reaches
			// the disk, which is exactly the trade the recovery makes.
			// The managed-reference rule is the one used here rather than the accelerator rule: an accelerator on a
			// collection that already holds entities is refused by a pre-flight check instead, before any exchange
			// happens, so it would leave nothing to recover FROM and the test would pass without proving anything
			final InvalidSchemaMutationException exception =
				CatalogUnpublishableBarrierAssertions.assertRefusalRaisesTheBarrier(
					WarmUpRefusedSchemaPersistenceTest.this.evita, TEST_CATALOG,
					InvalidSchemaMutationException.class,
					() -> WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.createNewEntity(Entities.PRODUCT, DISCARDED_PRODUCT_PK).upsertVia(session);
							session.updateEntitySchema(
								new ModifyEntitySchemaMutation(
									Entities.PRODUCT,
									new CreateReferenceSchemaMutation(
										REFERENCE_BRAND, null, null, Cardinality.ZERO_OR_ONE,
										NON_EXISTING_ENTITY_TYPE, true,
										null, false,
										false, false
									)
								)
							);
						}
					)
				);
			assertTrue(
				exception.getMessage().contains(NON_EXISTING_ENTITY_TYPE)
					&& exception.getMessage().contains("is not present in catalog"),
				() -> "The recovery must be driven by the catalog-schema validation refusal, but was: "
					+ exception.getMessage()
			);

			// the deactivation has settled by now - `assertRefusalRaisesTheBarrier` waited for it - but the
			// lifecycle mutation carrying it releases its conflict key only as it finalizes, a moment later
			WarmUpRefusedSchemaPersistenceTest.this.activateWithConflictRetry();

			WarmUpRefusedSchemaPersistenceTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertFalse(
						session.getEntitySchemaOrThrow(Entities.PRODUCT).getReference(REFERENCE_BRAND).isPresent(),
						"Activation must hand back the schema of the last state the catalog published"
					);
					assertTrue(
						session.getEntity(Entities.PRODUCT, PUBLISHED_PRODUCT_PK).isPresent(),
						"An entity written by a session that closed successfully must survive the recovery"
					);
					assertFalse(
						session.getEntity(Entities.PRODUCT, DISCARDED_PRODUCT_PK).isPresent(),
						"An entity written by the refused session must not survive it - it was never published"
					);
				}
			);
		}
	}

	/**
	 * Activates the test catalog, retrying while the engine reports that another lifecycle operation for it is
	 * still in flight.
	 *
	 * The retry is not optional. A deactivation settles in two steps: the engine-state update lands - which is what
	 * {@link CatalogUnpublishableBarrierAssertions.SettledStateLatch} observes - and the lifecycle mutation carrying
	 * it releases its conflict key a moment later, as it finalizes. An activation issued in between is refused with
	 * {@link ConflictingEngineMutationException}, the engine's "another lifecycle operation for this catalog is
	 * still in flight" signal, which is a transient rather than a failure worth failing the test over.
	 *
	 * This is a **retry of a refused operation**, not a poll for a condition, which is why it is a loop and stays
	 * one: the conflict key is released as the deactivation mutation finalizes and no seam exposes that moment, so
	 * there is nothing to latch on. A slow machine widens the window this rides out rather than shortening it.
	 */
	private void activateWithConflictRetry() {
		final long deadline = System.currentTimeMillis() + AWAIT_BUDGET_MILLIS;
		RuntimeException lastConflict;
		do {
			try {
				this.evita.activateCatalog(TEST_CATALOG);
				return;
			} catch (RuntimeException ex) {
				if (!(ex instanceof ConflictingEngineMutationException)
					&& !(ex.getCause() instanceof ConflictingEngineMutationException)) {
					throw ex;
				}
				lastConflict = ex;
				backOffBeforeRetry();
			}
		} while (System.currentTimeMillis() < deadline);
		fail("Catalog `" + TEST_CATALOG + "` could not be activated: " + lastConflict.getMessage());
	}

	/**
	 * Waits out one backoff interval between two attempts at an operation the engine currently refuses, turning an
	 * interrupt into a test failure rather than a silent early return.
	 */
	private void backOffBeforeRetry() {
		try {
			Thread.sleep(RETRY_BACKOFF_MILLIS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			fail("Interrupted while retrying an engine operation refused by a lifecycle conflict.");
		}
	}

	/**
	 * Defines the product schema with a single `String` attribute that is neither filterable nor unique in any scope,
	 * so that an accelerator declared on it has no filter index to sit on.
	 */
	private void defineProductWithPlainAttribute() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::nullable)
					.updateVia(session);
			}
		);
	}

	/**
	 * Closes the running instance and opens a fresh one over the same storage directory, returning the product schema
	 * as it was actually read back from disk.
	 *
	 * Reopening is the only honest way to ask what a refused session left behind - the in-memory catalog would answer
	 * for a schema that was never written, and the close is itself the write under test.
	 *
	 * @return the product entity schema of the reopened catalog; never null
	 */
	@Nonnull
	private EntitySchemaContract reopenAndReadSchema() {
		reopenCatalog();
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchemaOrThrow(Entities.PRODUCT);
			}
		);
	}

	/**
	 * Closes the running instance and opens a fresh one over the same storage directory, so that everything read
	 * afterwards comes from the disk rather than from a catalog that never wrote it.
	 *
	 * Separate from {@link #reopenAndReadSchema()} because a removal has no product schema to read back - the whole
	 * point of the assertion is which collections exist at all.
	 */
	private void reopenCatalog() {
		this.evita.close();
		this.evita = new Evita(configuration());
		// deterministic rather than a wait: the constructor schedules the initial catalog load and this joins the
		// futures it created, so the state read below is the settled one
		this.evita.waitUntilFullyInitialized();
		// a refusal schedules a deactivation, and whether that lands before the close is a race this test must not
		// depend on. When it does land, the INACTIVE state is persisted and the reopened engine leaves the catalog
		// unloaded; when the close wins, the catalog comes back loaded. Both sides read the same bootstrap record -
		// the newest state that reached the disk - which is the state under assertion
		if (this.evita.getCatalogState(TEST_CATALOG).orElse(null) == CatalogState.INACTIVE) {
			activateWithConflictRetry();
		}
	}

	/**
	 * Reads the entity types the currently open catalog holds.
	 *
	 * @return the entity types; never null
	 */
	@Nonnull
	private Set<String> readEntityTypes() {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getAllEntityTypes();
			}
		);
	}

	/**
	 * Defines a circular product/category pair: an ordinary managed reference from the product to the category, and a
	 * reflected reference on the category mirroring it back. One entity is written into each, so that both collections
	 * own a data file the assertions below can look for.
	 *
	 * Both directions matter to the tests that use this. Dropping the product leaves the category's reflected
	 * reference without the reference it reflects; dropping the category leaves the product's managed reference
	 * without the entity type it names. Neither half of the pair can be dropped on its own.
	 */
	private void defineInterlinkedProductAndCategory() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REFERENCE_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
			}
		);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchemaOrThrow(Entities.CATEGORY)
					.openForWrite()
					.withReflectedReferenceToEntity(
						REFERENCE_REFLECTED_PRODUCTS, Entities.PRODUCT, REFERENCE_CATEGORY,
						whichIs -> whichIs.withAttributesInherited()
					)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, CATEGORY_PK));
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, PUBLISHED_PRODUCT_PK)
						.setReference(REFERENCE_CATEGORY, CATEGORY_PK)
				);
			}
		);
	}

	/**
	 * Lists the entity-collection data files the catalog folder currently holds, by file name.
	 *
	 * This is the only way to tell a removal that published from one that was refused - both leave the same catalog
	 * behind in memory. A removal does not unlink the collection's data file: the file is still named by the
	 * bootstrap record a reload would follow, so the unlink is parked and released only by the record that supersedes
	 * it ({@code DefaultCatalogPersistenceService#retireDataFile}). A round that never publishes must therefore drop
	 * the retirement, and a round that publishes must honour it.
	 *
	 * @return the data-file names, sorted; never null
	 */
	@Nonnull
	private List<String> listCollectionDataFileNames() {
		try (final Stream<Path> storageContent = Files.walk(this.paths.storage())) {
			return storageContent
				.filter(Files::isRegularFile)
				.map(it -> it.getFileName().toString())
				.filter(it -> it.endsWith(CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX))
				.sorted()
				.toList();
		} catch (IOException ex) {
			return fail("Catalog storage folder `" + this.paths.storage() + "` could not be listed.", ex);
		}
	}

	/**
	 * Builds the throw-away embedded configuration this test runs against.
	 *
	 * @return the configuration; never null
	 */
	@Nonnull
	private EvitaConfiguration configuration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

}
