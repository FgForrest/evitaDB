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

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the reduced index of a reference added or removed in an entity mutation ends up holding exactly the
 * locales the owner has after the mutation, whichever order the mutation lists its local mutations in.
 *
 * The entity body gains or loses a locale the moment its first or last localized value lands, while the indexes learn
 * about the change only at the end of the entity mutation, through the references the owner has then. A reference
 * added or removed in the same mutation populates or clears its reduced index from the locales the body has at that
 * moment, so when a locale change precedes the reference change, the two steps must agree on which of them takes care
 * of that locale - otherwise the index is asked to remove a localized compound it never received, or receives one
 * twice, or keeps one the owner no longer has.
 *
 * The entity builder sorts removals before upserts, which hides most of the orders; a mutation built by hand - by a
 * client or a replayed write-ahead log - keeps the order it was given, so the scenarios list the local mutations
 * explicitly. The builder order is not safe either: it removes the last value of a locale before it adds a reference,
 * and the attributes of the new reference then write their compounds in the locales before the mutation - the
 * locales every reduced index holds until the end of the mutation - into the new partition too.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reduced index locales whatever order one entity mutation lists its changes in")
@Tag(INDEXING)
@Tag(REFERENCE)
class ReducedIndexLocaleMutationOrderTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String ENTITY_CATEGORY = "category";
	private static final String REF_BRAND = "brand";
	private static final String REF_CATEGORIES = "categories";
	private static final String REF_PRODUCTS = "products";
	private static final String REF_OUTLETS = "outlets";
	private static final String ATTR_TITLE = "title";
	private static final String ATTR_NAME = "name";
	private static final String ATTR_CODE = "code";
	private static final String ATTR_LABEL = "label";
	private static final String ATTR_PRIORITY = "priority";
	private static final String ATTR_ORDER = "order";
	private static final String ATTR_COUNTRY = "country";
	private static final String ASSOCIATED_DATA_NOTE = "note";
	private static final String COMPOUND_NAME_CODE = "nameCode";
	private static final String COMPOUND_LABEL_PRIORITY = "labelPriority";
	private static final String COMPOUND_LABEL_AND_ORDER = "labelAndOrder";
	private static final Locale LOCALE = Locale.ENGLISH;
	private static final Locale OTHER_LOCALE = Locale.GERMAN;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReducedIndexLocaleMutationOrderTest");
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
		cleanupTestPaths(this.paths);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a category drops a reflected reference after another reflected reference enters a new locale")
	void shouldRemoveReflectedReferenceWhenAnotherOneEntersNewLocaleFirst(CatalogState state) {
		prepareReflectedCategories(state);
		assertEquals(
			Set.of(COMPOUND_LABEL_AND_ORDER),
			this.evita.queryCatalog(
				TEST_CATALOG,
				(Function<EvitaSessionContract, Set<String>>) session ->
					session.getEntitySchemaOrThrowException(ENTITY_CATEGORY)
						.getReferenceOrThrowException(REF_PRODUCTS)
						.getSortableAttributeCompounds()
						.keySet()
			),
			"the reflected reference must inherit the localized compound"
		);

		tx(session -> {
			final ReferenceKey remainingKey = session.getEntity(ENTITY_CATEGORY, 1, referenceContentAll())
				.orElseThrow()
				.getReference(REF_PRODUCTS, 1)
				.orElseThrow()
				.getReferenceKey();
			session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_CATEGORY, 1, EntityExistence.MUST_EXIST,
					List.of(
						new ReferenceAttributeMutation(
							remainingKey, new UpsertAttributeMutation(ATTR_LABEL, OTHER_LOCALE, "Etikett")
						),
						new RemoveReferenceMutation(REF_PRODUCTS, 2)
					)
				)
			);
		});

		assertNull(
			partitionOf(ENTITY_CATEGORY, REF_PRODUCTS, 2),
			"the partition its only owner left must be swept"
		);
		final EntityIndex remaining = partitionOf(ENTITY_CATEGORY, REF_PRODUCTS, 1);
		assertNotNull(remaining, "the partition of the remaining reference must exist");
		assertEquals(
			Set.of(
				new AttributeIndexKey(REF_PRODUCTS, COMPOUND_LABEL_AND_ORDER, LOCALE),
				new AttributeIndexKey(REF_PRODUCTS, COMPOUND_LABEL_AND_ORDER, OTHER_LOCALE)
			),
			remaining.getSortIndexes()
		);
		for (AttributeIndexKey key : remaining.getSortIndexes()) {
			assertArrayEquals(new int[]{1}, remaining.getSortIndex(key).getSortedRecords(), "records of " + key);
		}
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner drops a reference after a localized entity attribute puts it in a new locale")
	void shouldRemoveReferenceWhenEntityAttributeEntersNewLocaleFirst(CatalogState state) {
		prepareProducts(state);

		for (int pk = 1; pk <= 2; pk++) {
			upsertProduct(
				pk,
				new UpsertAttributeMutation(ATTR_TITLE, OTHER_LOCALE, "Titel" + pk),
				new RemoveReferenceMutation(REF_BRAND, 1)
			);
			if (pk == 1) {
				assertPartitionHolds(1, new int[]{2}, Map.of(LOCALE, new int[]{2}));
			}
		}
		assertNull(partitionOf(ENTITY_PRODUCT, REF_BRAND, 1), "the partition its last owner left must be swept");
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner drops a reference after the attribute of another reference puts it in a new locale")
	void shouldRemoveReferenceWhenOtherReferenceAttributeEntersNewLocaleFirst(CatalogState state) {
		prepareProducts(state);
		tx(session -> fetchProduct(session, 1)
			.openForWrite()
			.setReference(REF_BRAND, 2, whichIs -> whichIs.setAttribute(ATTR_PRIORITY, 20))
			.upsertVia(session));

		upsertProduct(
			1,
			new ReferenceAttributeMutation(
				new ReferenceKey(REF_BRAND, 2), new UpsertAttributeMutation(ATTR_LABEL, OTHER_LOCALE, "Etikett")
			),
			new RemoveReferenceMutation(REF_BRAND, 1)
		);

		assertPartitionHolds(1, new int[]{2}, Map.of(LOCALE, new int[]{2}));
		assertPartitionHolds(2, new int[]{1}, Map.of(LOCALE, new int[]{1}, OTHER_LOCALE, new int[]{1}));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner adds a reference after a localized entity attribute puts it in a new locale")
	void shouldInsertReferenceWhenEntityAttributeEntersNewLocaleFirst(CatalogState state) {
		prepareProducts(state);

		upsertProduct(
			1,
			new UpsertAttributeMutation(ATTR_TITLE, OTHER_LOCALE, "Titel1"),
			new InsertReferenceMutation(new ReferenceKey(REF_BRAND, 2)),
			new ReferenceAttributeMutation(
				new ReferenceKey(REF_BRAND, 2), new UpsertAttributeMutation(ATTR_PRIORITY, 20)
			)
		);

		assertPartitionHolds(1, new int[]{1, 2}, Map.of(LOCALE, new int[]{1, 2}, OTHER_LOCALE, new int[]{1}));
		assertPartitionHolds(2, new int[]{1}, Map.of(LOCALE, new int[]{1}, OTHER_LOCALE, new int[]{1}));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner leaves its only locale and then adds a reference")
	void shouldInsertReferenceWhenOwnerLeavesItsLocaleFirst(CatalogState state) {
		prepareProducts(state);

		upsertProduct(
			1,
			new RemoveAttributeMutation(ATTR_TITLE, LOCALE),
			new InsertReferenceMutation(new ReferenceKey(REF_BRAND, 2)),
			new ReferenceAttributeMutation(
				new ReferenceKey(REF_BRAND, 2), new UpsertAttributeMutation(ATTR_PRIORITY, 20)
			)
		);

		assertPartitionHolds(1, new int[]{1, 2}, Map.of(LOCALE, new int[]{2}));
		assertPartitionHolds(2, new int[]{1}, Map.of());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner adds a reference and then leaves its only locale")
	void shouldInsertReferenceWhenOwnerLeavesItsLocaleAfterwards(CatalogState state) {
		prepareProducts(state);

		upsertProduct(
			1,
			new InsertReferenceMutation(new ReferenceKey(REF_BRAND, 2)),
			new ReferenceAttributeMutation(
				new ReferenceKey(REF_BRAND, 2), new UpsertAttributeMutation(ATTR_PRIORITY, 20)
			),
			new RemoveAttributeMutation(ATTR_TITLE, LOCALE)
		);

		assertPartitionHolds(1, new int[]{1, 2}, Map.of(LOCALE, new int[]{2}));
		assertPartitionHolds(2, new int[]{1}, Map.of());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner leaves its only locale and adds a reference in the order the entity builder produces")
	void shouldInsertReferenceWhenOwnerLeavesItsLocaleThroughBuilder(CatalogState state) {
		prepareProducts(state);

		tx(session -> fetchProduct(session, 1)
			.openForWrite()
			.setReference(REF_BRAND, 2, whichIs -> whichIs.setAttribute(ATTR_PRIORITY, 20))
			.removeAttribute(ATTR_TITLE, LOCALE)
			.upsertVia(session));

		assertPartitionHolds(1, new int[]{1, 2}, Map.of(LOCALE, new int[]{2}));
		assertPartitionHolds(2, new int[]{1}, Map.of());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner leaves its only locale and adds a duplicate reference opening a partition of its own")
	void shouldInsertDuplicateReferenceWhenOwnerLeavesItsLocaleThroughBuilder(CatalogState state) {
		prepareProducts(state);
		tx(session -> fetchProduct(session, 1)
			.openForWrite()
			.setOrUpdateReference(
				REF_OUTLETS, 1, Functions.alwaysFalse(),
				whichIs -> whichIs.setAttribute(ATTR_COUNTRY, "CZ").setAttribute(ATTR_PRIORITY, 10)
			)
			.upsertVia(session));
		assertPartitionHolds(REF_OUTLETS, outletPartition("CZ"), new int[]{1}, Map.of(LOCALE, new int[]{1}));

		tx(session -> fetchProduct(session, 1)
			.openForWrite()
			.setOrUpdateReference(
				REF_OUTLETS, 1, Functions.alwaysFalse(),
				whichIs -> whichIs.setAttribute(ATTR_COUNTRY, "DE").setAttribute(ATTR_PRIORITY, 20)
			)
			.removeAttribute(ATTR_TITLE, LOCALE)
			.upsertVia(session));

		assertPartitionHolds(REF_OUTLETS, outletPartition("CZ"), new int[]{1}, Map.of());
		assertPartitionHolds(REF_OUTLETS, outletPartition("DE"), new int[]{1}, Map.of());
		assertPartitionHolds(1, new int[]{1, 2}, Map.of(LOCALE, new int[]{2}));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner holding a locale only through associated data puts an attribute in it and adds a reference")
	void shouldInsertReferenceWhenAttributeEntersLocaleHeldByAssociatedDataFirst(CatalogState state) {
		prepareProductWithNoteInOtherLocale(state);

		upsertProduct(
			1,
			new UpsertAttributeMutation(ATTR_TITLE, OTHER_LOCALE, "Titel1"),
			new InsertReferenceMutation(new ReferenceKey(REF_BRAND, 2)),
			new ReferenceAttributeMutation(
				new ReferenceKey(REF_BRAND, 2), new UpsertAttributeMutation(ATTR_PRIORITY, 20)
			)
		);

		assertPartitionHolds(1, new int[]{1, 2}, Map.of(LOCALE, new int[]{1, 2}, OTHER_LOCALE, new int[]{1}));
		assertPartitionHolds(2, new int[]{1}, Map.of(LOCALE, new int[]{1}, OTHER_LOCALE, new int[]{1}));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName(
		"an owner holding a locale only through associated data adds a reference and then puts an attribute in it"
	)
	void shouldInsertReferenceWhenAttributeEntersLocaleHeldByAssociatedDataAfterwards(CatalogState state) {
		prepareProductWithNoteInOtherLocale(state);

		upsertProduct(
			1,
			new InsertReferenceMutation(new ReferenceKey(REF_BRAND, 2)),
			new ReferenceAttributeMutation(
				new ReferenceKey(REF_BRAND, 2), new UpsertAttributeMutation(ATTR_PRIORITY, 20)
			),
			new UpsertAttributeMutation(ATTR_TITLE, OTHER_LOCALE, "Titel1")
		);

		assertPartitionHolds(1, new int[]{1, 2}, Map.of(LOCALE, new int[]{1, 2}, OTHER_LOCALE, new int[]{1}));
		assertPartitionHolds(2, new int[]{1}, Map.of(LOCALE, new int[]{1}, OTHER_LOCALE, new int[]{1}));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner holding a locale only through associated data puts an attribute in it and drops a reference")
	void shouldRemoveReferenceWhenAttributeEntersLocaleHeldByAssociatedDataFirst(CatalogState state) {
		prepareProductWithNoteInOtherLocale(state);
		upsertProduct(
			1,
			new InsertReferenceMutation(new ReferenceKey(REF_BRAND, 2)),
			new ReferenceAttributeMutation(
				new ReferenceKey(REF_BRAND, 2), new UpsertAttributeMutation(ATTR_PRIORITY, 20)
			)
		);

		upsertProduct(
			1,
			new UpsertAttributeMutation(ATTR_TITLE, OTHER_LOCALE, "Titel1"),
			new RemoveReferenceMutation(REF_BRAND, 1)
		);

		assertPartitionHolds(1, new int[]{2}, Map.of(LOCALE, new int[]{2}));
		assertPartitionHolds(2, new int[]{1}, Map.of(LOCALE, new int[]{1}, OTHER_LOCALE, new int[]{1}));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner entering a locale through associated data only adds a reference")
	void shouldInsertReferenceWhenAssociatedDataEntersNewLocaleFirst(CatalogState state) {
		prepareProducts(state);

		upsertProduct(
			1,
			new UpsertAssociatedDataMutation(ASSOCIATED_DATA_NOTE, OTHER_LOCALE, "Notiz"),
			new InsertReferenceMutation(new ReferenceKey(REF_BRAND, 2)),
			new ReferenceAttributeMutation(
				new ReferenceKey(REF_BRAND, 2), new UpsertAttributeMutation(ATTR_PRIORITY, 20)
			)
		);

		assertPartitionHolds(
			1, new int[]{1, 2},
			Map.of(LOCALE, new int[]{1, 2}, OTHER_LOCALE, new int[]{1}),
			Map.of(LOCALE, new int[]{1, 2})
		);
		assertPartitionHolds(
			2, new int[]{1},
			Map.of(LOCALE, new int[]{1}, OTHER_LOCALE, new int[]{1}),
			Map.of(LOCALE, new int[]{1})
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName(
		"an owner holding a locale through associated data and an attribute removes the attribute and drops a reference"
	)
	void shouldRemoveReferenceWhenAttributeLeavesLocaleHeldByAssociatedDataFirst(CatalogState state) {
		prepareProductWithNoteInOtherLocale(state);
		upsertProduct(
			1,
			new UpsertAttributeMutation(ATTR_TITLE, OTHER_LOCALE, "Titel1"),
			new InsertReferenceMutation(new ReferenceKey(REF_BRAND, 2)),
			new ReferenceAttributeMutation(
				new ReferenceKey(REF_BRAND, 2), new UpsertAttributeMutation(ATTR_PRIORITY, 20)
			)
		);
		assertPartitionHolds(1, new int[]{1, 2}, Map.of(LOCALE, new int[]{1, 2}, OTHER_LOCALE, new int[]{1}));

		upsertProduct(
			1,
			new RemoveAttributeMutation(ATTR_TITLE, OTHER_LOCALE),
			new RemoveReferenceMutation(REF_BRAND, 1)
		);

		assertPartitionHolds(1, new int[]{2}, Map.of(LOCALE, new int[]{2}));
		assertPartitionHolds(
			2, new int[]{1},
			Map.of(LOCALE, new int[]{1}, OTHER_LOCALE, new int[]{1}),
			Map.of(LOCALE, new int[]{1})
		);
	}

	/**
	 * Returns the key of the partition of outlet 1 for the given country - the representative attribute telling the
	 * duplicate references to it apart.
	 *
	 * @param country the country of the duplicate reference
	 * @return the partition key
	 */
	@Nonnull
	private static RepresentativeReferenceKey outletPartition(@Nonnull String country) {
		return new RepresentativeReferenceKey(new ReferenceKey(REF_OUTLETS, 1), new Serializable[]{country});
	}

	/**
	 * Applies the local mutations to an existing product in exactly the given order.
	 *
	 * @param productPk primary key of the product
	 * @param mutations the local mutations in the order they are to be applied
	 */
	private void upsertProduct(int productPk, @Nonnull LocalMutation<?, ?>... mutations) {
		tx(session -> session.upsertEntity(
			new EntityUpsertMutation(
				ENTITY_PRODUCT, productPk, EntityExistence.MUST_EXIST,
				mutations
			)
		));
	}

	/**
	 * Fetches a product with all its attributes in all locales and its references.
	 *
	 * @param session   the session to read in
	 * @param productPk primary key of the product
	 * @return the product
	 */
	@Nonnull
	private static SealedEntity fetchProduct(@Nonnull EvitaSessionContract session, int productPk) {
		return session.getEntity(
				ENTITY_PRODUCT, productPk,
				attributeContentAll(), referenceContentAllWithAttributes(), dataInLocalesAll()
			)
			.orElseThrow();
	}

	/**
	 * Asserts that the partition of the given brand holds exactly the given owners - see
	 * {@link #assertPartitionHolds(String, RepresentativeReferenceKey, int[], Map)}.
	 *
	 * @param brandPk        primary key of the brand whose partition is checked
	 * @param expectedOwners primary keys of the products expected in the partition
	 * @param ownersByLocale primary keys of the products expected in each locale; a locale left out holds no owner
	 */
	private void assertPartitionHolds(
		int brandPk,
		@Nonnull int[] expectedOwners,
		@Nonnull Map<Locale, int[]> ownersByLocale
	) {
		assertPartitionHolds(brandPk, expectedOwners, ownersByLocale, ownersByLocale);
	}

	/**
	 * Asserts that the partition of the given brand holds exactly the given owners, telling the locales of the owners'
	 * bodies apart from the locales of their attributes - see
	 * {@link #assertPartitionHolds(String, RepresentativeReferenceKey, int[], Map, Map)}.
	 *
	 * @param brandPk                 primary key of the brand whose partition is checked
	 * @param expectedOwners          primary keys of the products expected in the partition
	 * @param ownersByEntityLocale    primary keys of the products expected in the language bitmap of each locale; a
	 *                                locale left out holds no owner
	 * @param ownersByAttributeLocale primary keys of the products expected in the localized compounds of each locale; a
	 *                                locale left out has no compound sort index
	 */
	private void assertPartitionHolds(
		int brandPk,
		@Nonnull int[] expectedOwners,
		@Nonnull Map<Locale, int[]> ownersByEntityLocale,
		@Nonnull Map<Locale, int[]> ownersByAttributeLocale
	) {
		assertPartitionHolds(
			REF_BRAND, new RepresentativeReferenceKey(new ReferenceKey(REF_BRAND, brandPk)),
			expectedOwners, ownersByEntityLocale, ownersByAttributeLocale
		);
	}

	/**
	 * Asserts that the product partition holds exactly the given owners, and in each locale exactly the given owners in
	 * the language bitmap and in the sort indexes of both localized compounds - every owner carries a value of the
	 * non-localized element of both compounds, so each compound is indexed in every locale its owner has.
	 *
	 * @param referenceName  the reference the partition belongs to
	 * @param partitionKey   the key of the partition
	 * @param expectedOwners primary keys of the products expected in the partition
	 * @param ownersByLocale primary keys of the products expected in each locale; a locale left out holds no owner
	 */
	private void assertPartitionHolds(
		@Nonnull String referenceName,
		@Nonnull RepresentativeReferenceKey partitionKey,
		@Nonnull int[] expectedOwners,
		@Nonnull Map<Locale, int[]> ownersByLocale
	) {
		assertPartitionHolds(referenceName, partitionKey, expectedOwners, ownersByLocale, ownersByLocale);
	}

	/**
	 * Asserts that the product partition holds exactly the given owners, in each locale exactly the given owners in
	 * the language bitmap, which follows the locales of the owners' bodies, and exactly the given owners in the sort
	 * indexes of both localized compounds, which follow the locales of the owners' attributes - every owner carries a
	 * value of the non-localized element of both compounds, so each compound is indexed in every attribute locale its
	 * owner has. The two differ for an owner holding a locale only through its associated data.
	 *
	 * @param referenceName           the reference the partition belongs to
	 * @param partitionKey            the key of the partition
	 * @param expectedOwners          primary keys of the products expected in the partition
	 * @param ownersByEntityLocale    primary keys of the products expected in the language bitmap of each locale; a
	 *                                locale left out holds no owner
	 * @param ownersByAttributeLocale primary keys of the products expected in the localized compounds of each locale; a
	 *                                locale left out has no compound sort index
	 */
	private void assertPartitionHolds(
		@Nonnull String referenceName,
		@Nonnull RepresentativeReferenceKey partitionKey,
		@Nonnull int[] expectedOwners,
		@Nonnull Map<Locale, int[]> ownersByEntityLocale,
		@Nonnull Map<Locale, int[]> ownersByAttributeLocale
	) {
		final EntityIndex partition = partitionOf(ENTITY_PRODUCT, partitionKey);
		assertNotNull(partition, "the partition " + partitionKey + " must exist while it has owners");
		assertArrayEquals(expectedOwners, partition.getAllPrimaryKeys().getArray(), "owners of " + partitionKey);
		final Set<AttributeIndexKey> expectedSortIndexes = CollectionUtils.createHashSet(4);
		for (Locale locale : List.of(LOCALE, OTHER_LOCALE)) {
			assertArrayEquals(
				ownersByEntityLocale.getOrDefault(locale, new int[0]),
				partition.getRecordsWithLanguageFormula(locale).compute().getArray(),
				"owners of " + partitionKey + " in " + locale
			);
			if (ownersByAttributeLocale.getOrDefault(locale, new int[0]).length > 0) {
				expectedSortIndexes.add(new AttributeIndexKey(null, COMPOUND_NAME_CODE, locale));
				expectedSortIndexes.add(new AttributeIndexKey(referenceName, COMPOUND_LABEL_PRIORITY, locale));
			}
		}
		assertEquals(expectedSortIndexes, partition.getSortIndexes(), "sort indexes of " + partitionKey);
		for (AttributeIndexKey key : expectedSortIndexes) {
			assertArrayEquals(
				ownersByAttributeLocale.get(key.locale()),
				partition.getSortIndex(key).getSortedRecords(),
				"records of " + key + " in " + partitionKey
			);
		}
	}

	/**
	 * Resolves the reduced index of the given referenced entity in the given collection.
	 *
	 * @param entityType    the collection owning the reference
	 * @param referenceName the reference
	 * @param referencedPk  primary key of the referenced entity
	 * @return the partition, or `null` when it does not exist
	 */
	@Nullable
	private EntityIndex partitionOf(@Nonnull String entityType, @Nonnull String referenceName, int referencedPk) {
		return partitionOf(entityType, new RepresentativeReferenceKey(new ReferenceKey(referenceName, referencedPk)));
	}

	/**
	 * Resolves the live reduced index with the given key in the given collection.
	 *
	 * @param entityType   the collection owning the reference
	 * @param partitionKey the key of the partition
	 * @return the partition, or `null` when it does not exist
	 */
	@Nullable
	private EntityIndex partitionOf(@Nonnull String entityType, @Nonnull RepresentativeReferenceKey partitionKey) {
		final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection = (EntityCollection) catalog.getCollectionForEntity(entityType)
			.orElseThrow();
		return collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, partitionKey)
		);
	}

	/**
	 * Creates the product schema with a localized compound on the entity and another on its brand reference and on
	 * its outlet reference, which allows duplicates told apart by their country; then two brands and two products in
	 * the partition of the first brand. Each product carries a single localized attribute,
	 * which puts it in the locale, and the non-localized element of both compounds, while the localized elements stay
	 * absent - so both compounds are indexed in the locale only because of the unrelated localized attribute.
	 *
	 * @param state target catalog state
	 */
	private void prepareProducts(@Nonnull CatalogState state) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withLocale(LOCALE, OTHER_LOCALE)
					.withAttribute(ATTR_TITLE, String.class, thatIs -> thatIs.localized().nullable())
					.withAttribute(ATTR_NAME, String.class, thatIs -> thatIs.localized().nullable())
					.withAttribute(ATTR_CODE, String.class, thatIs -> thatIs.nullable())
					.withAssociatedData(ASSOCIATED_DATA_NOTE, String.class, whichIs -> whichIs.localized().nullable())
					.withSortableAttributeCompound(
						COMPOUND_NAME_CODE,
						new AttributeElement(ATTR_NAME, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTR_CODE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					)
					.withReferenceToEntity(
						REF_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.withAttribute(ATTR_LABEL, String.class, thatIs -> thatIs.localized().nullable())
							.withAttribute(ATTR_PRIORITY, Integer.class, thatIs -> thatIs.nullable())
							.withSortableAttributeCompound(
								COMPOUND_LABEL_PRIORITY,
								new AttributeElement(ATTR_LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
								new AttributeElement(ATTR_PRIORITY, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							)
					)
					.withReferenceToEntity(
						REF_OUTLETS, ENTITY_BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.withAttribute(ATTR_COUNTRY, String.class, thatIs -> thatIs.filterable().representative())
							.withAttribute(ATTR_LABEL, String.class, thatIs -> thatIs.localized().nullable())
							.withAttribute(ATTR_PRIORITY, Integer.class, thatIs -> thatIs.nullable())
							.withSortableAttributeCompound(
								COMPOUND_LABEL_PRIORITY,
								new AttributeElement(ATTR_LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
								new AttributeElement(ATTR_PRIORITY, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							)
					)
					.updateVia(session);
				session.createNewEntity(ENTITY_BRAND, 1).upsertVia(session);
				session.createNewEntity(ENTITY_BRAND, 2).upsertVia(session);
				for (int pk = 1; pk <= 2; pk++) {
					session.createNewEntity(ENTITY_PRODUCT, pk)
						.setAttribute(ATTR_TITLE, LOCALE, "title" + pk)
						.setAttribute(ATTR_CODE, "code" + pk)
						.setReference(REF_BRAND, 1, whichIs -> whichIs.setAttribute(ATTR_PRIORITY, 10))
						.upsertVia(session);
				}
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			}
		);
		assertPartitionHolds(1, new int[]{1, 2}, Map.of(LOCALE, new int[]{1, 2}));
	}

	/**
	 * Prepares the products as {@link #prepareProducts(CatalogState)} does and then gives product 1 a localized
	 * associated data in the other locale - which puts the product's body in that locale, while its attributes stay in
	 * the first locale only, so only the language bitmap of its partition learns about the other locale.
	 *
	 * @param state target catalog state
	 */
	private void prepareProductWithNoteInOtherLocale(@Nonnull CatalogState state) {
		prepareProducts(state);
		upsertProduct(1, new UpsertAssociatedDataMutation(ASSOCIATED_DATA_NOTE, OTHER_LOCALE, "Notiz"));
		assertPartitionHolds(
			1, new int[]{1, 2},
			Map.of(LOCALE, new int[]{1, 2}, OTHER_LOCALE, new int[]{1}),
			Map.of(LOCALE, new int[]{1, 2})
		);
	}

	/**
	 * Creates products whose category reference carries an order and a localized label combined in a localized
	 * compound, reflected on the category with all attributes - and so the compound - inherited; then creates products
	 * 1 and 2, both in category 1 with a label in the first locale only.
	 *
	 * @param state target catalog state
	 */
	private void prepareReflectedCategories(@Nonnull CatalogState state) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.defineEntitySchema(ENTITY_CATEGORY).withLocale(LOCALE, OTHER_LOCALE).updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withLocale(LOCALE, OTHER_LOCALE)
					.withReferenceToEntity(
						REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFiltering()
							.withAttribute(ATTR_ORDER, Integer.class, thatIs -> thatIs.nullable())
							.withAttribute(ATTR_LABEL, String.class, thatIs -> thatIs.localized().nullable())
							.withSortableAttributeCompound(
								COMPOUND_LABEL_AND_ORDER,
								new AttributeElement(ATTR_LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
								new AttributeElement(ATTR_ORDER, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
							)
					)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_CATEGORY)
					.withReflectedReferenceToEntity(
						REF_PRODUCTS, ENTITY_PRODUCT, REF_CATEGORIES,
						whichIs -> whichIs.withAttributesInherited().indexedForFiltering()
					)
					.updateVia(session);
				session.createNewEntity(ENTITY_CATEGORY, 1).upsertVia(session);
				for (int pk = 1; pk <= 2; pk++) {
					final int productPk = pk;
					session.createNewEntity(ENTITY_PRODUCT, pk)
						.setReference(
							REF_CATEGORIES, 1,
							whichIs -> whichIs.setAttribute(ATTR_ORDER, productPk)
								.setAttribute(ATTR_LABEL, LOCALE, "label" + productPk)
						)
						.upsertVia(session);
				}
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			}
		);
	}

	/**
	 * Runs one unit of write work against the test catalog - a transaction in `ALIVE`, a plain bulk write in
	 * `WARMING_UP`.
	 *
	 * @param work the write to perform
	 */
	private void tx(@Nonnull Consumer<EvitaSessionContract> work) {
		this.evita.updateCatalog(TEST_CATALOG, work);
	}

	/**
	 * Builds the engine configuration over the test folder, with sessions that never expire.
	 *
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.build();
	}
}
