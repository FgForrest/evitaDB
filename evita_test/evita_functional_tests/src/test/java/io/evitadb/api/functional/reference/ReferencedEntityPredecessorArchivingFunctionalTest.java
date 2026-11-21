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

package io.evitadb.api.functional.reference;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.dataType.Scope.ARCHIVED;
import static io.evitadb.dataType.Scope.LIVE;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies scope changes and ordering for owners of a plain reference with a sortable
 * `ReferencedEntityPredecessor`. The rows are written during warm-up and scope changes run after go-live.
 *
 * | Dataset                    | Owners | Rows per owner           | Scope change |
 * |----------------------------|--------|--------------------------|--------------|
 * | Shared brand, one session  | 1, 2   | Brand 1, a chain head    | Archive both together |
 * | Shared brand, two sessions | 1, 2   | Brand 1, a chain head    | Archive 1, then 2 |
 * | Shared brand, one owner    | 1, 2   | Brand 1, a chain head    | Archive 2 |
 * | Shared brand, restoration  | 1, 2   | Brand 1, a chain head    | Archive both, then restore both |
 * | Ungrouped                  | 1–6    | Brands 1–3, linked below  | Archive 3–6 together |
 * | Grouped                    | 3      | Brands 1–3, linked below | Archive 3 |
 * | Live ordering              | 1, 2   | Brands 1–3, linked below  | None |
 *
 * | Owner | Brand row insertion | Chain  |
 * |-------|---------------------|--------|
 * | 1     | 2, 1, 3             | 2, 1, 3 |
 * | 2     | 2, 3, 1             | 1, 3, 2 |
 * | 3     | 1, 3, 2             | 3, 2, 1 |
 * | 4     | 3, 1, 2             | 1, 3, 2 |
 * | 5     | 3, 2, 1             | 2, 3, 1 |
 * | 6     | 1, 2, 3             | 3, 1, 2 |
 *
 * Multi-row owners are inserted with heads, then linked in a second write. On the grouped reference, brands 1 and 2
 * carry group 11 and brand 3 carries group 10.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Archiving and restoring owners of references with a referenced-entity predecessor")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(REFERENCE)
@Tag(ATTRIBUTE)
class ReferencedEntityPredecessorArchivingFunctionalTest {
	private static final String PRODUCT = "PRODUCT";
	private static final String BRAND = "BRAND";
	private static final String BRAND_GROUP = "BRAND_GROUP";
	private static final String BRANDS = "brands";
	private static final String PREDECESSOR = "predecessor";
	private static final String SHARED_BRAND_TOGETHER = "predecessorArchivingSharedBrandTogether";
	private static final String SHARED_BRAND_APART = "predecessorArchivingSharedBrandApart";
	private static final String SHARED_BRAND_ONE = "predecessorArchivingSharedBrandOne";
	private static final String SHARED_BRAND_RESTORE = "predecessorArchivingSharedBrandRestore";
	private static final String UNGROUPED = "predecessorArchivingUngrouped";
	private static final String GROUPED_SINGLE_OWNER = "predecessorArchivingGroupedSingleOwner";
	private static final String LIVE_ORDERING = "predecessorArchivingLiveOrdering";
	private static final List<Owner> SHARED_BRAND_OWNERS = List.of(
		new Owner(1, List.of(1), List.of(1)),
		new Owner(2, List.of(1), List.of(1))
	);
	private static final List<Owner> SIX_OWNERS = List.of(
		new Owner(1, List.of(2, 1, 3), List.of(2, 1, 3)),
		new Owner(2, List.of(2, 3, 1), List.of(1, 3, 2)),
		new Owner(3, List.of(1, 3, 2), List.of(3, 2, 1)),
		new Owner(4, List.of(3, 1, 2), List.of(1, 3, 2)),
		new Owner(5, List.of(3, 2, 1), List.of(2, 3, 1)),
		new Owner(6, List.of(1, 2, 3), List.of(3, 1, 2))
	);

	/**
	 * Creates the two shared-brand owners for archiving in a single session.
	 *
	 * @param evita engine whose catalog is still warming up
	 */
	@DataSet(value = SHARED_BRAND_TOGETHER, readOnly = false, destroyAfterClass = true)
	void setUpSharedBrandTogether(@Nonnull Evita evita) {
		populate(evita, false, 1, SHARED_BRAND_OWNERS);
	}

	/**
	 * Creates a separate copy of the shared brand for archiving its owners in consecutive sessions.
	 *
	 * @param evita engine whose catalog is still warming up
	 */
	@DataSet(value = SHARED_BRAND_APART, readOnly = false, destroyAfterClass = true)
	void setUpSharedBrandApart(@Nonnull Evita evita) {
		populate(evita, false, 1, SHARED_BRAND_OWNERS);
	}

	/**
	 * Creates a separate copy of the shared brand for a move that leaves one owner live.
	 *
	 * @param evita engine whose catalog is still warming up
	 */
	@DataSet(value = SHARED_BRAND_ONE, readOnly = false, destroyAfterClass = true)
	void setUpSharedBrandOne(@Nonnull Evita evita) {
		populate(evita, false, 1, SHARED_BRAND_OWNERS);
	}

	/**
	 * Creates a separate copy of the shared brand for the archive and restore round trip.
	 *
	 * @param evita engine whose catalog is still warming up
	 */
	@DataSet(value = SHARED_BRAND_RESTORE, readOnly = false, destroyAfterClass = true)
	void setUpSharedBrandRestore(@Nonnull Evita evita) {
		populate(evita, false, 1, SHARED_BRAND_OWNERS);
	}

	/**
	 * Writes six owners with independent chains over the same three brands.
	 *
	 * @param evita engine whose catalog is still warming up
	 */
	@DataSet(value = UNGROUPED, readOnly = false, destroyAfterClass = true)
	void setUpUngrouped(@Nonnull Evita evita) {
		populate(evita, false, 3, SIX_OWNERS);
	}

	/**
	 * Writes the third owner alone, with its chain on grouped references.
	 *
	 * @param evita engine whose catalog is still warming up
	 */
	@DataSet(value = GROUPED_SINGLE_OWNER, readOnly = false, destroyAfterClass = true)
	void setUpGroupedSingleOwner(@Nonnull Evita evita) {
		populate(evita, true, 3, List.of(SIX_OWNERS.get(2)));
	}

	/**
	 * Writes two live owners that link the same brands in different orders.
	 *
	 * @param evita engine whose catalog is still warming up
	 */
	@DataSet(value = LIVE_ORDERING, destroyAfterClass = true)
	void setUpLiveOrdering(@Nonnull Evita evita) {
		populate(evita, false, 3, SIX_OWNERS.subList(0, 2));
	}

	/**
	 * Moves owners whose only reference points to a shared brand, which exercises the same index partition.
	 */
	@Nested
	@DisplayName("Scope changes for two owners of one brand")
	class SharedBrandScopeChanges {

		/**
		 * Checks that both owners can be archived within one transaction.
		 *
		 * @param evita engine hosting the two live owners before archiving
		 */
		@Test
		@UseDataSet(SHARED_BRAND_TOGETHER)
		@DisplayName("Should archive two owners of the same brand in one session")
		void shouldArchiveOwnersWhenTheirOnlyRowsTargetTheSameBrand(@Nonnull Evita evita) {
			assertArchived(evita, 1, 2);
		}

		/**
		 * Checks that the second owner can still be archived after the first move commits.
		 *
		 * @param evita engine hosting the two live owners before archiving
		 */
		@Test
		@UseDataSet(SHARED_BRAND_APART)
		@DisplayName("Should archive two owners of the same brand in separate sessions")
		void shouldArchiveOwnersOneByOneWhenTheirOnlyRowsTargetTheSameBrand(@Nonnull Evita evita) {
			assertArchived(evita, 1);
			assertArchived(evita, 2);
			assertOwnersInScope(evita, ARCHIVED, 1, 2);
		}

		/**
		 * Checks that archiving one owner leaves the other owner available in LIVE.
		 *
		 * @param evita engine hosting the two live owners before archiving
		 */
		@Test
		@UseDataSet(SHARED_BRAND_ONE)
		@DisplayName("Should archive one of two owners of the same brand")
		void shouldArchiveOneOwnerWhenAnotherOwnerTargetsTheSameBrand(@Nonnull Evita evita) {
			assertArchived(evita, 2);
			assertOwnersInScope(evita, LIVE, 1);
		}

		/**
		 * Checks that a committed archive can be reversed by restoring both owners to the live scope.
		 *
		 * @param evita engine hosting the two live owners before archiving
		 */
		@Test
		@UseDataSet(SHARED_BRAND_RESTORE)
		@DisplayName("Should restore two owners of the same brand after archiving both")
		void shouldRestoreOwnersWhenTheirOnlyRowsTargetTheSameBrand(@Nonnull Evita evita) {
			assertArchived(evita, 1, 2);
			evita.updateCatalog(TEST_CATALOG, session -> {
				for (final int owner : List.of(1, 2)) {
					assertTrue(
						session.restoreEntity(PRODUCT, owner),
						"Product " + owner + " with a shared brand predecessor must move from ARCHIVED to LIVE"
					);
				}
			});
			assertOwnersInScope(evita, LIVE, 1, 2);
		}
	}

	/**
	 * Moves owners with several linked reference rows, with and without a group.
	 */
	@Nested
	@DisplayName("Archiving owners with linked brand rows")
	class LinkedBrandArchiving {

		/**
		 * Checks that multiple owners of ungrouped brands can move in one transaction.
		 *
		 * @param evita engine hosting six live owners with distinct brand chains
		 */
		@Test
		@UseDataSet(UNGROUPED)
		@DisplayName("Should archive owners when the reference carries no group")
		void shouldArchiveOwnersWhenReferenceIsUngrouped(@Nonnull Evita evita) {
			assertArchived(evita, 3, 4, 5, 6);
		}

		/**
		 * Checks that the chain and group indexes allow the only owner to move.
		 *
		 * @param evita engine hosting the live owner of grouped brands
		 */
		@Test
		@UseDataSet(GROUPED_SINGLE_OWNER)
		@DisplayName("Should archive the only owner of a grouped reference")
		void shouldArchiveOwnerWhenItIsTheOnlyOwnerOfGroupedReference(@Nonnull Evita evita) {
			assertArchived(evita, 3);
		}
	}

	/**
	 * Exercises reference fetch ordering on the live index used by the scope-change cases.
	 */
	@Nested
	@DisplayName("Ordering live owners' brand chains")
	class LiveChainOrdering {

		/**
		 * Checks that each owner returns the sequence encoded by its own predecessor values.
		 *
		 * @param evita engine hosting two different chains over the same three brand keys
		 */
		@Test
		@UseDataSet(LIVE_ORDERING)
		@DisplayName("Should follow each live owner's predecessor chain when the same brands are linked differently")
		void shouldFollowEachLiveOwnersChainWhenBrandsAreShared(@Nonnull Evita evita) {
			for (final OrderDirection direction : OrderDirection.values()) {
				final List<SealedEntity> owners = evita.queryCatalog(
					TEST_CATALOG,
					(EvitaSessionContract session) -> session.queryListOfSealedEntities(
						query(
							collection(PRODUCT), filterBy(scope(LIVE)),
							require(entityFetch(
								referenceContent(BRANDS, orderBy(attributeNatural(PREDECESSOR, direction)))
							))
						)
					)
				);
				final Map<Integer, List<Integer>> actual = owners.stream().collect(Collectors.toMap(
					SealedEntity::getPrimaryKeyOrThrowException,
					owner -> owner.getReferences(BRANDS).stream()
						.map(ReferenceContract::getReferencedPrimaryKey).toList()
				));
				assertEquals(
					direction == OrderDirection.ASC ?
						Map.of(1, List.of(2, 1, 3), 2, List.of(1, 3, 2)) :
						Map.of(1, List.of(3, 1, 2), 2, List.of(2, 3, 1)),
					actual,
					"Each product must follow its own brand chain in " + direction
				);
			}
		}
	}

	/**
	 * Defines the live reference index, writes its rows, and switches the catalog to transactional mode.
	 *
	 * @param evita engine whose catalog is still warming up
	 * @param grouped whether the reference carries a group and indexes the referenced group component
	 * @param brandCount number of consecutive brand keys to create, starting at one
	 * @param owners owners with their reference insertion order and final chains
	 */
	private static void populate(
		@Nonnull Evita evita, boolean grouped, int brandCount, @Nonnull List<Owner> owners
	) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(BRAND).withoutGeneratedPrimaryKey().updateVia(session);
			if (grouped) {
				session.defineEntitySchema(BRAND_GROUP).withoutGeneratedPrimaryKey().updateVia(session);
				session.createNewEntity(BRAND_GROUP, 10).upsertVia(session);
				session.createNewEntity(BRAND_GROUP, 11).upsertVia(session);
			}
			session.defineEntitySchema(PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withReferenceToEntity(BRANDS, BRAND, Cardinality.ZERO_OR_MORE, reference -> {
					reference.indexedForFilteringAndPartitioning()
						.withAttribute(PREDECESSOR, ReferencedEntityPredecessor.class, AttributeSchemaEditor::sortable);
					if (grouped) {
						reference.withGroupTypeRelatedToEntity(BRAND_GROUP)
							.indexedWithComponents(
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							);
					}
				})
				.updateVia(session);
			for (final int brand : IntStream.rangeClosed(1, brandCount).toArray()) {
				session.createNewEntity(BRAND, brand).upsertVia(session);
			}
			for (final Owner owner : owners) {
				writeOwner(session, grouped, owner);
			}
			session.goLiveAndClose();
		});
	}

	/**
	 * Inserts the owner's rows as heads and links the non-head rows in a second upsert.
	 *
	 * @param session writer of the warming-up catalog
	 * @param grouped whether to assign a group to each row
	 * @param owner product with its reference insertion order and final chain
	 */
	private static void writeOwner(@Nonnull EvitaSessionContract session, boolean grouped, @Nonnull Owner owner) {
		final EntityBuilder builder = session.createNewEntity(PRODUCT, owner.primaryKey());
		for (final int brand : owner.insertion()) {
			builder.setReference(BRANDS, brand, reference -> {
				if (grouped) {
					reference.setGroup(brand == 3 ? 10 : 11);
				}
				reference.setAttribute(PREDECESSOR, ReferencedEntityPredecessor.HEAD);
			});
		}
		builder.upsertVia(session);
		if (owner.chain().size() > 1) {
			final EntityBuilder linker = session.getEntity(
				PRODUCT, owner.primaryKey(), referenceContentWithAttributes(BRANDS)
			).orElseThrow().openForWrite();
			for (final int index : IntStream.range(1, owner.chain().size()).toArray()) {
				final int brand = owner.chain().get(index);
				final ReferencedEntityPredecessor predecessor = new ReferencedEntityPredecessor(
					owner.chain().get(index - 1)
				);
				linker.updateReference(BRANDS, brand, reference -> reference.setAttribute(PREDECESSOR, predecessor));
			}
			linker.upsertVia(session);
		}
	}

	/**
	 * Archives the owners in one transaction and verifies their committed scope from a new session.
	 *
	 * @param evita engine hosting the catalog in transactional mode
	 * @param owners primary keys to archive together
	 */
	private static void assertArchived(@Nonnull Evita evita, @Nonnull int... owners) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			for (final int owner : owners) {
				assertTrue(
					session.archiveEntity(PRODUCT, owner),
					"Product " + owner + " with a brand predecessor must move from LIVE to ARCHIVED"
				);
			}
		});
		assertOwnersInScope(evita, ARCHIVED, owners);
	}

	/**
	 * Reads each owner in the specified scope and verifies the committed scope on its returned body.
	 *
	 * @param evita engine hosting the catalog after any scope mutation has committed
	 * @param expectedScope the only scope from which to retrieve the owners
	 * @param owners primary keys that must be visible in that scope
	 */
	private static void assertOwnersInScope(
		@Nonnull Evita evita, @Nonnull Scope expectedScope, @Nonnull int... owners
	) {
		evita.queryCatalog(TEST_CATALOG, session -> {
			for (final int owner : owners) {
				final String context = "Product " + owner + " with a brand predecessor, scope " + expectedScope;
				final SealedEntity product = session.getEntity(PRODUCT, owner, new Scope[]{expectedScope})
					.orElseThrow(() -> new AssertionError(context + " must be found"));
				assertEquals(expectedScope, product.getScope(), context + " returned scope");
			}
		});
	}

	/**
	 * Specifies a product's brand rows.
	 *
	 * @param primaryKey product primary key
	 * @param insertion sequence in which the rows are written
	 * @param chain brand order encoded by the predecessor attributes
	 */
	private record Owner(int primaryKey, @Nonnull List<Integer> insertion, @Nonnull List<Integer> chain) {
	}
}
