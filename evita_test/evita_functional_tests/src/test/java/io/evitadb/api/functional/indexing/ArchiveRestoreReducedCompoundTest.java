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
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.SortableAttributeCompoundSchemaBuilder;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static io.evitadb.api.functional.indexing.EvitaIndexingTest.getReferencedEntityIndex;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for a production crash where archiving (or removing) an entity threw
 * `Value ... is not present in the sort index of attribute ...` from {@link SortIndex#removeRecord}.
 *
 * A reference is partitioned (FOR_FILTERING_AND_PARTITIONING) in the LIVE scope only. Its partitioned reduced
 * (per-reference) LIVE index holds the entity-level sortable-attribute compound. After an ARCHIVED to LIVE
 * restore, the compound must be re-inserted into that reduced LIVE index. The transition memoized the active scope
 * only after re-indexing, so the partitioning gate was evaluated against the stale ARCHIVED scope (where the reference
 * is not partitioned): the compound was skipped, leaving the reduced LIVE index without it while the GLOBAL index keeps
 * it. The inconsistency surfaces on the next archive/remove, when the unindex-references path recomputes the compound
 * from the entity and tries to remove it from a reduced index that never received it.
 *
 * The same stale-scope gate also affected the ARCHIVED direction: pre-fix archiving wrongly inserted the compound into
 * the reduced ARCHIVED index even though the reference is only FOR_FILTERING there (and therefore not partitioned).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reduced reference index keeps the entity-level sortable compound across archive/restore")
public class ArchiveRestoreReducedCompoundTest implements EvitaTestSupport {
	private static final String DIR = "archiveRestoreReducedCompoundTest";
	private static final String DIR_EXPORT = "archiveRestoreReducedCompoundTest_export";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_FLAG = "flag";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String COMPOUND_NAME = "flagNameCompound";
	private static final Locale PL = Locale.forLanguageTag("pl");
	private static final int PRODUCT_PK = 100;
	private static final int CATEGORY_PK = 50;

	private Evita evita;

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR);
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR);
	}

	@Nested
	@DisplayName("Restore re-indexes the LIVE-partitioned compound")
	class RestoreReindexingTest {

		@DisplayName("Restore re-indexes a localized sortable compound into the reduced LIVE reference index")
		@Test
		void shouldKeepReducedIndexCompoundWhenLocalizedCompoundIsRestored() {
			createLocalizedSchema();
			createLocalizedData();

			// archive: LIVE -> ARCHIVED, then restore: ARCHIVED -> LIVE
			archive();
			restore();

			// the reduced LIVE category index must contain the entity's compound value after restore
			assertReducedIndexContainsCompound(Scope.LIVE, PL);

			// archiving again must not throw (the unindex-references path removes the compound from the reduced index)
			assertDoesNotThrow(
				ArchiveRestoreReducedCompoundTest.this::archive,
				"Archiving after restore must not fail on a missing reduced-index sortable compound"
			);
		}

		@DisplayName("Restore re-indexes a non-localized sortable compound into the reduced LIVE reference index")
		@Test
		void shouldKeepReducedIndexCompoundWhenNonLocalizedCompoundIsRestored() {
			createNonLocalizedSchema();
			createNonLocalizedData();

			// archive: LIVE -> ARCHIVED, then restore: ARCHIVED -> LIVE
			archive();
			restore();

			// the reduced LIVE category index must contain the compound stored under the null locale after restore
			assertReducedIndexContainsCompound(Scope.LIVE, null);

			// archiving again exercises the null-locale unindex path, which must not throw
			assertDoesNotThrow(
				ArchiveRestoreReducedCompoundTest.this::archive,
				"Archiving after restore must not fail on a missing reduced-index non-localized sortable compound"
			);
		}
	}

	@Nested
	@DisplayName("Archive must not partition a FOR_FILTERING-only reference")
	class ArchiveAbsenceTest {

		@DisplayName("Archive does not insert the compound into the non-partitioned reduced ARCHIVED reference index")
		@Test
		void shouldNotInsertCompoundIntoReducedArchivedIndexWhenReferenceIsNotPartitioned() {
			createLocalizedSchema();
			createLocalizedData();

			// archive: LIVE -> ARCHIVED (the reference is only FOR_FILTERING in ARCHIVED, hence not partitioned)
			archive();

			// the reduced ARCHIVED index must NOT hold the compound; pre-fix archive wrongly inserted it
			assertReducedIndexDoesNotContainCompound(Scope.ARCHIVED, PL);
		}
	}

	@Nested
	@DisplayName("Unindex paths after restore stay consistent")
	class PostRestoreUnindexTest {

		@DisplayName("Deleting an entity after restore does not fail on a missing reduced-index compound")
		@Test
		void shouldDeleteEntityAfterRestoreWithoutFailure() {
			createLocalizedSchema();
			createLocalizedData();

			archive();
			restore();

			// deletion follows the same unindex-references path as archiving and crashed in production pre-fix
			assertDoesNotThrow(
				() -> ArchiveRestoreReducedCompoundTest.this.evita.updateCatalog(
					TEST_CATALOG, session -> { session.deleteEntity(Entities.PRODUCT, PRODUCT_PK); }
				),
				"Deleting after restore must not fail on a missing reduced-index sortable compound"
			);
		}

		@DisplayName("Repeated archive/restore cycles keep the reduced LIVE compound and never fail on re-archive")
		@Test
		void shouldRemainConsistentAcrossRepeatedArchiveRestoreCycles() {
			createLocalizedSchema();
			createLocalizedData();

			// three full cycles: every restore must leave the reduced LIVE index with the compound
			for (int cycle = 0; cycle < 3; cycle++) {
				archive();
				restore();
				assertReducedIndexContainsCompound(Scope.LIVE, PL);
			}

			// a final archive after the last restore must still not throw
			assertDoesNotThrow(
				ArchiveRestoreReducedCompoundTest.this::archive,
				"Archiving after repeated restore cycles must not fail on a missing reduced-index sortable compound"
			);
		}
	}

	@Nested
	@DisplayName("Symmetric partitioning regression guard")
	class SymmetricPartitioningTest {

		@DisplayName("Both reduced indexes keep the compound when the reference is partitioned in both scopes")
		@Test
		void shouldKeepCompoundInBothReducedIndexesWhenPartitionedInBothScopes() {
			createBothScopesSchema();
			createLocalizedData();

			archive();
			restore();

			// after restore the reduced LIVE index must contain the compound
			assertReducedIndexContainsCompound(Scope.LIVE, PL);

			assertDoesNotThrow(
				ArchiveRestoreReducedCompoundTest.this::archive,
				"Re-archiving a both-scopes-partitioned reference must not throw"
			);

			// after re-archive the reduced ARCHIVED index must contain the compound (it IS partitioned in ARCHIVED)
			assertReducedIndexContainsCompound(Scope.ARCHIVED, PL);
		}
	}

	/**
	 * Archives the product entity (LIVE to ARCHIVED transition).
	 */
	private void archive() {
		this.evita.updateCatalog(
			TEST_CATALOG, session -> { session.archiveEntity(Entities.PRODUCT, PRODUCT_PK); }
		);
	}

	/**
	 * Restores the product entity (ARCHIVED to LIVE transition).
	 */
	private void restore() {
		this.evita.updateCatalog(
			TEST_CATALOG, session -> { session.restoreEntity(Entities.PRODUCT, PRODUCT_PK); }
		);
	}

	/**
	 * Asserts that the reduced (per-reference) index for the `categories` reference in the given scope both exists and
	 * holds the entity-level sortable compound for {@code recordId} under the given locale.
	 *
	 * @param scope  scope whose reduced reference index is inspected
	 * @param locale locale of the compound key (`null` for a non-localized compound)
	 */
	private void assertReducedIndexContainsCompound(@Nonnull Scope scope, @Nullable Locale locale) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract product = catalog.getCollectionForEntityOrThrowException(Entities.PRODUCT);
			final EntityIndex reduced = getReferencedEntityIndex(product, scope, Entities.CATEGORY, CATEGORY_PK);
			assertNotNull(reduced, "Reduced " + scope + " category index must exist");
			assertEquals(
				CompoundPresence.PRESENT,
				sortCompoundPresence(reduced, COMPOUND_NAME, locale, PRODUCT_PK),
				"Reduced " + scope + " category index is MISSING the entity-level sortable compound"
			);
			return null;
		});
	}

	/**
	 * Asserts that the reduced (per-reference) index for the `categories` reference in the given scope does NOT hold the
	 * entity-level sortable compound for {@code recordId}. A `null` reduced index (the reference is not partitioned in
	 * that scope) and a present-but-recordless compound both satisfy absence; an actually-present record fails.
	 *
	 * @param scope  scope whose reduced reference index is inspected
	 * @param locale locale of the compound key (`null` for a non-localized compound)
	 */
	@SuppressWarnings("SameParameterValue")
	private void assertReducedIndexDoesNotContainCompound(@Nonnull Scope scope, @Nullable Locale locale) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract product = catalog.getCollectionForEntityOrThrowException(Entities.PRODUCT);
			final EntityIndex reduced = getReferencedEntityIndex(product, scope, Entities.CATEGORY, CATEGORY_PK);
			if (reduced == null) {
				// the reference is not partitioned in this scope - there is no reduced index, which is correct absence
				return null;
			}
			assertNotSame(
				CompoundPresence.PRESENT, sortCompoundPresence(reduced, COMPOUND_NAME, locale, PRODUCT_PK),
				"Reduced " + scope + " category index UNEXPECTEDLY contains the entity-level sortable compound"
			);
			return null;
		});
	}

	/**
	 * Defines the schema used by most tests: a product with a localized `name`, a boolean `flag`, an entity-level
	 * localized sortable compound `flagNameCompound`, and a `categories` reference that is partitioned in LIVE only.
	 */
	private void createLocalizedSchema() {
		createSchema(
			whichIs -> whichIs.indexedInScope(Scope.LIVE, Scope.ARCHIVED),
			thatIs -> thatIs
				.indexedForFilteringAndPartitioningInScope(Scope.LIVE)
				.indexedForFilteringInScope(Scope.ARCHIVED),
			true
		);
	}

	/**
	 * Defines a variant schema where the compound is non-localized (`flag` + non-localized `code`); the reference is
	 * still partitioned in LIVE only. Exercises the distinct `locale == null` indexing code path.
	 */
	private void createNonLocalizedSchema() {
		createSchema(
			whichIs -> whichIs.indexedInScope(Scope.LIVE, Scope.ARCHIVED),
			thatIs -> thatIs
				.indexedForFilteringAndPartitioningInScope(Scope.LIVE)
				.indexedForFilteringInScope(Scope.ARCHIVED),
			false
		);
	}

	/**
	 * Defines a variant schema where the `categories` reference is partitioned in BOTH scopes. Guards that the fix did
	 * not break the symmetric-partitioning case.
	 */
	private void createBothScopesSchema() {
		createSchema(
			whichIs -> whichIs.indexedInScope(Scope.LIVE, Scope.ARCHIVED),
			thatIs -> thatIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED),
			true
		);
	}

	/**
	 * Builds the product/category schema parameterized by the compound's indexed-scope configuration, the reference's
	 * scope configuration, and whether the compound (and its `name` element) is localized.
	 *
	 * @param compoundScopes   configures the indexed scopes of the entity-level sortable compound
	 * @param referenceScopes  configures the filtering/partitioning scopes of the `categories` reference
	 * @param compoundLocalized whether the compound is localized (uses `name`) or non-localized (uses `code`)
	 */
	private void createSchema(
		@Nonnull Consumer<SortableAttributeCompoundSchemaBuilder> compoundScopes,
		@Nonnull Consumer<ReferenceSchemaBuilder> referenceScopes,
		boolean compoundLocalized
	) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);

				// the compound's second element is a localized `name` or a non-localized `code` depending on the variant;
				// only the used attribute is defined so the unused one does not become a mandatory non-null attribute
				final String secondElement = compoundLocalized ? ATTRIBUTE_NAME : ATTRIBUTE_CODE;
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(
						secondElement, String.class,
						thatIs -> {
							if (compoundLocalized) {
								thatIs.localized();
							}
							thatIs.filterable().sortable();
						}
					)
					.withAttribute(ATTRIBUTE_FLAG, Boolean.class, thatIs -> thatIs.filterable().sortable())
					// entity-level sortable compound stored in the partitioned reduced reference index
					.withSortableAttributeCompound(
						COMPOUND_NAME,
						new AttributeElement[]{
							new AttributeElement(ATTRIBUTE_FLAG, OrderDirection.DESC, OrderBehaviour.NULLS_LAST),
							new AttributeElement(secondElement, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
						},
						compoundScopes
					)
					.withReferenceToEntity(
						Entities.CATEGORY,
						Entities.CATEGORY,
						Cardinality.ZERO_OR_MORE,
						referenceScopes
					)
					.updateVia(session);
			}
		);
	}

	/**
	 * Creates one category and one product with a localized `name`, used by the localized-compound schemas.
	 */
	private void createLocalizedData() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.CATEGORY, CATEGORY_PK).upsertVia(session);
				session.createNewEntity(Entities.PRODUCT, PRODUCT_PK)
					.setAttribute(ATTRIBUTE_NAME, PL, "Poduszka fioletowa")
					.setAttribute(ATTRIBUTE_FLAG, false)
					.setReference(Entities.CATEGORY, CATEGORY_PK)
					.upsertVia(session);
			}
		);
	}

	/**
	 * Creates one category and one product with a non-localized `code`, used by the non-localized-compound schema.
	 */
	private void createNonLocalizedData() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.CATEGORY, CATEGORY_PK).upsertVia(session);
				session.createNewEntity(Entities.PRODUCT, PRODUCT_PK)
					.setAttribute(ATTRIBUTE_CODE, "purple-pillow")
					.setAttribute(ATTRIBUTE_FLAG, false)
					.setReference(Entities.CATEGORY, CATEGORY_PK)
					.upsertVia(session);
			}
		);
	}

	/**
	 * Tri-state result distinguishing a present record from a missing record from an entirely absent compound lookup.
	 * Prevents false-positive absence assertions that would silently pass when the compound key is never found.
	 */
	private enum CompoundPresence {
		/** The compound's sort index exists and contains the record. */
		PRESENT,
		/** The compound's sort index exists but does not contain the record. */
		ABSENT_RECORD,
		/** No sort index exists for the compound/locale key at all. */
		NO_SUCH_COMPOUND
	}

	/**
	 * Reflectively resolves the presence of {@code recordId} in the sort index of the given entity-level
	 * sortable-attribute compound for the given locale within {@code index}. Returns a tri-state so absence checks can
	 * distinguish "compound exists but lacks the record" from "no such compound/locale key" - the latter must not
	 * silently satisfy a presence assertion.
	 *
	 * @param index       entity (reduced) index to inspect
	 * @param compoundName name of the entity-level sortable compound
	 * @param locale      locale of the compound key, or `null` for a non-localized compound
	 * @param recordId    record primary key to look for
	 * @return tri-state presence result
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "SameParameterValue"})
	private static CompoundPresence sortCompoundPresence(
		@Nonnull EntityIndex index, @Nonnull String compoundName, @Nullable Locale locale, int recordId
	) {
		try {
			final Field attributeIndexField = EntityIndex.class.getDeclaredField("attributeIndex");
			attributeIndexField.setAccessible(true);
			final AttributeIndex attributeIndex = (AttributeIndex) attributeIndexField.get(index);

			final Field sortIndexField = AttributeIndex.class.getDeclaredField("sortIndex");
			sortIndexField.setAccessible(true);
			final Map<AttributeIndexKey, SortIndex> sortIndexes =
				(Map<AttributeIndexKey, SortIndex>) sortIndexField.get(attributeIndex);

			boolean compoundKeyFound = false;
			for (final Map.Entry<AttributeIndexKey, SortIndex> entry : sortIndexes.entrySet()) {
				final AttributeIndexKey key = entry.getKey();
				if (compoundName.equals(key.attributeName()) && Objects.equals(locale, key.locale())) {
					compoundKeyFound = true;
					for (final int id : entry.getValue().getSortedRecords()) {
						if (id == recordId) {
							return CompoundPresence.PRESENT;
						}
					}
				}
			}
			return compoundKeyFound ? CompoundPresence.ABSENT_RECORD : CompoundPresence.NO_SUCH_COMPOUND;
		} catch (ReflectiveOperationException ex) {
			throw new GenericEvitaInternalError(
				"Failed to reflectively read sort index of compound '" + compoundName + "': " + ex.getMessage(), ex
			);
		}
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return EvitaConfiguration.builder()
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.storage(StorageOptions.builder().storageDirectory(getTestDirectory().resolve(DIR)).build())
			.export(FileSystemExportOptions.builder().directory(getTestDirectory().resolve(DIR_EXPORT)).build())
			.build();
	}
}
