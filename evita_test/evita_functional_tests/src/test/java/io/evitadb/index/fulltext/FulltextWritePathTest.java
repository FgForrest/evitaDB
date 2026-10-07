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

package io.evitadb.index.fulltext;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.fulltext.analysis.AnalyzedTerm;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.index.fulltext.FulltextFieldKey.attribute;
import static io.evitadb.index.fulltext.FulltextFieldKey.referenceAttribute;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the fulltext write path end to end on an embedded evitaDB: entity mutations keep the per-locale fulltext
 * indexes of the global entity index in step with the searchable attributes, through the real executor, in both the
 * transactional and the bulk-load state, and across a restart.
 *
 * Expected terms are produced by the analyzer the catalog indexes with, so the assertions read as "the index holds
 * exactly what the value analyzes to" and do not hard-code what a stemmer makes of a word.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Fulltext write path")
@Tag(ENGINE)
@Tag(INDEXING)
@Tag(FULLTEXT)
class FulltextWritePathTest implements EvitaTestSupport {
	private static final String CATALOG = "fulltextWritePathTest";
	private static final String PRODUCT = "product";
	private static final String CATEGORY = "category";
	private static final String REFERENCE_CATEGORIES = "categories";
	/** Localized, searchable in both scopes. */
	private static final String ATTRIBUTE_NAME = "name";
	/** Localized `String[]`, searchable in the live scope only. */
	private static final String ATTRIBUTE_TAGS = "tags";
	/** Localized, filterable, never searchable. */
	private static final String ATTRIBUTE_NOTE = "note";
	/** A catalog-level attribute, localized and searchable. */
	private static final String ATTRIBUTE_TITLE = "title";
	/** A localized, searchable attribute of the categories reference. */
	private static final String ATTRIBUTE_LABEL = "label";
	/** A reference to categories that allows duplicates, told apart by their slot. */
	private static final String REFERENCE_DUPLICATE_CATEGORIES = "duplicateCategories";
	/** The representative attribute of the duplicate categories reference. */
	private static final String ATTRIBUTE_SLOT = "slot";
	/** A reference that is not indexed in any scope. */
	private static final String REFERENCE_NOTES = "notes";
	/** A localized attribute of the notes reference, never searchable. */
	private static final String ATTRIBUTE_MEMO = "memo";
	private static final Locale CZECH = Locale.forLanguageTag("cs");

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("FulltextWritePathTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(
						ATTRIBUTE_TITLE, String.class, whichIs -> whichIs.localized().nullable().searchable()
					)
					.updateVia(session);
				session.defineEntitySchema(PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH, CZECH)
					.withGlobalAttribute(ATTRIBUTE_TITLE)
					.withAttribute(
						ATTRIBUTE_NAME, String.class,
						whichIs -> whichIs.localized().nullable().searchableInScope(Scope.LIVE, Scope.ARCHIVED)
					)
					.withAttribute(
						ATTRIBUTE_TAGS, String[].class, whichIs -> whichIs.localized().nullable().searchable()
					)
					.withAttribute(
						ATTRIBUTE_NOTE, String.class, whichIs -> whichIs.localized().nullable().filterable()
					)
					.withReferenceTo(
						REFERENCE_CATEGORIES, CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.withAttribute(
							ATTRIBUTE_LABEL, String.class, thatIs -> thatIs.localized().nullable().searchable()
						)
					)
					.updateVia(session);
			}
		);
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Entity attributes")
	class EntityAttributes {

		@BeforeEach
		void goLive() {
			FulltextWritePathTest.this.goLive();
		}

		@Test
		@DisplayName("an upserted value is indexed in the index of its own locale")
		void shouldIndexAnUpsertedValueInItsLocale() {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "green tea")
						.setAttribute(ATTRIBUTE_NAME, CZECH, "zelený čaj")
						.setAttribute(ATTRIBUTE_NOTE, Locale.ENGLISH, "bitter herbs")
				)
			);

			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 1, "green tea");
			assertIndexed(Scope.LIVE, CZECH, attribute(ATTRIBUTE_NAME), 1, "zelený čaj");
			// an attribute that is not searchable leaves no field behind
			final FulltextIndex english = fulltextIndexOf(Scope.LIVE, Locale.ENGLISH);
			assertNotNull(english);
			assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, english.getFieldId(attribute(ATTRIBUTE_NOTE)));
		}

		@Test
		@DisplayName("an update replaces the old value's terms with the new value's")
		void shouldReplaceTheOldValueOnUpdate() {
			upsertName(1, "green tea");

			upsertName(1, "black coffee");

			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 1, "black coffee");
		}

		@Test
		@DisplayName("removing the attribute removes its terms")
		void shouldRemoveTheValueWithTheAttribute() {
			upsertName(1, "green tea");
			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 1, "green tea");

			write(
				session -> session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow()
					.openForWrite()
					.removeAttribute(ATTRIBUTE_NAME, Locale.ENGLISH)
					.upsertVia(session)
			);

			assertNotIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 1);
		}

		@Test
		@DisplayName("every element of an array attribute is indexed")
		void shouldIndexAnArrayAttribute() {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1)
						.setAttribute(ATTRIBUTE_TAGS, Locale.ENGLISH, new String[]{"red", "leather shoe"})
				)
			);

			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_TAGS), 1, "red", "leather shoe");
		}

		@Test
		@DisplayName("a global attribute is indexed as an attribute of the entity")
		void shouldIndexAGlobalAttribute() {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1).setAttribute(ATTRIBUTE_TITLE, Locale.ENGLISH, "garden chair")
				)
			);

			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_TITLE), 1, "garden chair");
		}

		@Test
		@DisplayName("removing the entity removes every term it had indexed")
		void shouldRemoveEverythingWithTheEntity() {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "green tea")
						.setAttribute(ATTRIBUTE_TAGS, Locale.ENGLISH, new String[]{"herbal"})
						.setReference(
							REFERENCE_CATEGORIES, 10,
							whichIs -> whichIs.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "beverages")
						)
				)
			);
			upsertName(2, "green apple");
			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 1, "green tea");
			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_TAGS), 1, "herbal");
			assertIndexed(Scope.LIVE, Locale.ENGLISH, labelField(), 1, "beverages");

			write(session -> session.deleteEntity(PRODUCT, 1));

			assertNotIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 1);
			assertNotIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_TAGS), 1);
			assertNotIndexed(Scope.LIVE, Locale.ENGLISH, labelField(), 1);
			// the other entity sharing a term keeps it
			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 2, "green apple");
		}

		@Test
		@DisplayName("a rolled-back transaction leaves no term behind")
		void shouldLeaveNothingAfterARollback() {
			upsertName(1, "green tea");

			assertThrows(
				RollbackException.class,
				() -> write(
					session -> {
						session.upsertEntity(
							session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow()
								.openForWrite()
								.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "black coffee")
						);
						session.setRollbackOnly();
					}
				)
			);

			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 1, "green tea");
		}

	}

	@Nested
	@DisplayName("Reference attributes")
	class ReferenceAttributes {

		@BeforeEach
		void goLive() {
			FulltextWritePathTest.this.goLive();
		}

		@Test
		@DisplayName("the value is the union of the attribute over the references")
		void shouldIndexTheUnionOverReferences() {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1)
						.setReference(REFERENCE_CATEGORIES, 10, labelled("shoes"))
						.setReference(REFERENCE_CATEGORIES, 11, labelled("boots"))
				)
			);

			assertIndexed(Scope.LIVE, Locale.ENGLISH, labelField(), 1, "shoes", "boots");
		}

		@Test
		@DisplayName("a value another reference still carries stays indexed when one reference goes")
		void shouldKeepAValueAnotherReferenceStillCarries() {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1)
						.setReference(REFERENCE_CATEGORIES, 10, labelled("shoes"))
						.setReference(REFERENCE_CATEGORIES, 11, labelled("shoes"))
				)
			);

			removeCategory(1, 10);
			assertIndexed(Scope.LIVE, Locale.ENGLISH, labelField(), 1, "shoes");

			removeCategory(1, 11);
			assertNotIndexed(Scope.LIVE, Locale.ENGLISH, labelField(), 1);
		}

		@Test
		@DisplayName("a changed reference value replaces its terms in the union")
		void shouldReplaceAChangedReferenceValue() {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1)
						.setReference(REFERENCE_CATEGORIES, 10, labelled("shoes"))
						.setReference(REFERENCE_CATEGORIES, 11, labelled("boots"))
				)
			);

			setLabel(1, 10, "sandals");

			assertIndexed(Scope.LIVE, Locale.ENGLISH, labelField(), 1, "sandals", "boots");
		}

		@Test
		@DisplayName("a change that leaves the union the same set writes nothing to the index")
		void shouldNotWriteWhenTheUnionStaysTheSameSet() {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1)
						.setReference(REFERENCE_CATEGORIES, 10, labelled("shoes"))
						.setReference(REFERENCE_CATEGORIES, 11, labelled("boots"))
						.setReference(REFERENCE_CATEGORIES, 12, labelled("shoes"))
				)
			);
			final FulltextIndex before = requireFulltextIndex(Scope.LIVE, Locale.ENGLISH);

			// `shoes` is still carried by reference 10 and `boots` already by reference 11 - the set is unchanged
			setLabel(1, 12, "boots");

			// a write opens a transactional layer, and only an index without one is carried into the next catalog
			// version as the same instance
			assertSame(before, requireFulltextIndex(Scope.LIVE, Locale.ENGLISH));
			assertIndexed(Scope.LIVE, Locale.ENGLISH, labelField(), 1, "shoes", "boots");

			// the positive control: a change of the set does write
			setLabel(1, 12, "sandals");
			assertNotSame(before, requireFulltextIndex(Scope.LIVE, Locale.ENGLISH));
			assertIndexed(Scope.LIVE, Locale.ENGLISH, labelField(), 1, "shoes", "boots", "sandals");
		}

		@Test
		@DisplayName("removing a reference attribute takes its value out of the union")
		void shouldRemoveAReferenceAttributeFromTheUnion() {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1)
						.setReference(REFERENCE_CATEGORIES, 10, labelled("shoes"))
						.setReference(REFERENCE_CATEGORIES, 11, labelled("boots"))
				)
			);
			assertIndexed(Scope.LIVE, Locale.ENGLISH, labelField(), 1, "shoes", "boots");

			write(
				session -> session.getEntity(PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(
						REFERENCE_CATEGORIES, 11,
						whichIs -> whichIs.removeAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH)
					)
					.upsertVia(session)
			);

			assertIndexed(Scope.LIVE, Locale.ENGLISH, labelField(), 1, "shoes");
		}

	}

	@Nested
	@DisplayName("Duplicate references")
	class DuplicateReferences {

		@BeforeEach
		void declareDuplicateReferenceAndGoLive() {
			write(
				session -> session.getEntitySchemaOrThrow(PRODUCT)
					.openForWrite()
					.withReferenceTo(
						REFERENCE_DUPLICATE_CATEGORIES, CATEGORY, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
						whichIs -> whichIs
							.withAttribute(ATTRIBUTE_SLOT, Integer.class, thatIs -> thatIs.representative())
							.withAttribute(
								ATTRIBUTE_LABEL, String.class, thatIs -> thatIs.localized().nullable().searchable()
							)
					)
					.updateVia(session)
			);
			FulltextWritePathTest.this.goLive();
		}

		@Test
		@DisplayName("duplicates created together unite their values")
		void shouldUniteLabelsOfDuplicateReferencesCreatedTogether() {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1)
						.setOrUpdateReference(REFERENCE_DUPLICATE_CATEGORIES, 10, ref -> false, slotted(1, "shoes"))
						.setOrUpdateReference(REFERENCE_DUPLICATE_CATEGORIES, 10, ref -> false, slotted(2, "boots"))
				)
			);

			assertIndexed(Scope.LIVE, Locale.ENGLISH, duplicateLabelField(), 1, "shoes", "boots");
		}

		@Test
		@DisplayName("a duplicate added beside a stored one keeps the stored one's value, and its removal only its own")
		void shouldKeepPersistedDuplicateLabelWhenAnotherDuplicateIsAdded() {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1)
						.setOrUpdateReference(REFERENCE_DUPLICATE_CATEGORIES, 10, ref -> false, slotted(1, "shoes"))
				)
			);

			write(
				session -> session.getEntity(PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setOrUpdateReference(REFERENCE_DUPLICATE_CATEGORIES, 10, ref -> false, slotted(2, "boots"))
					.upsertVia(session)
			);

			assertIndexed(Scope.LIVE, Locale.ENGLISH, duplicateLabelField(), 1, "shoes", "boots");

			// removing the first duplicate takes only its own value out of the union
			write(
				session -> {
					final SealedEntity product = session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow();
					final ReferenceKey firstSlot = product.getReferences(REFERENCE_DUPLICATE_CATEGORIES)
						.stream()
						.filter(it -> Integer.valueOf(1).equals(it.getAttribute(ATTRIBUTE_SLOT)))
						.findFirst()
						.orElseThrow()
						.getReferenceKey();
					product.openForWrite().removeReference(firstSlot).upsertVia(session);
				}
			);

			assertIndexed(Scope.LIVE, Locale.ENGLISH, duplicateLabelField(), 1, "boots");
		}

	}

	@Nested
	@DisplayName("Undeclared reference attributes")
	class UndeclaredReferenceAttributes {

		@BeforeEach
		void declareNonIndexedReferenceAndGoLive() {
			write(
				session -> session.getEntitySchemaOrThrow(PRODUCT)
					.openForWrite()
					.withReferenceTo(
						REFERENCE_NOTES, CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.nonIndexed()
							.withAttribute(ATTRIBUTE_MEMO, String.class, thatIs -> thatIs.localized().nullable())
					)
					.updateVia(session)
			);
			FulltextWritePathTest.this.goLive();
		}

		@Test
		@DisplayName("an entity whose non-indexed reference holds an attribute the schema dropped can be removed")
		void shouldRemoveEntityWhoseNonIndexedReferenceHoldsAnUndeclaredAttribute() {
			writeMemoAndDropItsSchema(1);

			assertDoesNotThrow(() -> write(session -> session.deleteEntity(PRODUCT, 1)));
			assertTrue(readProduct(1).isEmpty());
		}

		@Test
		@DisplayName("an entity whose non-indexed reference holds an attribute the schema dropped can be archived")
		void shouldArchiveEntityWhoseNonIndexedReferenceHoldsAnUndeclaredAttribute() {
			writeMemoAndDropItsSchema(1);

			assertDoesNotThrow(() -> write(session -> session.archiveEntity(PRODUCT, 1)));
			assertDoesNotThrow(() -> write(session -> session.restoreEntity(PRODUCT, 1)));
			assertTrue(readProduct(1).isPresent());
		}

		/**
		 * Writes a product whose notes reference carries an English memo, then removes the memo attribute from the
		 * reference schema - which leaves the stored value behind.
		 *
		 * @param primaryKey the product
		 */
		private void writeMemoAndDropItsSchema(int primaryKey) {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, primaryKey)
						.setReference(
							REFERENCE_NOTES, 10,
							whichIs -> whichIs.setAttribute(ATTRIBUTE_MEMO, Locale.ENGLISH, "fragile")
						)
				)
			);
			write(
				session -> session.getEntitySchemaOrThrow(PRODUCT)
					.openForWrite()
					.withReferenceTo(
						REFERENCE_NOTES, CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.withoutAttribute(ATTRIBUTE_MEMO)
					)
					.updateVia(session)
			);
		}

	}

	@Nested
	@DisplayName("Scope change")
	class ScopeChange {

		@BeforeEach
		void goLive() {
			FulltextWritePathTest.this.goLive();
		}

		@Test
		@DisplayName("archiving keeps only what is searchable in the archive, restoring brings everything back")
		void shouldMoveSearchableValuesWithTheScope() {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "green tea")
						.setAttribute(ATTRIBUTE_TAGS, Locale.ENGLISH, new String[]{"herbal"})
						.setReference(REFERENCE_CATEGORIES, 10, labelled("beverages"))
				)
			);
			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 1, "green tea");
			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_TAGS), 1, "herbal");
			assertIndexed(Scope.LIVE, Locale.ENGLISH, labelField(), 1, "beverages");

			write(session -> session.archiveEntity(PRODUCT, 1));

			assertNotIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 1);
			assertNotIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_TAGS), 1);
			assertNotIndexed(Scope.LIVE, Locale.ENGLISH, labelField(), 1);
			// `name` is searchable in the archive too, `tags` and the label are not
			assertIndexed(Scope.ARCHIVED, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 1, "green tea");
			assertNotIndexed(Scope.ARCHIVED, Locale.ENGLISH, attribute(ATTRIBUTE_TAGS), 1);
			assertNotIndexed(Scope.ARCHIVED, Locale.ENGLISH, labelField(), 1);

			write(session -> session.restoreEntity(PRODUCT, 1));

			assertNotIndexed(Scope.ARCHIVED, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 1);
			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 1, "green tea");
			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_TAGS), 1, "herbal");
			assertIndexed(Scope.LIVE, Locale.ENGLISH, labelField(), 1, "beverages");
		}

	}

	@Nested
	@DisplayName("Withdrawn searchability")
	class Withdrawal {

		@BeforeEach
		void goLive() {
			FulltextWritePathTest.this.goLive();
		}

		@Test
		@DisplayName("the next write retires the field, and a re-declared field starts empty")
		void shouldRetireAWithdrawnFieldOnTheNextWrite() {
			upsertName(1, "green tea");
			upsertName(2, "black tea");
			final int originalFieldId = requireFulltextIndex(Scope.LIVE, Locale.ENGLISH)
				.getFieldId(attribute(ATTRIBUTE_NAME));
			assertNotEquals(FulltextIndex.UNKNOWN_FIELD_ID, originalFieldId);

			setNameSearchable(false);
			// the schema change alone touches no index - the field is still there
			assertEquals(
				originalFieldId, requireFulltextIndex(Scope.LIVE, Locale.ENGLISH).getFieldId(attribute(ATTRIBUTE_NAME))
			);

			upsertName(1, "white tea");
			final FulltextIndex afterWithdrawal = requireFulltextIndex(Scope.LIVE, Locale.ENGLISH);
			assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, afterWithdrawal.getFieldId(attribute(ATTRIBUTE_NAME)));
			assertTrue(afterWithdrawal.isFieldRetired(originalFieldId));

			setNameSearchable(true);
			upsertName(2, "red tea");

			final FulltextIndex afterReAdd = requireFulltextIndex(Scope.LIVE, Locale.ENGLISH);
			final int newFieldId = afterReAdd.getFieldId(attribute(ATTRIBUTE_NAME));
			assertNotEquals(FulltextIndex.UNKNOWN_FIELD_ID, newFieldId);
			assertNotEquals(originalFieldId, newFieldId);
			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 2, "red tea");
			// entity 1 was written while the attribute was not searchable, so it is missing - never phantom
			assertNotIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 1);
		}

		@Test
		@DisplayName("a write retires the field only in the scope and locale it writes")
		void shouldRetireOnlyTheFieldOfTheLocaleAndScopeThatWasWritten() {
			write(
				session -> {
					session.upsertEntity(
						session.createNewEntity(PRODUCT, 1)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "green tea")
							.setAttribute(ATTRIBUTE_NAME, CZECH, "zelený čaj")
					);
					session.upsertEntity(
						session.createNewEntity(PRODUCT, 2).setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "black tea")
					);
				}
			);
			write(session -> session.archiveEntity(PRODUCT, 2));
			final int liveEnglishId = requireFulltextIndex(Scope.LIVE, Locale.ENGLISH)
				.getFieldId(attribute(ATTRIBUTE_NAME));
			final int liveCzechId = requireFulltextIndex(Scope.LIVE, CZECH).getFieldId(attribute(ATTRIBUTE_NAME));
			final int archivedEnglishId = requireFulltextIndex(Scope.ARCHIVED, Locale.ENGLISH)
				.getFieldId(attribute(ATTRIBUTE_NAME));
			assertIndexed(Scope.ARCHIVED, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 2, "black tea");

			setNameSearchable(false);
			upsertName(1, "white tea");

			// the written scope and locale retire their field ...
			final FulltextIndex liveEnglish = requireFulltextIndex(Scope.LIVE, Locale.ENGLISH);
			assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, liveEnglish.getFieldId(attribute(ATTRIBUTE_NAME)));
			assertTrue(liveEnglish.isFieldRetired(liveEnglishId));
			// ... another locale of the same entity keeps its field and postings until it is written ...
			assertEquals(
				liveCzechId, requireFulltextIndex(Scope.LIVE, CZECH).getFieldId(attribute(ATTRIBUTE_NAME))
			);
			assertIndexed(Scope.LIVE, CZECH, attribute(ATTRIBUTE_NAME), 1, "zelený čaj");
			// ... and so does the other scope
			assertEquals(
				archivedEnglishId,
				requireFulltextIndex(Scope.ARCHIVED, Locale.ENGLISH).getFieldId(attribute(ATTRIBUTE_NAME))
			);
			assertIndexed(Scope.ARCHIVED, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 2, "black tea");

			// a write in the archive retires the archived field too
			write(
				session -> session.getEntity(PRODUCT, 2, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "red tea")
					.upsertVia(session)
			);
			final FulltextIndex archivedEnglish = requireFulltextIndex(Scope.ARCHIVED, Locale.ENGLISH);
			assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, archivedEnglish.getFieldId(attribute(ATTRIBUTE_NAME)));
			assertTrue(archivedEnglish.isFieldRetired(archivedEnglishId));
		}

	}

	@Nested
	@DisplayName("Index mirrors storage")
	class Invariant {

		@BeforeEach
		void goLive() {
			FulltextWritePathTest.this.goLive();
		}

		@Test
		@DisplayName("after every step of a mixed sequence, the index holds exactly what storage makes searchable")
		void shouldMirrorStorageAfterEveryStepOfAMixedMutationSequence() {
			write(
				session -> {
					session.upsertEntity(
						session.createNewEntity(PRODUCT, 1)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "green tea")
							.setAttribute(ATTRIBUTE_NAME, CZECH, "zelený čaj")
							.setAttribute(ATTRIBUTE_TAGS, Locale.ENGLISH, new String[]{"herbal", "loose leaf"})
							.setReference(REFERENCE_CATEGORIES, 10, labelled("shoes"))
							.setReference(REFERENCE_CATEGORIES, 11, labelled("boots"))
					);
					// a second product sharing terms, to catch one entity's write leaking into another's postings
					session.upsertEntity(
						session.createNewEntity(PRODUCT, 2)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "green apple")
							.setReference(REFERENCE_CATEGORIES, 10, labelled("shoes"))
					);
				}
			);
			assertIndexMirrorsStorage(1);
			assertIndexMirrorsStorage(2);

			// one entity mutation: a reference removal, a reference attribute change, an attribute replacement and
			// a scope change - applied as removals, then the scope change, then upserts
			write(
				session -> session.getEntity(PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setScope(Scope.ARCHIVED)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "black tea")
					.removeReference(REFERENCE_CATEGORIES, 10)
					.setReference(REFERENCE_CATEGORIES, 11, labelled("sandals"))
					.upsertVia(session)
			);
			assertEquals(Scope.ARCHIVED, readProduct(1).orElseThrow().getScope());
			assertIndexMirrorsStorage(1);
			assertIndexMirrorsStorage(2);

			// restore, and add a reference to the existing entity in the same mutation
			write(
				session -> session.getEntity(
						PRODUCT, 1, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()
					)
					.orElseThrow()
					.openForWrite()
					.setScope(Scope.LIVE)
					.setReference(REFERENCE_CATEGORIES, 12, labelled("slippers"))
					.upsertVia(session)
			);
			assertEquals(Scope.LIVE, readProduct(1).orElseThrow().getScope());
			assertIndexMirrorsStorage(1);
			assertIndexMirrorsStorage(2);

			// one locale only: the Czech name goes, the English one stays
			write(
				session -> session.getEntity(PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.removeAttribute(ATTRIBUTE_NAME, CZECH)
					.upsertVia(session)
			);
			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 1, "black tea");
			assertIndexMirrorsStorage(1);
			assertIndexMirrorsStorage(2);

			write(session -> session.deleteEntity(PRODUCT, 1));
			assertNothingIndexedFor(1);
			assertIndexMirrorsStorage(2);
		}

	}

	@Nested
	@DisplayName("Persistence")
	class Persistence {

		@Test
		@DisplayName("values written in bulk load are read back after a restart")
		void shouldReloadValuesWrittenInBulkLoad() {
			upsertName(1, "green tea");
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 2)
						.setReference(REFERENCE_CATEGORIES, 10, labelled("beverages"))
				)
			);

			restart();

			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 1, "green tea");
			assertIndexed(Scope.LIVE, Locale.ENGLISH, labelField(), 2, "beverages");
		}

		@Test
		@DisplayName("values written in transactions are read back after a restart")
		void shouldReloadValuesWrittenTransactionally() {
			goLive();
			upsertName(1, "green tea");
			upsertName(1, "black coffee");
			upsertName(2, "green apple");

			restart();

			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 1, "black coffee");
			assertIndexed(Scope.LIVE, Locale.ENGLISH, attribute(ATTRIBUTE_NAME), 2, "green apple");
		}

	}

	/**
	 * Reads a product with all its content, in any scope.
	 *
	 * @param primaryKey the product
	 * @return the product, empty when it does not exist
	 */
	@Nonnull
	private Optional<SealedEntity> readProduct(int primaryKey) {
		return this.evita.queryCatalog(
			CATALOG,
			session -> {
				return session.getEntity(
					PRODUCT, primaryKey, new Scope[]{Scope.LIVE, Scope.ARCHIVED}, entityFetchAllContent()
				);
			}
		);
	}

	/**
	 * Asserts that the fulltext indexes hold exactly what the stored product makes searchable: in the scope it lives
	 * in, every searchable entity attribute and the union of the searchable reference label, per locale; in the other
	 * scope, nothing at all. The expectation is recomputed from the fetched entity and its schema, not from the
	 * mutations that produced it.
	 *
	 * @param primaryKey the product
	 */
	private void assertIndexMirrorsStorage(int primaryKey) {
		final SealedEntity product = readProduct(primaryKey).orElseThrow();
		final Scope scope = product.getScope();
		final Scope otherScope = scope == Scope.LIVE ? Scope.ARCHIVED : Scope.LIVE;
		for (final Locale locale : new Locale[]{Locale.ENGLISH, CZECH}) {
			for (final String attributeName : new String[]{ATTRIBUTE_NAME, ATTRIBUTE_TAGS, ATTRIBUTE_TITLE}) {
				final Set<String> values = new TreeSet<>();
				product.getAttributeValue(attributeName, locale)
					.ifPresent(it -> collectTexts(it.value(), values));
				final boolean searchable = product.getSchema()
					.getAttribute(attributeName)
					.orElseThrow()
					.isSearchableInScope(scope);
				assertMirrored(scope, locale, attribute(attributeName), primaryKey, searchable, values);
				assertNotIndexed(otherScope, locale, attribute(attributeName), primaryKey);
			}
			final Set<String> labels = new TreeSet<>();
			for (final ReferenceContract reference : product.getReferences(REFERENCE_CATEGORIES)) {
				reference.getAttributeValue(ATTRIBUTE_LABEL, locale)
					.ifPresent(it -> collectTexts(it.value(), labels));
			}
			final boolean labelSearchable = product.getSchema()
				.getReferenceOrThrowException(REFERENCE_CATEGORIES)
				.getAttribute(ATTRIBUTE_LABEL)
				.orElseThrow()
				.isSearchableInScope(scope);
			assertMirrored(scope, locale, labelField(), primaryKey, labelSearchable, labels);
			assertNotIndexed(otherScope, locale, labelField(), primaryKey);
		}
	}

	/**
	 * Asserts that the entity holds exactly the terms of `values` in the field when the field is searchable and has
	 * values, and no term otherwise.
	 *
	 * @param scope      the scope of the global index
	 * @param locale     the locale of the fulltext index
	 * @param fieldKey   the field
	 * @param primaryKey the entity
	 * @param searchable whether the field is searchable in the scope
	 * @param values     the values the entity's storage holds for the field
	 */
	private void assertMirrored(
		@Nonnull Scope scope,
		@Nonnull Locale locale,
		@Nonnull FulltextFieldKey fieldKey,
		int primaryKey,
		boolean searchable,
		@Nonnull Set<String> values
	) {
		if (searchable && !values.isEmpty()) {
			assertIndexed(scope, locale, fieldKey, primaryKey, values.toArray(String[]::new));
		} else {
			assertNotIndexed(scope, locale, fieldKey, primaryKey);
		}
	}

	/**
	 * Adds the texts of a `String` or `String[]` value to `target`.
	 *
	 * @param value  the stored value
	 * @param target the set collecting the texts
	 */
	private static void collectTexts(@Nullable Serializable value, @Nonnull Set<String> target) {
		if (value instanceof String text) {
			target.add(text);
		} else if (value instanceof String[] texts) {
			for (final String text : texts) {
				if (text != null) {
					target.add(text);
				}
			}
		}
	}

	/**
	 * Asserts that the fulltext indexes of both scopes hold no term of a removed product in any field.
	 *
	 * @param primaryKey the removed product
	 */
	private void assertNothingIndexedFor(int primaryKey) {
		for (final Scope scope : Scope.values()) {
			for (final Locale locale : new Locale[]{Locale.ENGLISH, CZECH}) {
				for (final String attributeName : new String[]{ATTRIBUTE_NAME, ATTRIBUTE_TAGS, ATTRIBUTE_TITLE}) {
					assertNotIndexed(scope, locale, attribute(attributeName), primaryKey);
				}
				assertNotIndexed(scope, locale, labelField(), primaryKey);
			}
		}
	}

	/**
	 * Asserts that the terms the entity holds in the field are exactly the terms the values analyze to.
	 *
	 * @param scope      the scope of the global index
	 * @param locale     the locale of the fulltext index
	 * @param fieldKey   the field
	 * @param primaryKey the entity
	 * @param values     the values the entity's field is expected to hold
	 */
	private void assertIndexed(
		@Nonnull Scope scope,
		@Nonnull Locale locale,
		@Nonnull FulltextFieldKey fieldKey,
		int primaryKey,
		@Nonnull String... values
	) {
		final Set<String> expected = new TreeSet<>();
		final FulltextAnalyzerRegistry registry = catalog().getFulltextAnalyzerRegistry();
		for (final String value : values) {
			for (final AnalyzedTerm term : registry.getIndexAnalyzer(PRODUCT, locale).getTerms(value)) {
				expected.add(term.term());
			}
		}
		assertFalse(expected.isEmpty(), "The fixture values must produce terms.");
		assertEquals(
			expected, termsOf(scope, locale, fieldKey, primaryKey),
			"Entity " + primaryKey + " holds other terms in " + fieldKey + " of " + scope + "/" + locale
		);
	}

	/**
	 * Asserts that the entity holds no term in the field.
	 *
	 * @param scope      the scope of the global index
	 * @param locale     the locale of the fulltext index
	 * @param fieldKey   the field
	 * @param primaryKey the entity
	 */
	private void assertNotIndexed(
		@Nonnull Scope scope,
		@Nonnull Locale locale,
		@Nonnull FulltextFieldKey fieldKey,
		int primaryKey
	) {
		assertEquals(
			Set.of(), termsOf(scope, locale, fieldKey, primaryKey),
			"Entity " + primaryKey + " still holds terms in " + fieldKey + " of " + scope + "/" + locale
		);
	}

	/**
	 * Collects the terms whose posting list contains the entity, in the field of the fulltext index.
	 *
	 * @param scope      the scope of the global index
	 * @param locale     the locale of the fulltext index
	 * @param fieldKey   the field
	 * @param primaryKey the entity
	 * @return the terms, empty when the index or the field does not exist
	 */
	@Nonnull
	private Set<String> termsOf(
		@Nonnull Scope scope,
		@Nonnull Locale locale,
		@Nonnull FulltextFieldKey fieldKey,
		int primaryKey
	) {
		final Set<String> terms = new TreeSet<>();
		final FulltextIndex index = fulltextIndexOf(scope, locale);
		if (index == null) {
			return terms;
		}
		final int fieldId = index.getFieldId(fieldKey);
		if (fieldId == FulltextIndex.UNKNOWN_FIELD_ID) {
			return terms;
		}
		index.forEachTerm(
			fieldId, "",
			(term, postings, impacts) -> {
				if (postings.contains(primaryKey)) {
					terms.add(term);
				}
				return true;
			}
		);
		return terms;
	}

	/**
	 * Returns the fulltext index of the locale in the product collection's global index of the scope.
	 *
	 * @param scope  the scope of the global index
	 * @param locale the locale
	 * @return the index, or `null` when there is none
	 */
	@Nullable
	private FulltextIndex fulltextIndexOf(@Nonnull Scope scope, @Nonnull Locale locale) {
		final GlobalEntityIndex globalIndex = (GlobalEntityIndex) catalog()
			.getCollectionForEntityInternal(PRODUCT)
			.orElseThrow()
			.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, scope));
		return globalIndex == null ? null : globalIndex.getFulltextIndex(locale);
	}

	/**
	 * Returns the fulltext index of the locale in the product collection's global index of the scope.
	 *
	 * @param scope  the scope of the global index
	 * @param locale the locale
	 * @return the index
	 */
	@Nonnull
	private FulltextIndex requireFulltextIndex(@Nonnull Scope scope, @Nonnull Locale locale) {
		final FulltextIndex index = fulltextIndexOf(scope, locale);
		assertNotNull(index, "There is no fulltext index of " + scope + "/" + locale);
		return index;
	}

	/**
	 * @return the catalog instance currently published
	 */
	@Nonnull
	private Catalog catalog() {
		return (Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG);
	}

	/**
	 * @return the field of the label attribute of the categories reference
	 */
	@Nonnull
	private static FulltextFieldKey labelField() {
		return referenceAttribute(REFERENCE_CATEGORIES, ATTRIBUTE_LABEL);
	}

	/**
	 * @return the field of the label attribute of the duplicate categories reference
	 */
	@Nonnull
	private static FulltextFieldKey duplicateLabelField() {
		return referenceAttribute(REFERENCE_DUPLICATE_CATEGORIES, ATTRIBUTE_LABEL);
	}

	/**
	 * Builds a reference setter giving a duplicate reference its slot and an English label.
	 *
	 * @param slot  the representative slot telling the duplicate apart
	 * @param label the label
	 * @return the setter
	 */
	@Nonnull
	private static Consumer<ReferenceBuilder> slotted(int slot, @Nonnull String label) {
		return whichIs -> whichIs
			.setAttribute(ATTRIBUTE_SLOT, slot)
			.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, label);
	}

	/**
	 * Builds a reference setter giving the reference an English label.
	 *
	 * @param label the label
	 * @return the setter
	 */
	@Nonnull
	private static Consumer<ReferenceBuilder> labelled(@Nonnull String label) {
		return whichIs -> whichIs.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, label);
	}

	/**
	 * Upserts the English name of a product, creating the product when it does not exist.
	 *
	 * @param primaryKey the product
	 * @param name       the name
	 */
	private void upsertName(int primaryKey, @Nonnull String name) {
		write(
			session -> session.upsertEntity(
				session.getEntity(PRODUCT, primaryKey, entityFetchAllContent())
					.map(it -> it.openForWrite())
					.orElseGet(() -> session.createNewEntity(PRODUCT, primaryKey))
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, name)
			)
		);
	}

	/**
	 * Sets the English label of a categories reference of a product.
	 *
	 * @param primaryKey the product
	 * @param categoryId the referenced category
	 * @param label      the new label
	 */
	private void setLabel(int primaryKey, int categoryId, @Nonnull String label) {
		write(
			session -> session.getEntity(PRODUCT, primaryKey, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(REFERENCE_CATEGORIES, categoryId, labelled(label))
				.upsertVia(session)
		);
	}

	/**
	 * Removes a categories reference of a product.
	 *
	 * @param primaryKey the product
	 * @param categoryId the referenced category
	 */
	private void removeCategory(int primaryKey, int categoryId) {
		write(
			session -> session.getEntity(PRODUCT, primaryKey, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.removeReference(REFERENCE_CATEGORIES, categoryId)
				.upsertVia(session)
		);
	}

	/**
	 * Declares the name attribute searchable in both scopes, or in none.
	 *
	 * @param searchable whether the attribute is searchable
	 */
	private void setNameSearchable(boolean searchable) {
		write(
			session -> session.getEntitySchemaOrThrow(PRODUCT)
				.openForWrite()
				.withAttribute(
					ATTRIBUTE_NAME, String.class,
					whichIs -> {
						if (searchable) {
							whichIs.searchableInScope(Scope.LIVE, Scope.ARCHIVED);
						} else {
							whichIs.nonSearchable();
						}
					}
				)
				.updateVia(session)
		);
	}

	/**
	 * Runs the writes in one session of the test catalog.
	 *
	 * @param writes the writes
	 */
	private void write(@Nonnull Consumer<EvitaSessionContract> writes) {
		this.evita.updateCatalog(CATALOG, writes);
	}

	/**
	 * Takes the catalog live, so what follows commits transactionally.
	 */
	private void goLive() {
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
	}

	/**
	 * Closes the embedded evitaDB and opens a new one over the very same storage, so that everything asserted
	 * afterwards was read back from disk.
	 */
	private void restart() {
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
	}

}
