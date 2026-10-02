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
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
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
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that an owner leaving a partition takes its locale-bound data along, even when the locale itself leaves the
 * owner in the same entity mutation.
 *
 * A localized sortable compound is indexed for every locale the owner has attributes in, whether or not its localized
 * elements carry a value - here both compounds pair an absent localized element with a present non-localized one, so
 * an unrelated localized attribute alone puts them into the locale. The entity body drops a locale as soon as its last
 * value goes, but the indexes learn about it only at the end of the entity mutation, and only through the references
 * the owner still has. A reference removed in the same mutation is no longer one of them, so the partition it leads to
 * has to drop the locale while the reference leaves - otherwise the departed owner stays in the partition's language
 * bitmap and compound sort indexes, and a partition left by all its owners is never swept, because it never looks
 * empty.
 *
 * Both the control - the locale and the reference dropped in separate mutations - and the mirror - a locale entered in
 * the mutation that drops the reference - must stay clean too.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Localized compound residue of an owner departed from a partition")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class DepartedOwnerLocalizedCompoundResidueTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String REF_BRAND = "brand";
	private static final String ATTR_TITLE = "title";
	private static final String ATTR_NAME = "name";
	private static final String ATTR_CODE = "code";
	private static final String ATTR_LABEL = "label";
	private static final String ATTR_PRIORITY = "priority";
	private static final String COMPOUND_NAME_CODE = "nameCode";
	private static final String COMPOUND_LABEL_PRIORITY = "labelPriority";
	private static final Locale LOCALE = Locale.ENGLISH;
	private static final Locale OTHER_LOCALE = Locale.GERMAN;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("DepartedOwnerLocalizedCompoundResidueTest");
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("owners dropping their last value in a locale together with the reference leave nothing behind")
	void shouldLeaveNoResidueWhenLastLocaleValueAndReferenceGoInOneMutation(CatalogState state) {
		prepare(state);
		upsertOwners();
		assertPartitionHolds(1, 2);

		dropTitleAndReferenceTogether(1);
		assertPartitionHolds(2);

		dropTitleAndReferenceTogether(2);
		assertNull(partitionOf(1), "the partition its last owner left must be swept");

		reload();
		assertNull(partitionOf(1), "the reloaded catalog must not bring the partition back");
		assertEquals(2, countProducts(), "the reloaded catalog must serve both owners");
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("owners entering a new locale together with dropping the reference leave nothing behind")
	void shouldLeaveNoResidueWhenNewLocaleValueAndReferenceRemovalGoInOneMutation(CatalogState state) {
		prepare(state);
		upsertOwners();

		for (int pk = 1; pk <= 2; pk++) {
			final int productPk = pk;
			tx(session -> fetchProduct(session, productPk)
				.openForWrite()
				.setAttribute(ATTR_TITLE, OTHER_LOCALE, "titel" + productPk)
				.removeReference(REF_BRAND, 1)
				.upsertVia(session));
		}
		assertNull(partitionOf(1), "the partition its last owner left must be swept");
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner removed outright leaves nothing behind")
	void shouldLeaveNoResidueWhenOwnerIsRemovedOutright(CatalogState state) {
		prepare(state);
		upsertOwners();

		tx(session -> session.deleteEntity(ENTITY_PRODUCT, 1));
		assertPartitionHolds(2);

		tx(session -> session.deleteEntity(ENTITY_PRODUCT, 2));
		assertNull(partitionOf(1), "the partition its last owner left must be swept");
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("owners dropping their last value in a locale and the reference separately leave nothing behind")
	void shouldLeaveNoResidueWhenLastLocaleValueAndReferenceGoInSeparateMutations(CatalogState state) {
		prepare(state);
		upsertOwners();
		for (int pk = 1; pk <= 2; pk++) {
			final int productPk = pk;
			tx(session -> fetchProduct(session, productPk)
				.openForWrite()
				.removeAttribute(ATTR_TITLE, LOCALE)
				.upsertVia(session));
			tx(session -> fetchProduct(session, productPk)
				.openForWrite()
				.removeReference(REF_BRAND, 1)
				.upsertVia(session));
		}
		assertNull(partitionOf(1), "the partition its last owner left must be swept");
	}

	/**
	 * Removes the only localized entity attribute of the product - the one value that keeps the product in the locale -
	 * together with its reference to the brand, in a single entity mutation.
	 *
	 * @param productPk primary key of the product
	 */
	private void dropTitleAndReferenceTogether(int productPk) {
		tx(session -> fetchProduct(session, productPk)
			.openForWrite()
			.removeAttribute(ATTR_TITLE, LOCALE)
			.removeReference(REF_BRAND, 1)
			.upsertVia(session));
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
	 * Asserts that the brand partition holds exactly the given owners - in its owner bitmap, in the language bitmap and
	 * in every sort index of the localized compounds, which are the structures a departed owner's data would linger in.
	 *
	 * @param expectedOwners primary keys of the products expected in the partition
	 */
	private void assertPartitionHolds(int... expectedOwners) {
		final EntityIndex partition = partitionOf(1);
		assertNotNull(partition, "the partition must exist while it has owners");
		assertArrayEquals(expectedOwners, partition.getAllPrimaryKeys().getArray(), "owners of the partition");
		assertArrayEquals(
			expectedOwners,
			partition.getRecordsWithLanguageFormula(LOCALE).compute().getArray(),
			"owners of the partition in " + LOCALE
		);
		final Set<AttributeIndexKey> sortIndexes = partition.getSortIndexes();
		assertEquals(
			Set.of(
				new AttributeIndexKey(null, COMPOUND_NAME_CODE, LOCALE),
				new AttributeIndexKey(REF_BRAND, COMPOUND_LABEL_PRIORITY, LOCALE)
			),
			sortIndexes
		);
		for (AttributeIndexKey key : sortIndexes) {
			assertArrayEquals(expectedOwners, partition.getSortIndex(key).getSortedRecords(), "records of " + key);
		}
	}

	/**
	 * Counts the products the catalog serves.
	 *
	 * @return number of products
	 */
	private int countProducts() {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, Integer>) session ->
				session.queryEntityReference(query(collection(ENTITY_PRODUCT))).getTotalRecordCount()
		);
	}

	/**
	 * Closes the engine and opens it again over the same storage, waiting until the catalog is loaded.
	 */
	private void reload() {
		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(
				() -> !this.evita.management()
					.getCatalogStatistics(TEST_CATALOG, EnumSet.of(CatalogStatisticsComponent.IDENTITY))
					.identity()
					.unusable()
			);
	}

	/**
	 * Creates two products in the same brand partition. Each carries a single localized attribute, which puts it in the
	 * locale, a non-localized element of both localized compounds, and leaves the localized elements absent - so the
	 * compounds are indexed for the locale only because of the unrelated localized attribute.
	 */
	private void upsertOwners() {
		tx(session -> {
			for (int pk = 1; pk <= 2; pk++) {
				session.createNewEntity(ENTITY_PRODUCT, pk)
					.setAttribute(ATTR_TITLE, LOCALE, "title" + pk)
					.setAttribute(ATTR_CODE, "code" + pk)
					.setReference(REF_BRAND, 1, whichIs -> whichIs.setAttribute(ATTR_PRIORITY, 10))
					.upsertVia(session);
			}
		});
	}

	/**
	 * Resolves the partition of the given brand in the product collection.
	 *
	 * @param brandPk primary key of the brand
	 * @return the partition, or `null` when it does not exist
	 */
	@Nullable
	private EntityIndex partitionOf(int brandPk) {
		final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection = (EntityCollection) catalog.getCollectionForEntity(ENTITY_PRODUCT)
			.orElseThrow();
		return collection.getIndexByKeyIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey(REF_BRAND, brandPk))
			)
		);
	}

	/**
	 * Creates the schema and the brand and brings the catalog to the requested state.
	 *
	 * @param state target catalog state
	 */
	private void prepare(@Nonnull CatalogState state) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withLocale(LOCALE, OTHER_LOCALE)
					.withAttribute(ATTR_TITLE, String.class, thatIs -> thatIs.localized().nullable())
					.withAttribute(ATTR_NAME, String.class, thatIs -> thatIs.localized().nullable())
					.withAttribute(ATTR_CODE, String.class, thatIs -> thatIs.nullable())
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
					.updateVia(session);
				session.createNewEntity(ENTITY_BRAND, 1).upsertVia(session);
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
