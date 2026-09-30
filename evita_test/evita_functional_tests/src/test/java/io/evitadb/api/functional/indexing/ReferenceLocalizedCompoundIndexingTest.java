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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Locale;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that a localized sortable attribute compound declared on a reference keeps its per-locale entries in the
 * reduced index of every referenced entity through the whole life of the owner, whichever level the reference is
 * indexed at. The reference-level compound lives in the reduced index for both
 * {@link ReferenceIndexType#FOR_FILTERING} and {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING} - only the
 * entity-level compounds are reserved to partitioning - so ordering by it on the index route must agree with the
 * prefetch route, which computes the value from the stored reference attributes, at both levels.
 *
 * Each scenario drives one path that adds or removes the per-locale entries: the owner inserted with a locale, a
 * reference added to an owner that already has one, a locale the owner gains or loses after the reference exists,
 * a reference removed and added again, an archive and restore, a reference attribute updated, and the reference
 * index level changed on existing data. A removal that misses its entry leaves it behind, and adding the entry again
 * then fails on `SortIndex#addRecord`, so the scenarios that remove always add back.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Localized reference sortable compound is indexed at every reference indexing level")
@Tag(INDEXING)
@Tag(ORDER)
@Tag(REFERENCE)
public class ReferenceLocalizedCompoundIndexingTest implements EvitaTestSupport {
	private static final String DIR = "referenceLocalizedCompoundIndexingTest";
	private static final String DIR_EXPORT = "referenceLocalizedCompoundIndexingTest_export";
	private static final String ENTITY_TARGET = "target";
	private static final String ENTITY_OWNER = "owner";
	private static final String REFERENCE_ITEMS = "items";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String ATTRIBUTE_LABEL = "label";
	private static final String COMPOUND_ORDER_LABEL = "orderLabel";
	private static final Locale LOCALE = Locale.ENGLISH;
	private static final Scope[] BOTH_SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	/**
	 * The order of the owners written by {@link #createOwnersWithLocale()}: `order` ascending, then `label`
	 * descending, the owner without a reference last.
	 */
	private static final int[] BASE_ORDER = {2, 3, 1, 4};

	private Evita evita;

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR);
		cleanTestSubDirectoryWithRethrow(DIR_EXPORT);
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR);
		cleanTestSubDirectoryWithRethrow(DIR_EXPORT);
	}

	@DisplayName("Should order by the compound when the owner is inserted with a locale")
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = ReferenceIndexType.class, names = {"FOR_FILTERING", "FOR_FILTERING_AND_PARTITIONING"})
	void shouldOrderByCompoundWhenOwnerIsInsertedWithLocale(ReferenceIndexType indexType) {
		createSchema(indexType);
		createOwnersWithLocale();

		assertCompoundOrder(BASE_ORDER);
	}

	@DisplayName("Should order by the compound when the reference is added to an owner that has the locale")
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = ReferenceIndexType.class, names = {"FOR_FILTERING", "FOR_FILTERING_AND_PARTITIONING"})
	void shouldOrderByCompoundWhenReferenceIsAddedToOwnerWithLocale(ReferenceIndexType indexType) {
		createSchema(indexType);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createTargets(session);
				for (int owner = 1; owner <= 4; owner++) {
					session.createNewEntity(ENTITY_OWNER, owner)
						.setAttribute(ATTRIBUTE_NAME, LOCALE, "owner " + owner)
						.upsertVia(session);
				}
			}
		);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				setItem(session, 1, 1, 2, "b");
				setItem(session, 2, 1, 1, "a");
				setItem(session, 3, 2, 2, "c");
			}
		);

		assertCompoundOrder(BASE_ORDER);
	}

	@DisplayName("Should order by the compound when the owner gains the locale after the reference exists")
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = ReferenceIndexType.class, names = {"FOR_FILTERING", "FOR_FILTERING_AND_PARTITIONING"})
	void shouldOrderByCompoundWhenOwnerGainsLocaleAfterReference(ReferenceIndexType indexType) {
		createSchema(indexType);
		// no localized value anywhere - the owners have no locale yet and the compound has only its `order` half
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createTargets(session);
				createOwner(session, 1, 1, 3, null);
				createOwner(session, 2, 1, 1, null);
				createOwner(session, 3, 2, 2, null);
				session.createNewEntity(ENTITY_OWNER, 4).upsertVia(session);
			}
		);
		assertCompoundOrder();

		// the owners gain the locale through an entity attribute, the reference is left untouched
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				for (int owner = 1; owner <= 4; owner++) {
					session.getEntity(ENTITY_OWNER, owner, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTRIBUTE_NAME, LOCALE, "owner " + owner)
						.upsertVia(session);
				}
			}
		);

		assertCompoundOrder(2, 3, 1, 4);
		assertCompoundEntry(1, 1, true);
	}

	@DisplayName("Should drop the compound when the owner loses the locale and index it again when regained")
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = ReferenceIndexType.class, names = {"FOR_FILTERING", "FOR_FILTERING_AND_PARTITIONING"})
	void shouldDropCompoundWhenOwnerLosesLocale(ReferenceIndexType indexType) {
		createSchema(indexType);
		createOwnersWithLocale();
		assertCompoundEntry(1, 1, true);

		// the reference loses its localized label first - the owner keeps the locale through its name
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(ENTITY_OWNER, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.updateReference(
						REFERENCE_ITEMS, 1, whichIs -> whichIs.removeAttribute(ATTRIBUTE_LABEL, LOCALE)
					)
					.upsertVia(session);
			}
		);
		assertRoutesAgree();
		assertCompoundEntry(1, 1, true);

		// then the owner loses its last localized value, and with it the locale
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(ENTITY_OWNER, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.removeAttribute(ATTRIBUTE_NAME, LOCALE)
					.upsertVia(session);
			}
		);
		assertCompoundOrder(2, 3, 4);
		assertCompoundEntry(1, 1, false);

		// the locale comes back with a label that moves the owner ahead of owner 3
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(ENTITY_OWNER, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_NAME, LOCALE, "owner 1")
					.updateReference(
						REFERENCE_ITEMS, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_LABEL, LOCALE, "z")
					)
					.upsertVia(session);
			}
		);
		assertCompoundOrder(2, 1, 3, 4);
	}

	@DisplayName("Should drop the compound when the reference is removed and index it again when re-added")
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = ReferenceIndexType.class, names = {"FOR_FILTERING", "FOR_FILTERING_AND_PARTITIONING"})
	void shouldDropCompoundWhenReferenceIsRemoved(ReferenceIndexType indexType) {
		createSchema(indexType);
		createOwnersWithLocale();

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(ENTITY_OWNER, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.removeReference(REFERENCE_ITEMS, 1)
					.upsertVia(session);
			}
		);
		// owner 2 keeps the reduced index of target 1 alive, so the entry of owner 1 can be looked for in it
		assertCompoundEntry(1, 1, false);
		assertCompoundOrder(2, 3, 1, 4);

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			setItem(session, 1, 1, 0, "q");
		});
		assertCompoundOrder(1, 2, 3, 4);
	}

	@DisplayName("Should keep the compound across archiving and restoring the owner")
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = ReferenceIndexType.class, names = {"FOR_FILTERING", "FOR_FILTERING_AND_PARTITIONING"})
	void shouldKeepCompoundAcrossArchiveAndRestore(ReferenceIndexType indexType) {
		createSchema(indexType);
		createOwnersWithLocale();

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.archiveEntity(ENTITY_OWNER, 1);
		});
		assertCompoundOrder(2, 3, 4);
		assertCompoundEntry(1, 1, false);

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.restoreEntity(ENTITY_OWNER, 1);
		});
		assertCompoundOrder(BASE_ORDER);
		assertCompoundEntry(1, 1, true);

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.archiveEntity(ENTITY_OWNER, 1);
		});
		assertCompoundOrder(2, 3, 4);
	}

	@DisplayName("Should reorder by the compound when a reference attribute changes")
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = ReferenceIndexType.class, names = {"FOR_FILTERING", "FOR_FILTERING_AND_PARTITIONING"})
	void shouldReorderByCompoundWhenReferenceAttributeChanges(ReferenceIndexType indexType) {
		createSchema(indexType);
		createOwnersWithLocale();

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			setItem(session, 1, 1, 0, "b");
		});
		assertCompoundOrder(1, 2, 3, 4);

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			setItem(session, 3, 2, 2, "a");
		});
		assertCompoundOrder(1, 2, 3, 4);
	}

	@DisplayName("Should keep the compound consistent when the reference indexing level changes on existing data")
	@ParameterizedTest(name = "to {0}")
	@EnumSource(value = ReferenceIndexType.class, names = {"FOR_FILTERING", "FOR_FILTERING_AND_PARTITIONING"})
	void shouldKeepCompoundConsistentWhenIndexingLevelChanges(ReferenceIndexType targetIndexType) {
		createSchema(
			targetIndexType == ReferenceIndexType.FOR_FILTERING ?
				ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING : ReferenceIndexType.FOR_FILTERING
		);
		createOwnersWithLocale();

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchemaOrThrowException(ENTITY_OWNER)
					.openForWrite()
					.withReferenceToEntity(
						REFERENCE_ITEMS, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
						whichIs -> applyIndexType(whichIs, targetIndexType)
					)
					.updateVia(session);
			}
		);
		assertCompoundOrder(BASE_ORDER);

		// the entries written at the former level are removed at the new one - and written back without a clash
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(ENTITY_OWNER, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.removeReference(REFERENCE_ITEMS, 1)
					.upsertVia(session);
			}
		);
		assertCompoundEntry(1, 1, false);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			setItem(session, 1, 1, 0, "q");
		});
		assertCompoundOrder(1, 2, 3, 4);
	}

	/**
	 * Defines the target and owner schemas; the reference is indexed at `indexType` in both scopes.
	 */
	private void createSchema(@Nonnull ReferenceIndexType indexType) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_TARGET)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(ENTITY_OWNER)
					.withoutGeneratedPrimaryKey()
					.withLocale(LOCALE)
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().nullable())
					.withReferenceToEntity(
						REFERENCE_ITEMS, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
						whichIs -> applyIndexType(whichIs, indexType)
							.withAttribute(
								ATTRIBUTE_ORDER, Integer.class,
								thatIs -> thatIs.sortableInScope(BOTH_SCOPES).nullable()
							)
							.withAttribute(
								ATTRIBUTE_LABEL, String.class,
								thatIs -> thatIs.localized().sortableInScope(BOTH_SCOPES).nullable()
							)
							.withSortableAttributeCompound(
								COMPOUND_ORDER_LABEL,
								new AttributeElement[]{
									new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
									new AttributeElement(ATTRIBUTE_LABEL, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
								},
								thatIs -> thatIs.indexedInScope(BOTH_SCOPES)
							)
					)
					.updateVia(session);
			}
		);
	}

	/**
	 * Indexes the reference at `indexType` in both scopes.
	 */
	@Nonnull
	private static ReferenceSchemaBuilder applyIndexType(
		@Nonnull ReferenceSchemaBuilder builder,
		@Nonnull ReferenceIndexType indexType
	) {
		return switch (indexType) {
			case FOR_FILTERING -> builder.indexedForFilteringInScope(BOTH_SCOPES);
			case FOR_FILTERING_AND_PARTITIONING -> builder.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES);
			case NONE -> throw new IllegalArgumentException("The compound needs an indexed reference.");
		};
	}

	/**
	 * Writes the two targets and four owners with the locale, whose compound order is {@link #BASE_ORDER}.
	 */
	private void createOwnersWithLocale() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createTargets(session);
				createOwner(session, 1, 1, 2, "b");
				createOwner(session, 2, 1, 1, "a");
				createOwner(session, 3, 2, 2, "c");
				session.createNewEntity(ENTITY_OWNER, 4)
					.setAttribute(ATTRIBUTE_NAME, LOCALE, "owner 4")
					.upsertVia(session);
			}
		);
	}

	/**
	 * Writes the targets 1 and 2.
	 */
	private static void createTargets(@Nonnull EvitaSessionContract session) {
		session.createNewEntity(ENTITY_TARGET, 1).upsertVia(session);
		session.createNewEntity(ENTITY_TARGET, 2).upsertVia(session);
	}

	/**
	 * Writes an owner with one reference to `target`; with a `label` the owner also gets its localized name, without
	 * one it has no locale at all.
	 */
	private static void createOwner(
		@Nonnull EvitaSessionContract session,
		int owner,
		int target,
		int order,
		@Nullable String label
	) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_OWNER, owner);
		if (label != null) {
			builder.setAttribute(ATTRIBUTE_NAME, LOCALE, "owner " + owner);
		}
		builder.setReference(
			REFERENCE_ITEMS, target,
			whichIs -> {
				whichIs.setAttribute(ATTRIBUTE_ORDER, order);
				if (label != null) {
					whichIs.setAttribute(ATTRIBUTE_LABEL, LOCALE, label);
				}
			}
		);
		builder.upsertVia(session);
	}

	/**
	 * Creates or updates the reference of an existing owner to `target`.
	 */
	private static void setItem(
		@Nonnull EvitaSessionContract session,
		int owner,
		int target,
		int order,
		@Nonnull String label
	) {
		session.getEntity(ENTITY_OWNER, owner, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				REFERENCE_ITEMS, target,
				whichIs -> whichIs
					.setAttribute(ATTRIBUTE_ORDER, order)
					.setAttribute(ATTRIBUTE_LABEL, LOCALE, label)
			)
			.upsertVia(session);
	}

	/**
	 * Asserts that the live owners with the locale come out in `expected` order when ordered by the compound
	 * ascending, on the index route and on the prefetch route alike, and that both routes agree on the descending
	 * order too.
	 */
	private void assertCompoundOrder(int... expected) {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int[] index = queryOrder(session, DebugMode.PREFER_INDEX_SCAN, OrderDirection.ASC);
				final int[] prefetch = queryOrder(session, DebugMode.PREFER_PREFETCHING, OrderDirection.ASC);
				assertArrayEquals(
					expected, prefetch,
					() -> "prefetch route: expected " + Arrays.toString(expected) + " but was " + Arrays.toString(prefetch)
				);
				assertArrayEquals(
					expected, index,
					() -> "index route: expected " + Arrays.toString(expected) + " but was " + Arrays.toString(index)
				);
			}
		);
		assertRoutesAgree();
	}

	/**
	 * Asserts that the index route and the prefetch route order the live owners with the locale identically, in both
	 * directions of the compound.
	 */
	private void assertRoutesAgree() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (OrderDirection direction : OrderDirection.values()) {
					final int[] index = queryOrder(session, DebugMode.PREFER_INDEX_SCAN, direction);
					final int[] prefetch = queryOrder(session, DebugMode.PREFER_PREFETCHING, direction);
					assertArrayEquals(
						prefetch, index,
						() -> direction + ": index route " + Arrays.toString(index) + " but prefetch route " +
							Arrays.toString(prefetch)
					);
				}
			}
		);
	}

	/**
	 * Runs the ordering by the compound on the route and returns the owner primary keys in result order.
	 */
	@Nonnull
	private static int[] queryOrder(
		@Nonnull EvitaSessionContract session,
		@Nonnull DebugMode route,
		@Nonnull OrderDirection direction
	) {
		return session.query(
				query(
					collection(ENTITY_OWNER),
					filterBy(entityLocaleEquals(LOCALE)),
					orderBy(referenceProperty(REFERENCE_ITEMS, attributeNatural(COMPOUND_ORDER_LABEL, direction))),
					require(page(1, 100), debug(route))
				),
				EntityReference.class
			)
			.getRecordData()
			.stream()
			.mapToInt(EntityReference::getPrimaryKey)
			.toArray();
	}

	/**
	 * Asserts whether the live reduced index of `target` holds a per-locale compound entry of `owner`. The index must
	 * exist - another owner keeps it alive - so an absent entry is never mistaken for an absent index.
	 */
	private void assertCompoundEntry(int target, int owner, boolean expectedPresent) {
		final EntityCollectionContract collection = this.evita.getCatalogInstance(TEST_CATALOG)
			.orElseThrow()
			.getCollectionForEntity(ENTITY_OWNER)
			.orElseThrow();
		final EntityIndex reducedIndex = IndexingTestSupport.getReferencedEntityIndex(
			collection, REFERENCE_ITEMS, target
		);
		assertNotNull(reducedIndex, "The reduced index of target " + target + " must exist.");
		final SortIndex sortIndex = reducedIndex.getSortIndex(
			new AttributeIndexKey(REFERENCE_ITEMS, COMPOUND_ORDER_LABEL, LOCALE)
		);
		final boolean present = sortIndex != null &&
			Arrays.stream(sortIndex.getSortedRecords()).anyMatch(it -> it == owner);
		if (expectedPresent) {
			assertTrue(present, "Owner " + owner + " must have a compound entry in target " + target + ".");
		} else {
			assertFalse(present, "Owner " + owner + " must have no compound entry in target " + target + ".");
		}
	}

	/**
	 * Returns the configuration of an instance writing into this test's own directories.
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return EvitaConfiguration.builder()
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.storage(StorageOptions.builder().storageDirectory(getTestDirectory().resolve(DIR)).build())
			.export(FileSystemExportOptions.builder().directory(getTestDirectory().resolve(DIR_EXPORT)).build())
			.build();
	}
}
