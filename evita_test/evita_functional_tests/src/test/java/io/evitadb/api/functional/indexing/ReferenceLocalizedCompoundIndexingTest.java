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

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Further scenarios pin the edges of that rule: the entity-level compounds reach the reduced indexes of a reference
 * indexed for partitioning only, a group index keeps no compound entries while the owner's locales change, and the
 * locale changes of an owner with duplicate references to one target are followed into each of the target's
 * partitions.
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
	private static final String ENTITY_GROUP = "group";
	private static final String REFERENCE_ITEMS = "items";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String ATTRIBUTE_LABEL = "label";
	private static final String ATTRIBUTE_COUNTRY = "country";
	private static final String ATTRIBUTE_RANK = "rank";
	private static final String COMPOUND_ORDER_LABEL = "orderLabel";
	private static final String COMPOUND_NAME = "nameCompound";
	private static final Locale LOCALE = Locale.ENGLISH;
	private static final Scope[] BOTH_SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	/** The key of the per-locale entries of the reference-level compound. */
	private static final AttributeIndexKey REFERENCE_COMPOUND_KEY =
		new AttributeIndexKey(REFERENCE_ITEMS, COMPOUND_ORDER_LABEL, LOCALE);
	/** The key of the per-locale entries of the entity-level compound over the owner's localized name. */
	private static final AttributeIndexKey ENTITY_COMPOUND_KEY = new AttributeIndexKey(null, COMPOUND_NAME, LOCALE);
	/**
	 * The order of the owners written by {@link #createOwnersWithLocale()}: `order` ascending, then `label`
	 * descending, the owner without a reference last.
	 */
	private static final int[] BASE_ORDER = {2, 3, 1, 4};
	/** The owner whose grouped reference the group index scenarios follow. */
	private static final int GROUPED_OWNER = 1;
	/** A second owner in the same group, which keeps the group index alive when the followed owner leaves it. */
	private static final int OTHER_OWNER = 2;
	/** The target of the grouped references. */
	private static final int GROUPED_TARGET = 5;
	/** The group of the grouped references. */
	private static final int GROUP_PK = 10;
	/** The target the duplicate references point to. */
	private static final int DUPLICATE_TARGET = 7;
	/** The representative values of the duplicate references - one partition each. */
	private static final String[] DUPLICATE_COUNTRIES = {"DE", "US"};

	private Evita evita;

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR);
		cleanTestSubDirectoryWithRethrow(DIR_EXPORT);
		this.evita = new Evita(getEvitaConfiguration());
		// TODO JNO - remove at the end of #1615
		// the tests read the sort indexes of the index objects of single-owner partitions, which only a catalog with
		// objects has
		this.evita.createCatalogWithVirtualReferencePartitions(TEST_CATALOG, false);
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
	void shouldOrderByCompoundWhenOwnerIsInsertedWithLocale(@Nonnull ReferenceIndexType indexType) {
		createSchema(indexType);
		createOwnersWithLocale();

		assertCompoundOrder(BASE_ORDER);
	}

	@DisplayName("Should order by the compound when the reference is added to an owner that has the locale")
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = ReferenceIndexType.class, names = {"FOR_FILTERING", "FOR_FILTERING_AND_PARTITIONING"})
	void shouldOrderByCompoundWhenReferenceIsAddedToOwnerWithLocale(@Nonnull ReferenceIndexType indexType) {
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
	void shouldOrderByCompoundWhenOwnerGainsLocaleAfterReference(@Nonnull ReferenceIndexType indexType) {
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
	void shouldDropCompoundWhenOwnerLosesLocale(@Nonnull ReferenceIndexType indexType) {
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
	void shouldDropCompoundWhenReferenceIsRemoved(@Nonnull ReferenceIndexType indexType) {
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
	void shouldKeepCompoundAcrossArchiveAndRestore(@Nonnull ReferenceIndexType indexType) {
		createSchema(indexType);
		createOwnersWithLocale();

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.archiveEntity(ENTITY_OWNER, 1);
		});
		assertCompoundOrder(2, 3, 4);
		assertCompoundEntry(1, 1, false);
		// the archive writes the entry into the archived scope's partition of the target
		assertCompoundEntry(Scope.ARCHIVED, 1, 1, true);

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.restoreEntity(ENTITY_OWNER, 1);
		});
		assertCompoundOrder(BASE_ORDER);
		assertCompoundEntry(1, 1, true);
		// the archived partition had no other owner, so it leaves together with the entry
		assertNull(
			IndexingTestSupport.getReferencedEntityIndex(ownerCollection(), Scope.ARCHIVED, REFERENCE_ITEMS, 1),
			"The archived partition of target 1 must be gone once its only owner is restored."
		);

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.archiveEntity(ENTITY_OWNER, 1);
		});
		assertCompoundOrder(2, 3, 4);
	}

	@DisplayName("Should reorder by the compound when a reference attribute changes")
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = ReferenceIndexType.class, names = {"FOR_FILTERING", "FOR_FILTERING_AND_PARTITIONING"})
	void shouldReorderByCompoundWhenReferenceAttributeChanges(@Nonnull ReferenceIndexType indexType) {
		createSchema(indexType);
		createOwnersWithLocale();

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			setItem(session, 1, 1, 0, "b");
		});
		assertCompoundOrder(1, 2, 3, 4);

		// owner 3 joins owner 1 at order 0 with a label that sorts it first - an update that skipped the removal or
		// the insertion on the index route would leave it where it was
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			setItem(session, 3, 2, 0, "c");
		});
		assertCompoundOrder(3, 1, 2, 4);
	}

	@DisplayName("Should keep the compound consistent when the reference indexing level changes on existing data")
	@ParameterizedTest(name = "to {0}")
	@EnumSource(value = ReferenceIndexType.class, names = {"FOR_FILTERING", "FOR_FILTERING_AND_PARTITIONING"})
	void shouldKeepCompoundConsistentWhenIndexingLevelChanges(@Nonnull ReferenceIndexType targetIndexType) {
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

	@DisplayName("Should keep the entity-level compound in the reduced indexes of a partitioned reference only")
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = ReferenceIndexType.class, names = {"FOR_FILTERING", "FOR_FILTERING_AND_PARTITIONING"})
	void shouldKeepEntityLevelCompoundInPartitioningIndexesOnly(@Nonnull ReferenceIndexType indexType) {
		createSchema(
			indexType,
			owner -> owner
				.withAttribute(ATTRIBUTE_RANK, Integer.class, thatIs -> thatIs.nullable())
				.withSortableAttributeCompound(
					COMPOUND_NAME,
					new AttributeElement[]{
						new AttributeElement(ATTRIBUTE_NAME, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_RANK, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					},
					thatIs -> thatIs.indexedInScope(BOTH_SCOPES)
				)
		);
		createOwnersWithLocale();
		final boolean partitioned = indexType == ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING;
		assertEntry(requireReducedIndex(Scope.LIVE, 1), ENTITY_COMPOUND_KEY, 1, partitioned, "target 1");
		assertEntry(requireReducedIndex(Scope.LIVE, 1), ENTITY_COMPOUND_KEY, 2, partitioned, "target 1");

		// owner 1 loses the locale - its reference label and its name go in one upsert
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(ENTITY_OWNER, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.updateReference(
						REFERENCE_ITEMS, 1, whichIs -> whichIs.removeAttribute(ATTRIBUTE_LABEL, LOCALE)
					)
					.removeAttribute(ATTRIBUTE_NAME, LOCALE)
					.upsertVia(session);
			}
		);
		assertEntry(requireReducedIndex(Scope.LIVE, 1), ENTITY_COMPOUND_KEY, 1, false, "target 1");
		assertEntry(requireReducedIndex(Scope.LIVE, 1), ENTITY_COMPOUND_KEY, 2, partitioned, "target 1");
	}

	@DisplayName("Should keep the group index free of compound entries while the owner gains and loses the locale")
	@ParameterizedTest(name = "{0} in {1}")
	@MethodSource("levelsInBothStates")
	void shouldKeepGroupIndexFreeOfCompoundEntriesWhenOwnerLocaleChanges(
		@Nonnull ReferenceIndexType indexType,
		@Nonnull CatalogState state
	) {
		createGroupedSchema(indexType);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createGroupedOwner(session, false);
				createGroupedOwner(session, OTHER_OWNER, false);
			}
		);
		goLiveIf(state);
		assertCompoundEntry(GROUPED_TARGET, GROUPED_OWNER, false);
		assertNoGroupCompoundEntries();

		// the owner gains the locale through an entity attribute, the reference is left untouched
		setGroupedOwnerName("owner");
		assertCompoundEntry(GROUPED_TARGET, GROUPED_OWNER, true);
		assertNoGroupCompoundEntries();

		// and loses it again
		setGroupedOwnerName(null);
		assertCompoundEntry(GROUPED_TARGET, GROUPED_OWNER, false);
		assertNoGroupCompoundEntries();

		// the reference is removed while the owner has the locale - the other owner keeps both indexes alive
		setGroupedOwnerName("owner");
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(ENTITY_OWNER, GROUPED_OWNER, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.removeReference(REFERENCE_ITEMS, GROUPED_TARGET)
					.upsertVia(session);
			}
		);
		assertCompoundEntry(GROUPED_TARGET, GROUPED_OWNER, false);
		assertNoGroupCompoundEntries();

		// and added again while the owner has the locale - the insertion of a reference indexes the owner's locales
		// into the group index too
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(ENTITY_OWNER, GROUPED_OWNER, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(
						REFERENCE_ITEMS, GROUPED_TARGET,
						whichIs -> whichIs.setGroup(ENTITY_GROUP, GROUP_PK).setAttribute(ATTRIBUTE_ORDER, GROUPED_OWNER)
					)
					.upsertVia(session);
			}
		);
		assertCompoundEntry(GROUPED_TARGET, GROUPED_OWNER, true);
		assertNoGroupCompoundEntries();

		setGroupedOwnerName(null);
		assertCompoundEntry(GROUPED_TARGET, GROUPED_OWNER, false);
		assertNoGroupCompoundEntries();
	}

	@DisplayName("Should index the compound once in each duplicate partition when the owner gains the locale")
	@ParameterizedTest(name = "{0} in {1}")
	@MethodSource("levelsInBothStates")
	void shouldIndexCompoundOnceInEachDuplicatePartitionWhenOwnerGainsLocale(
		@Nonnull ReferenceIndexType indexType,
		@Nonnull CatalogState state
	) {
		createDuplicateSchema(indexType);
		createDuplicateOwner(false);
		goLiveIf(state);
		assertDuplicateCompoundEntries(false);

		// both duplicate references resolve to the same two partitions, each of which must receive the entry once -
		// a second insertion would be refused by the sort index
		setDuplicateOwnerName("owner 1");
		assertDuplicateCompoundEntries(true);
	}

	@DisplayName("Should drop the compound from each duplicate partition when the owner loses the locale")
	@ParameterizedTest(name = "{0} in {1}")
	@MethodSource("levelsInBothStates")
	void shouldDropCompoundFromEachDuplicatePartitionWhenOwnerLosesLocale(
		@Nonnull ReferenceIndexType indexType,
		@Nonnull CatalogState state
	) {
		createDuplicateSchema(indexType);
		createDuplicateOwner(true);
		goLiveIf(state);
		assertDuplicateCompoundEntries(true);

		// each partition must lose the entry once - a second removal would find the sort index already dropped
		setDuplicateOwnerName(null);
		assertDuplicateCompoundEntries(false);

		// and regaining the locale must find both partitions clean
		setDuplicateOwnerName("owner 1");
		assertDuplicateCompoundEntries(true);
	}

	/**
	 * Both indexing levels of the reference, each in both catalog states.
	 */
	@Nonnull
	private static Stream<Arguments> levelsInBothStates() {
		return Stream.of(
			ReferenceIndexType.FOR_FILTERING, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING
		).flatMap(
			level -> Stream.of(CatalogState.WARMING_UP, CatalogState.ALIVE).map(state -> Arguments.of(level, state))
		);
	}

	/**
	 * Moves the catalog to `ALIVE` when the scenario runs in that state; a `WARMING_UP` scenario keeps writing in bulk.
	 */
	private void goLiveIf(@Nonnull CatalogState state) {
		if (state == CatalogState.ALIVE) {
			this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		}
	}

	/**
	 * Defines the target, group and owner schemas; the reference is grouped, indexed at `indexType` with both the
	 * referenced entity and the group component in both scopes, and carries the localized compound.
	 */
	private void createGroupedSchema(@Nonnull ReferenceIndexType indexType) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_TARGET)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(ENTITY_GROUP)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(ENTITY_OWNER)
					.withoutGeneratedPrimaryKey()
					.withLocale(LOCALE)
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().nullable())
					.withReferenceToEntity(
						REFERENCE_ITEMS, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
						whichIs -> withCompound(
							applyIndexType(whichIs, indexType)
								.withGroupTypeRelatedToEntity(ENTITY_GROUP)
								.indexedWithComponentsInScope(
									Scope.LIVE,
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.indexedWithComponentsInScope(
									Scope.ARCHIVED,
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
						)
					)
					.updateVia(session);
			}
		);
	}

	/**
	 * Writes {@link #GROUPED_OWNER} with its grouped reference - see {@link #createGroupedOwner(EvitaSessionContract,
	 * int, boolean)}.
	 */
	private static void createGroupedOwner(@Nonnull EvitaSessionContract session, boolean withLocale) {
		session.createNewEntity(ENTITY_TARGET, GROUPED_TARGET).upsertVia(session);
		session.createNewEntity(ENTITY_GROUP, GROUP_PK).upsertVia(session);
		createGroupedOwner(session, GROUPED_OWNER, withLocale);
	}

	/**
	 * Writes an owner referencing {@link #GROUPED_TARGET} in the group {@link #GROUP_PK} with a non-localized `order`
	 * only, so the localized compound entry exists exactly when the owner has the locale; with `withLocale` the owner
	 * gets it through its localized name. The owner, the target and the group have distinct primary keys, so no
	 * lookup by the wrong one of them can find the right data by coincidence.
	 */
	private static void createGroupedOwner(
		@Nonnull EvitaSessionContract session,
		int owner,
		boolean withLocale
	) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_OWNER, owner);
		if (withLocale) {
			builder.setAttribute(ATTRIBUTE_NAME, LOCALE, "owner " + owner);
		}
		builder.setReference(
			REFERENCE_ITEMS, GROUPED_TARGET,
			whichIs -> whichIs.setGroup(ENTITY_GROUP, GROUP_PK).setAttribute(ATTRIBUTE_ORDER, owner)
		);
		builder.upsertVia(session);
	}

	/**
	 * Sets the localized name of {@link #GROUPED_OWNER}, or removes it when `name` is `null`.
	 */
	private void setGroupedOwnerName(@Nullable String name) {
		setOwnerName(GROUPED_OWNER, name);
	}

	/**
	 * Asserts that the live group index of {@link #GROUP_PK} exists and keeps no sort index of the compound - a group
	 * index maintains no sort indexes, so the locale paths reaching it must neither write an entry nor fail on one.
	 */
	private void assertNoGroupCompoundEntries() {
		final EntityIndex groupIndex = IndexingTestSupport.getReferencedGroupEntityIndex(
			ownerCollection(), Scope.LIVE, REFERENCE_ITEMS, GROUP_PK
		);
		assertNotNull(groupIndex, "The group index of group " + GROUP_PK + " must exist.");
		assertNull(
			groupIndex.getSortIndex(REFERENCE_COMPOUND_KEY),
			"The group index of group " + GROUP_PK + " must keep no sort index of the compound."
		);
	}

	/**
	 * Sets the localized name of `owner`, or removes it when `name` is `null`, in its own session.
	 */
	private void setOwnerName(int owner, @Nullable String name) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityBuilder builder = session.getEntity(ENTITY_OWNER, owner, entityFetchAllContent())
					.orElseThrow()
					.openForWrite();
				if (name == null) {
					builder.removeAttribute(ATTRIBUTE_NAME, LOCALE);
				} else {
					builder.setAttribute(ATTRIBUTE_NAME, LOCALE, name);
				}
				builder.upsertVia(session);
			}
		);
	}

	/**
	 * Defines the target and owner schemas; the reference allows duplicates told apart by the representative
	 * `country`, is indexed at `indexType` in both scopes and carries the localized compound.
	 */
	private void createDuplicateSchema(@Nonnull ReferenceIndexType indexType) {
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
						REFERENCE_ITEMS, ENTITY_TARGET, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
						whichIs -> withCompound(
							applyIndexType(whichIs, indexType)
								.withAttribute(
									ATTRIBUTE_COUNTRY, String.class, thatIs -> thatIs.filterable().representative()
								)
						)
					)
					.updateVia(session);
			}
		);
	}

	/**
	 * Writes owner 1 and then, one session each, a reference to {@link #DUPLICATE_TARGET} per country of
	 * {@link #DUPLICATE_COUNTRIES}, each with its own non-localized `order`; with `withLocale` the owner gets the
	 * locale through its name. Each reference is added in a session of its own, so that each partition is populated by
	 * the insertion of its own reference rather than by a locale change of an owner that already has both.
	 */
	private void createDuplicateOwner(boolean withLocale) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(ENTITY_TARGET, DUPLICATE_TARGET).upsertVia(session);
				final EntityBuilder builder = session.createNewEntity(ENTITY_OWNER, 1);
				if (withLocale) {
					builder.setAttribute(ATTRIBUTE_NAME, LOCALE, "owner 1");
				}
				builder.upsertVia(session);
			}
		);
		for (int i = 0; i < DUPLICATE_COUNTRIES.length; i++) {
			final String country = DUPLICATE_COUNTRIES[i];
			final int order = i + 1;
			this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(ENTITY_OWNER, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setOrUpdateReference(
							REFERENCE_ITEMS, DUPLICATE_TARGET, Functions.alwaysFalse(),
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_COUNTRY, country)
								.setAttribute(ATTRIBUTE_ORDER, order)
						)
						.upsertVia(session);
				}
			);
		}
	}

	/**
	 * Sets the localized name of the owner of the duplicate references, or removes it when `name` is `null`.
	 */
	private void setDuplicateOwnerName(@Nullable String name) {
		setOwnerName(1, name);
	}

	/**
	 * Asserts whether every live partition of {@link #DUPLICATE_TARGET} - one per country - holds a per-locale
	 * compound entry of owner 1.
	 */
	private void assertDuplicateCompoundEntries(boolean expectedPresent) {
		final EntityCollection collection = ownerCollection();
		for (String country : DUPLICATE_COUNTRIES) {
			final EntityIndex partition = collection.getIndexByKeyIfExists(
				new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY,
					Scope.LIVE,
					new RepresentativeReferenceKey(
						new ReferenceKey(REFERENCE_ITEMS, DUPLICATE_TARGET), new Serializable[]{country}
					)
				)
			);
			assertNotNull(partition, "The partition of target " + DUPLICATE_TARGET + " in " + country + " must exist.");
			assertEntry(partition, REFERENCE_COMPOUND_KEY, 1, expectedPresent, "the " + country + " partition");
		}
	}

	/**
	 * Adds the sortable `order` and localized `label` attributes and the compound over them to the reference.
	 */
	@Nonnull
	private static ReferenceSchemaBuilder withCompound(@Nonnull ReferenceSchemaBuilder builder) {
		return builder
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
			);
	}

	/**
	 * Defines the target and owner schemas; the reference is indexed at `indexType` in both scopes.
	 */
	private void createSchema(@Nonnull ReferenceIndexType indexType) {
		createSchema(indexType, UnaryOperator.identity());
	}

	/**
	 * Defines the target and owner schemas; the reference is indexed at `indexType` in both scopes, and `ownerSchema`
	 * adds whatever else the scenario needs on the owner.
	 */
	private void createSchema(
		@Nonnull ReferenceIndexType indexType,
		@Nonnull UnaryOperator<EntitySchemaBuilder> ownerSchema
	) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_TARGET)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				ownerSchema.apply(
						session.defineEntitySchema(ENTITY_OWNER)
							.withoutGeneratedPrimaryKey()
							.withLocale(LOCALE)
							.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().nullable())
							.withReferenceToEntity(
								REFERENCE_ITEMS, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
								whichIs -> withCompound(applyIndexType(whichIs, indexType))
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
		assertCompoundEntry(Scope.LIVE, target, owner, expectedPresent);
	}

	/**
	 * Asserts whether the reduced index of `target` in `scope` holds a per-locale compound entry of `owner`. The index
	 * must exist, so an absent entry is never mistaken for an absent index.
	 */
	private void assertCompoundEntry(@Nonnull Scope scope, int target, int owner, boolean expectedPresent) {
		assertEntry(
			requireReducedIndex(scope, target), REFERENCE_COMPOUND_KEY, owner, expectedPresent, "target " + target
		);
	}

	/**
	 * Returns the reduced index of `target` in `scope`, which must exist.
	 */
	@Nonnull
	private EntityIndex requireReducedIndex(@Nonnull Scope scope, int target) {
		final EntityIndex reducedIndex = IndexingTestSupport.getReferencedEntityIndex(
			ownerCollection(), scope, REFERENCE_ITEMS, target
		);
		assertNotNull(reducedIndex, "The " + scope + " reduced index of target " + target + " must exist.");
		return reducedIndex;
	}

	/**
	 * Asserts whether the sort index of `index` under `key` holds an entry of `owner`; `indexName` names the index in
	 * the failure message.
	 */
	private static void assertEntry(
		@Nonnull EntityIndex index,
		@Nonnull AttributeIndexKey key,
		int owner,
		boolean expectedPresent,
		@Nonnull String indexName
	) {
		final SortIndex sortIndex = index.getSortIndex(key);
		final boolean present = sortIndex != null &&
			Arrays.stream(sortIndex.getSortedRecords()).anyMatch(it -> it == owner);
		if (expectedPresent) {
			assertTrue(present, "Owner " + owner + " must have a compound entry in " + indexName + ".");
		} else {
			assertFalse(present, "Owner " + owner + " must have no compound entry in " + indexName + ".");
		}
	}

	/**
	 * Returns the owner collection of the test catalog.
	 */
	@Nonnull
	private EntityCollection ownerCollection() {
		return (EntityCollection) this.evita.getCatalogInstance(TEST_CATALOG)
			.orElseThrow()
			.getCollectionForEntity(ENTITY_OWNER)
			.orElseThrow();
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
