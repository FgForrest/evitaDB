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
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.DataItemValue;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.fulltext.analysis.AnalyzedTerm;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.index.fulltext.FulltextFieldKey.associatedData;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the fulltext write path of searchable associated data end to end on an embedded evitaDB: upserts, updates and
 * removals of `String`, `String[]` and complex data object values keep the per-locale fulltext indexes of the global
 * entity index in step, through the real executor, in both the transactional and the bulk-load state, across a scope
 * change and a restart. Markup in a stored value is stripped by the index analyzer.
 *
 * Expected terms are produced by the analyzer the catalog indexes with, so the assertions read as "the index holds
 * exactly what the text analyzes to".
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Fulltext write path of searchable associated data")
@Tag(ENGINE)
@Tag(INDEXING)
@Tag(FULLTEXT)
class FulltextAssociatedDataWritePathTest implements EvitaTestSupport {
	private static final String CATALOG = "fulltextAssociatedDataWritePathTest";
	private static final String PRODUCT = "product";
	/** Localized `String`, searchable in both scopes. */
	private static final String BODY = "body";
	/** Localized `String[]`, searchable in the live scope only. */
	private static final String KEYWORDS = "keywords";
	/** Localized complex data object, searchable through its blocks. */
	private static final String ARTICLE = "article";
	/** Localized complex data object, searchable through its blocks in the archive only. */
	private static final String ARCHIVED_ARTICLE = "archivedArticle";
	/** Localized `String`, never searchable. */
	private static final String MEMO = "memo";
	/** Not localized `String`, never searchable. */
	private static final String NOTE = "note";
	private static final String BLOCKS_EXPRESSION = "$value.blocks.*[$.text]";
	private static final Locale CZECH = Locale.forLanguageTag("cs");

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("FulltextAssociatedDataWritePathTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		write(
			session -> session.defineEntitySchema(PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withLocale(Locale.ENGLISH, CZECH)
				.withAssociatedData(
					BODY, String.class,
					whichIs -> whichIs.localized().nullable().searchableInScope(Scope.LIVE, Scope.ARCHIVED)
				)
				.withAssociatedData(
					KEYWORDS, String[].class, whichIs -> whichIs.localized().nullable().searchable()
				)
				.withAssociatedData(
					ARTICLE, ComplexDataObject.class,
					whichIs -> whichIs.localized().nullable().searchable(ExpressionFactory.parse(BLOCKS_EXPRESSION))
				)
				.withAssociatedData(
					ARCHIVED_ARTICLE, ComplexDataObject.class,
					whichIs -> whichIs.localized().nullable()
						.searchableInScope(ExpressionFactory.parse(BLOCKS_EXPRESSION), Scope.ARCHIVED)
				)
				.withAssociatedData(MEMO, String.class, whichIs -> whichIs.localized().nullable())
				.withAssociatedData(NOTE, String.class, AssociatedDataSchemaEditor::nullable)
				.updateVia(session)
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
	@DisplayName("Values of every searchable type")
	class Values {

		@BeforeEach
		void goLive() {
			FulltextAssociatedDataWritePathTest.this.goLive();
		}

		@Test
		@DisplayName("a String, a String[] and the selected text of a complex data object are indexed per locale")
		void shouldIndexEverySearchableType() {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1)
						.setAssociatedData(BODY, Locale.ENGLISH, "green tea from the hills")
						.setAssociatedData(BODY, CZECH, "zelený čaj z hor")
						.setAssociatedData(KEYWORDS, Locale.ENGLISH, new String[]{"herbal", "organic leaves"})
						.setAssociatedData(ARTICLE, Locale.ENGLISH, article("Secret title", "first block", "second"))
						.setAssociatedData(MEMO, Locale.ENGLISH, "bitter herbs")
						.setAssociatedData(NOTE, "plain note")
				)
			);

			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(BODY), 1, "green tea from the hills");
			assertIndexed(Scope.LIVE, CZECH, associatedData(BODY), 1, "zelený čaj z hor");
			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(KEYWORDS), 1, "herbal", "organic leaves");
			// only the text the expression selects - the title is not part of it
			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(ARTICLE), 1, "first block", "second");
			// associated data that is not searchable leaves no field behind
			final FulltextIndex english = requireFulltextIndex(Scope.LIVE, Locale.ENGLISH);
			assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, english.getFieldId(associatedData(MEMO)));
			assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, english.getFieldId(associatedData(NOTE)));
			// the field records the expression its text was selected with, a String field none
			assertEquals(
				BLOCKS_EXPRESSION, english.getTextExpression(english.getFieldId(associatedData(ARTICLE)))
			);
			assertNull(english.getTextExpression(english.getFieldId(associatedData(BODY))));
		}

		@Test
		@DisplayName("markup and a JSON payload in a data- attribute are stripped from an HTML body")
		void shouldStripMarkupFromAnHtmlBody() {
			final String html = "<p class=\"intro\" data-value='{\"value\": false}'>Black <b>leather</b> shoes</p>";
			upsertBody(1, html);

			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(BODY), 1, "Black leather shoes");
			final Set<String> terms = termsOf(Scope.LIVE, Locale.ENGLISH, associatedData(BODY), 1);
			for (final String markup : new String[]{"p", "class", "intro", "data", "valu", "fals"}) {
				assertFalse(terms.contains(markup), "Markup term `" + markup + "` was indexed: " + terms);
			}
		}

		@Test
		@DisplayName("an update replaces the old value's terms, and writing the same value again keeps them")
		void shouldReplaceTheOldValueOnUpdate() {
			upsertBody(1, "green tea");
			upsertBody(1, "black coffee");
			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(BODY), 1, "black coffee");

			upsertBody(1, "black coffee");
			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(BODY), 1, "black coffee");

			write(
				session -> session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow()
					.openForWrite()
					.setAssociatedData(ARTICLE, Locale.ENGLISH, article("t", "old block"))
					.upsertVia(session)
			);
			write(
				session -> session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow()
					.openForWrite()
					.setAssociatedData(ARTICLE, Locale.ENGLISH, article("t", "new block"))
					.upsertVia(session)
			);
			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(ARTICLE), 1, "new block");
		}

		@Test
		@DisplayName("removing the associated data, or the entity, removes its terms")
		void shouldRemoveTheValueWithTheAssociatedDataAndTheEntity() {
			write(
				session -> {
					session.upsertEntity(
						session.createNewEntity(PRODUCT, 1)
							.setAssociatedData(BODY, Locale.ENGLISH, "green tea")
							.setAssociatedData(ARTICLE, Locale.ENGLISH, article("t", "green block"))
					);
					session.upsertEntity(
						session.createNewEntity(PRODUCT, 2).setAssociatedData(BODY, Locale.ENGLISH, "green apple")
					);
				}
			);
			write(
				session -> session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow()
					.openForWrite()
					.removeAssociatedData(BODY, Locale.ENGLISH)
					.upsertVia(session)
			);
			assertNotIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(BODY), 1);
			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(ARTICLE), 1, "green block");

			write(session -> session.deleteEntity(PRODUCT, 1));

			assertNotIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(ARTICLE), 1);
			// the other entity sharing a term keeps it
			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(BODY), 2, "green apple");
		}

		@Test
		@DisplayName("a missing path and a null leaf are skipped when the client writes the value")
		void shouldSkipNullLeavesOfAWrittenValue() {
			final ComplexDataObject partial = new ComplexDataObject(
				map(
					"blocks", new DataItemArray(
						new DataItem[]{
							map("text", new DataItemValue("only block")),
							map("caption", new DataItemValue("no text here"))
						}
					)
				)
			);
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1).setAssociatedData(ARTICLE, Locale.ENGLISH, partial)
				)
			);
			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(ARTICLE), 1, "only block");
		}

	}

	@Nested
	@DisplayName("Values that do not fit the text expression")
	class UnfitValues {

		@BeforeEach
		void goLive() {
			FulltextAssociatedDataWritePathTest.this.goLive();
		}

		@Test
		@DisplayName("a written value with a leaf that is not a string fails the write and leaves the index untouched")
		void shouldRefuseAWrittenValueWithANonStringLeaf() {
			final ComplexDataObject numeric = new ComplexDataObject(
				map("blocks", new DataItemArray(new DataItem[]{map("text", new DataItemValue(42L))}))
			);
			assertWriteRefusedAndIndexUntouched(numeric);
		}

		@Test
		@DisplayName("a written value the expression cannot be evaluated over fails the write and leaves the index")
		void shouldRefuseAWrittenValueTheExpressionCannotNavigate() {
			// `blocks` is a plain string here, so the spread of the expression fails over it
			assertWriteRefusedAndIndexUntouched(
				new ComplexDataObject(map("blocks", new DataItemValue("not a list")))
			);
		}

		@Test
		@DisplayName("a scope change moves a value that would fail a write leniently, its removal undoes just that")
		void shouldMoveAndRemoveAnUnfitValueLeniently() {
			// stored while the associated data is not searchable in the live scope - nothing evaluated it then
			final ComplexDataObject mixed = new ComplexDataObject(
				map(
					"blocks", new DataItemArray(
						new DataItem[]{
							map("text", new DataItemValue("archived block")),
							map("text", new DataItemValue(7L))
						}
					)
				)
			);
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1).setAssociatedData(ARCHIVED_ARTICLE, Locale.ENGLISH, mixed)
				)
			);

			// the engine moves the value - the number is skipped rather than failing the move
			write(session -> session.archiveEntity(PRODUCT, 1));
			assertIndexed(Scope.ARCHIVED, Locale.ENGLISH, associatedData(ARCHIVED_ARTICLE), 1, "archived block");

			// the removal re-extracts it leniently too, and so removes exactly what the move indexed
			write(
				session -> session.getEntity(PRODUCT, 1, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.removeAssociatedData(ARCHIVED_ARTICLE, Locale.ENGLISH)
					.upsertVia(session)
			);
			assertNotIndexed(Scope.ARCHIVED, Locale.ENGLISH, associatedData(ARCHIVED_ARTICLE), 1);
			final FulltextIndex archived = requireFulltextIndex(Scope.ARCHIVED, Locale.ENGLISH);
			assertEquals(
				0, archived.getFieldLengths(archived.getFieldId(associatedData(ARCHIVED_ARTICLE))).size(),
				"The removal left a length behind."
			);
		}

		/**
		 * Indexes a well-formed article of entity 1, then asserts that writing the passed article over it is refused
		 * and that the index still holds the well-formed one.
		 *
		 * @param unfit the article that does not fit the text expression
		 */
		private void assertWriteRefusedAndIndexUntouched(@Nonnull ComplexDataObject unfit) {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1)
						.setAssociatedData(ARTICLE, Locale.ENGLISH, article("t", "first block"))
				)
			);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> write(
					session -> session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow()
						.openForWrite()
						.setAssociatedData(ARTICLE, Locale.ENGLISH, unfit)
						.upsertVia(session)
				)
			);

			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(ARTICLE), 1, "first block");
		}

	}

	@Nested
	@DisplayName("Scope change")
	class ScopeChange {

		@BeforeEach
		void goLive() {
			FulltextAssociatedDataWritePathTest.this.goLive();
		}

		@Test
		@DisplayName("archiving keeps only what is searchable in the archive, restoring brings everything back")
		void shouldMoveSearchableValuesWithTheScope() {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1)
						.setAssociatedData(BODY, Locale.ENGLISH, "green tea")
						.setAssociatedData(KEYWORDS, Locale.ENGLISH, new String[]{"herbal"})
						.setAssociatedData(ARTICLE, Locale.ENGLISH, article("t", "leaf block"))
				)
			);

			write(session -> session.archiveEntity(PRODUCT, 1));

			assertNotIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(BODY), 1);
			assertNotIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(KEYWORDS), 1);
			assertNotIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(ARTICLE), 1);
			// `body` is searchable in the archive too, the others are not
			assertIndexed(Scope.ARCHIVED, Locale.ENGLISH, associatedData(BODY), 1, "green tea");
			assertNotIndexed(Scope.ARCHIVED, Locale.ENGLISH, associatedData(KEYWORDS), 1);
			assertNotIndexed(Scope.ARCHIVED, Locale.ENGLISH, associatedData(ARTICLE), 1);

			write(session -> session.restoreEntity(PRODUCT, 1));

			assertNotIndexed(Scope.ARCHIVED, Locale.ENGLISH, associatedData(BODY), 1);
			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(BODY), 1, "green tea");
			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(KEYWORDS), 1, "herbal");
			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(ARTICLE), 1, "leaf block");
		}

	}

	@Nested
	@DisplayName("Schema changes over indexed data")
	class SchemaChanges {

		@BeforeEach
		void goLive() {
			FulltextAssociatedDataWritePathTest.this.goLive();
		}

		@Test
		@DisplayName("the next write retires a withdrawn field, and a re-declared field starts empty")
		void shouldRetireAWithdrawnFieldOnTheNextWrite() {
			upsertBody(1, "green tea");
			upsertBody(2, "black tea");
			final int originalFieldId = requireFulltextIndex(Scope.LIVE, Locale.ENGLISH)
				.getFieldId(associatedData(BODY));
			assertNotEquals(FulltextIndex.UNKNOWN_FIELD_ID, originalFieldId);

			alterBody(AssociatedDataSchemaEditor::nonSearchable);
			assertEquals(
				originalFieldId, requireFulltextIndex(Scope.LIVE, Locale.ENGLISH).getFieldId(associatedData(BODY))
			);

			upsertBody(1, "white tea");
			final FulltextIndex afterWithdrawal = requireFulltextIndex(Scope.LIVE, Locale.ENGLISH);
			assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, afterWithdrawal.getFieldId(associatedData(BODY)));
			assertTrue(afterWithdrawal.isFieldRetired(originalFieldId));

			alterBody(whichIs -> whichIs.searchableInScope(Scope.LIVE, Scope.ARCHIVED));
			upsertBody(2, "red tea");

			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(BODY), 2, "red tea");
			// entity 1 was written while the associated data was not searchable - missing, never phantom
			assertNotIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(BODY), 1);
		}

		@Test
		@DisplayName("a changed text expression leaves the field extracting the way it was built until it is rebuilt")
		void shouldKeepExtractingWithTheRecordedExpression() {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1)
						.setAssociatedData(ARTICLE, Locale.ENGLISH, article("first title", "first block"))
				)
			);
			write(
				session -> session.getEntitySchemaOrThrow(PRODUCT)
					.openForWrite()
					.withAssociatedData(
						ARTICLE, ComplexDataObject.class,
						whichIs -> whichIs.searchable(ExpressionFactory.parse("$value.title"))
					)
					.updateVia(session)
			);
			write(
				session -> session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow()
					.openForWrite()
					.setAssociatedData(ARTICLE, Locale.ENGLISH, article("second title", "second block"))
					.upsertVia(session)
			);

			// the removal found exactly what the add indexed, and the new value was selected the old way too
			final FulltextIndex english = requireFulltextIndex(Scope.LIVE, Locale.ENGLISH);
			assertEquals(BLOCKS_EXPRESSION, english.getTextExpression(english.getFieldId(associatedData(ARTICLE))));
			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(ARTICLE), 1, "second block");
		}

		@Test
		@DisplayName("a written value breaking only an older expression the field still extracts with is accepted")
		void shouldAcceptAWriteBreakingOnlyTheRecordedExpression() {
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 1)
						.setAssociatedData(ARTICLE, Locale.ENGLISH, article("first title", "first block"))
				)
			);
			write(
				session -> session.getEntitySchemaOrThrow(PRODUCT)
					.openForWrite()
					.withAssociatedData(
						ARTICLE, ComplexDataObject.class,
						whichIs -> whichIs.searchable(ExpressionFactory.parse("$value.title"))
					)
					.updateVia(session)
			);
			// fits the new expression, but the spread of the recorded one fails over it - the client cannot see that
			// expression, so the write is not refused
			final ComplexDataObject titleOnly = new ComplexDataObject(
				map("title", new DataItemValue("new title"), "blocks", new DataItemValue("no longer a list"))
			);
			write(
				session -> session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow()
					.openForWrite()
					.setAssociatedData(ARTICLE, Locale.ENGLISH, titleOnly)
					.upsertVia(session)
			);

			// the old value was removed and the new one selected nothing the old way - incomplete, never phantom
			assertNotIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(ARTICLE), 1);
		}

	}

	@Nested
	@DisplayName("Persistence")
	class Persistence {

		@Test
		@DisplayName("values written in bulk load are read back after a restart, the field expression included")
		void shouldReloadValuesWrittenInBulkLoad() {
			upsertBody(1, "green tea");
			write(
				session -> session.upsertEntity(
					session.createNewEntity(PRODUCT, 2)
						.setAssociatedData(ARTICLE, Locale.ENGLISH, article("t", "bulk block"))
				)
			);

			restart();

			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(BODY), 1, "green tea");
			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(ARTICLE), 2, "bulk block");
			final FulltextIndex english = requireFulltextIndex(Scope.LIVE, Locale.ENGLISH);
			assertEquals(BLOCKS_EXPRESSION, english.getTextExpression(english.getFieldId(associatedData(ARTICLE))));
		}

		@Test
		@DisplayName("values written in transactions are read back after a restart")
		void shouldReloadValuesWrittenTransactionally() {
			goLive();
			upsertBody(1, "green tea");
			upsertBody(1, "black coffee");
			upsertBody(2, "green apple");

			restart();

			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(BODY), 1, "black coffee");
			assertIndexed(Scope.LIVE, Locale.ENGLISH, associatedData(BODY), 2, "green apple");
		}

	}

	/**
	 * Builds an article: a title and blocks of text.
	 *
	 * @param title  the title, never selected by the expression
	 * @param blocks the texts of the blocks
	 * @return the article as a complex data object
	 */
	@Nonnull
	private static ComplexDataObject article(@Nonnull String title, @Nonnull String... blocks) {
		final DataItem[] blockItems = new DataItem[blocks.length];
		for (int i = 0; i < blocks.length; i++) {
			blockItems[i] = map("text", new DataItemValue(blocks[i]), "order", new DataItemValue((long) i));
		}
		return new ComplexDataObject(
			map("title", new DataItemValue(title), "blocks", new DataItemArray(blockItems))
		);
	}

	/**
	 * Builds a map node of the passed key/value pairs, keeping their order.
	 *
	 * @param keysAndValues alternating keys and nodes
	 * @return the map node
	 */
	@Nonnull
	private static DataItemMap map(@Nonnull Object... keysAndValues) {
		final Map<String, DataItem> children = CollectionUtils.createLinkedHashMap(keysAndValues.length / 2);
		for (int i = 0; i < keysAndValues.length; i += 2) {
			children.put((String) keysAndValues[i], (DataItem) keysAndValues[i + 1]);
		}
		return new DataItemMap(children);
	}

	/**
	 * Upserts the English body of a product, creating the product when it does not exist.
	 *
	 * @param primaryKey the product
	 * @param body       the body
	 */
	private void upsertBody(int primaryKey, @Nonnull String body) {
		write(
			session -> session.upsertEntity(
				session.getEntity(PRODUCT, primaryKey, entityFetchAllContent())
					.map(it -> it.openForWrite())
					.orElseGet(() -> session.createNewEntity(PRODUCT, primaryKey))
					.setAssociatedData(BODY, Locale.ENGLISH, body)
			)
		);
	}

	/**
	 * Alters the schema of the body associated data.
	 *
	 * @param alteration the alteration
	 */
	private void alterBody(@Nonnull Consumer<AssociatedDataSchemaEditor> alteration) {
		write(
			session -> session.getEntitySchemaOrThrow(PRODUCT)
				.openForWrite()
				.withAssociatedData(BODY, String.class, alteration)
				.updateVia(session)
		);
	}

	/**
	 * Asserts that the terms the entity holds in the field are exactly the terms the texts analyze to.
	 *
	 * @param scope      the scope of the global index
	 * @param locale     the locale of the fulltext index
	 * @param fieldKey   the field
	 * @param primaryKey the entity
	 * @param texts      the texts the entity's field is expected to hold
	 */
	private void assertIndexed(
		@Nonnull Scope scope,
		@Nonnull Locale locale,
		@Nonnull FulltextFieldKey fieldKey,
		int primaryKey,
		@Nonnull String... texts
	) {
		final Set<String> expected = new TreeSet<>();
		final FulltextAnalyzerRegistry registry = catalog().getFulltextAnalyzerRegistry();
		for (final String text : texts) {
			for (final AnalyzedTerm term : registry.getIndexAnalyzer(PRODUCT, locale).getTerms(text)) {
				expected.add(term.term());
			}
		}
		assertFalse(expected.isEmpty(), "The fixture texts must produce terms.");
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
	 * Runs the writes in a read-write session of the catalog.
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
