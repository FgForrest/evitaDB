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

package io.evitadb.index.attribute;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the fold of {@link UniqueIndex} into the shared `value→ValueToRecord` tree owned by
 * {@link AttributeIndex}. A foldable unique attribute (any non-localized one, or a localized one unique within
 * locale) keeps no standalone value map: reads are served by a VIEW over the shared filter tree, and uniqueness is
 * enforced on the filter insert. These tests drive the same insert/remove pairing the real
 * {@link io.evitadb.index.mutation.local.AttributeIndexMutator} performs for a unique-not-separately-filterable
 * attribute (unique-insert that registers the view, paired with a filter-insert that materializes the value and
 * enforces uniqueness).
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("UniqueIndex fold (folded view over the shared tree)")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class UniqueIndexFoldTest {

	private static final String ENTITY_TYPE = "product";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_TAGS = "tags";
	private static final Set<Locale> ALLOWED_LOCALES = Set.of(Locale.ENGLISH);

	/**
	 * Catalog + product schema scaffolding used to assemble {@link #SCHEMA} through the real
	 * {@link InternalEntitySchemaBuilder} (rather than Mockito stubs). The builder runs the production
	 * schema-assembly path, so every fixture is a schema the engine could actually receive.
	 */
	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG, NamingConvention.generate(APITestConstants.TEST_CATALOG),
		null,
		EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
	);
	private static final EntitySchema PRODUCT_SCHEMA = EntitySchema._internalBuild(ENTITY_TYPE);

	/**
	 * A single product schema carrying both foldable-unique shapes the tests need:
	 *
	 * - `code` — non-localized, collection-unique scalar (the unique key equals the filter key)
	 * - `tags` — non-localized, collection-unique {@link String}-array whose EACH element must be globally unique
	 *   (the builder derives `getPlainType() == String.class` from the `String[]` type — no hand-stubbing needed)
	 */
	private static final EntitySchemaContract SCHEMA = new InternalEntitySchemaBuilder(
		CATALOG_SCHEMA, PRODUCT_SCHEMA
	)
		.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::unique)
		.withAttribute(ATTRIBUTE_TAGS, String[].class, AttributeSchemaEditor::unique)
		.toInstance();

	/** Foldable scalar unique `code`: non-localized ⇒ the unique key equals the filter key. */
	private static final EntityAttributeSchemaContract FOLDABLE_SCALAR_CODE =
		SCHEMA.getAttribute(ATTRIBUTE_CODE).orElseThrow();
	/** Foldable array unique `tags`: non-localized array whose EACH element must be globally unique. */
	private static final EntityAttributeSchemaContract FOLDABLE_ARRAY_TAGS =
		SCHEMA.getAttribute(ATTRIBUTE_TAGS).orElseThrow();

	/**
	 * Mirrors the mutator's unique-not-separately-filterable insert: register the folded view, then shadow the value
	 * into the shared filter tree (which is where uniqueness is enforced).
	 */
	private static void insertFolded(
		@Nonnull AttributeIndex index, @Nonnull AttributeSchemaContract schema,
		@Nonnull Serializable value, int recordId
	) {
		final boolean foldedUnique = index.insertUniqueAttribute(null, schema, ALLOWED_LOCALES, Scope.LIVE, null, value, recordId)
			== AttributeIndex.UniquenessEnforcement.BY_FILTER_WRITE;
		index.insertFilterAttribute(null, schema, ALLOWED_LOCALES, null, value, recordId, foldedUnique);
	}

	/**
	 * Mirrors the mutator's unique-not-separately-filterable removal: the unique-remove is a no-op for a folded
	 * attribute; the filter-remove drops the value (and the view when the tree empties).
	 */
	private static void removeFolded(
		@Nonnull AttributeIndex index, @Nonnull AttributeSchemaContract schema,
		@Nonnull Serializable value, int recordId
	) {
		index.removeUniqueAttribute(null, schema, ALLOWED_LOCALES, Scope.LIVE, null, value, recordId);
		index.removeFilterAttribute(null, schema, ALLOWED_LOCALES, null, value, recordId);
	}

	@Nonnull
	private static UniqueIndex foldedView(@Nonnull AttributeIndex index, @Nonnull AttributeSchemaContract schema) {
		final UniqueIndex view = index.getUniqueIndex(null, schema, Scope.LIVE, null);
		assertNotNull(view, "folded unique view must resolve");
		return view;
	}

	@Nested
	@DisplayName("Read parity")
	class ReadParityTest {

		@Test
		@DisplayName("folded view resolves a unique value to its single record and exposes all records")
		void shouldResolveFoldedUniqueValueToRecord() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema = FOLDABLE_SCALAR_CODE;

			insertFolded(index, schema, "ABC", 1);
			insertFolded(index, schema, "DEF", 2);

			final UniqueIndex view = foldedView(index, schema);
			assertEquals(1, view.getRecordIdByUniqueValue("ABC"));
			assertEquals(2, view.getRecordIdByUniqueValue("DEF"));
			assertNull(view.getRecordIdByUniqueValue("ZZZ"));
			assertEquals(2, view.size());
			final Bitmap allRecords = view.getRecordIds();
			assertTrue(allRecords.contains(1));
			assertTrue(allRecords.contains(2));
		}

		@Test
		@DisplayName("removing the last value drops the folded view")
		void shouldDropViewWhenTreeEmpties() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema = FOLDABLE_SCALAR_CODE;

			insertFolded(index, schema, "ABC", 1);
			assertTrue(index.getUniqueIndexes().contains(new io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey(null, ATTRIBUTE_CODE, null)));

			removeFolded(index, schema, "ABC", 1);
			assertNull(index.getUniqueIndex(null, schema, Scope.LIVE, null));
		}
	}

	@Nested
	@DisplayName("Enforcement")
	class EnforcementTest {

		@Test
		@DisplayName("a second record claiming the same value is rejected")
		void shouldRejectDuplicateAcrossRecords() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema = FOLDABLE_SCALAR_CODE;

			insertFolded(index, schema, "ABC", 1);

			// account for record 2's uniqueness (folded ⇒ no-op), then the filter-insert must detect the conflict
			final boolean foldedUnique = index.insertUniqueAttribute(null, schema, ALLOWED_LOCALES, Scope.LIVE, null, "ABC", 2)
				== AttributeIndex.UniquenessEnforcement.BY_FILTER_WRITE;
			assertThrows(
				UniqueValueViolationException.class,
				() -> index.insertFilterAttribute(null, schema, ALLOWED_LOCALES, null, "ABC", 2, foldedUnique)
			);
			// record 1 still owns the value
			assertEquals(1, foldedView(index, schema).getRecordIdByUniqueValue("ABC"));
		}

		@Test
		@DisplayName("idempotent re-claim by the same record is allowed")
		void shouldAllowIdempotentReclaim() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema = FOLDABLE_SCALAR_CODE;

			insertFolded(index, schema, "ABC", 1);
			assertDoesNotThrow(() -> index.insertFilterAttribute(null, schema, ALLOWED_LOCALES, null, "ABC", 1, true));
			assertEquals(1, foldedView(index, schema).getRecordIdByUniqueValue("ABC"));
		}

		@Test
		@DisplayName("the same record may change its value (remove old, insert new)")
		void shouldAllowSameRecordValueReplace() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema = FOLDABLE_SCALAR_CODE;

			insertFolded(index, schema, "ABC", 1);
			// upsert order: remove old (unique no-op + filter remove), then insert new
			index.removeUniqueAttribute(null, schema, ALLOWED_LOCALES, Scope.LIVE, null, "ABC", 1);
			index.removeFilterAttribute(null, schema, ALLOWED_LOCALES, null, "ABC", 1);
			insertFolded(index, schema, "DEF", 1);

			final UniqueIndex view = foldedView(index, schema);
			assertNull(view.getRecordIdByUniqueValue("ABC"));
			assertEquals(1, view.getRecordIdByUniqueValue("DEF"));
		}

		@Test
		@DisplayName("the filter write self-registers the folded view and enforces, without any prior unique-insert")
		void shouldSelfContainFoldedHandlingInFilterWrite() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema = FOLDABLE_SCALAR_CODE;

			// call the filter write DIRECTLY with foldedUnique=true and NO preceding insertUniqueAttribute: the write is
			// self-contained — it registers the folded view (bound to the live tree) and enforces uniqueness on its own
			index.insertFilterAttribute(null, schema, ALLOWED_LOCALES, null, "ABC", 1, true);
			assertEquals(1, foldedView(index, schema).getRecordIdByUniqueValue("ABC"));

			// enforcement also lives entirely in the filter write: a second record claiming the value is rejected
			assertThrows(
				UniqueValueViolationException.class,
				() -> index.insertFilterAttribute(null, schema, ALLOWED_LOCALES, null, "ABC", 2, true)
			);
		}
	}

	@Nested
	@DisplayName("Array attributes (per-element uniqueness)")
	class ArrayTest {

		@Test
		@DisplayName("each array element is globally unique; one record owns many values")
		void shouldFoldArrayPerElement() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema = FOLDABLE_ARRAY_TAGS;

			insertFolded(index, schema, new String[]{"a", "b"}, 1);

			final UniqueIndex view = foldedView(index, schema);
			assertEquals(1, view.getRecordIdByUniqueValue("a"));
			assertEquals(1, view.getRecordIdByUniqueValue("b"));
		}

		@Test
		@DisplayName("an element overlapping another record's array is rejected and leaves no element half-applied")
		void shouldRejectOverlappingArrayElementAtomically() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema = FOLDABLE_ARRAY_TAGS;

			insertFolded(index, schema, new String[]{"a", "b"}, 1);

			// record 2 brings a fresh element "c" plus a conflicting element "a"; the whole insert must fail
			final boolean foldedUnique = index.insertUniqueAttribute(null, schema, ALLOWED_LOCALES, Scope.LIVE, null, new String[]{"c", "a"}, 2)
				== AttributeIndex.UniquenessEnforcement.BY_FILTER_WRITE;
			assertThrows(
				UniqueValueViolationException.class,
				() -> index.insertFilterAttribute(null, schema, ALLOWED_LOCALES, null, new String[]{"c", "a"}, 2, foldedUnique)
			);
			// verify-before-mutate: the fresh element "c" must NOT have been added
			final UniqueIndex view = foldedView(index, schema);
			assertNull(view.getRecordIdByUniqueValue("c"));
			assertEquals(1, view.getRecordIdByUniqueValue("a"));
			assertEquals(1, view.getRecordIdByUniqueValue("b"));
		}

		@Test
		@DisplayName("delta-add of a fresh element to an existing record is allowed and enforced")
		void shouldEnforceArrayDeltaAdd() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema = FOLDABLE_ARRAY_TAGS;

			insertFolded(index, schema, new String[]{"a"}, 1);
			insertFolded(index, schema, new String[]{"x"}, 2);

			// record 1 delta-adds "b" (free) -> ok
			assertDoesNotThrow(
				() -> index.addDeltaFilterAttribute(null, schema, ALLOWED_LOCALES, null, new String[]{"b"}, 1, true)
			);
			assertEquals(1, foldedView(index, schema).getRecordIdByUniqueValue("b"));

			// record 1 delta-adds "x" (owned by record 2) -> rejected
			assertThrows(
				UniqueValueViolationException.class,
				() -> index.addDeltaFilterAttribute(null, schema, ALLOWED_LOCALES, null, new String[]{"x"}, 1, true)
			);
		}
	}

	@Nested
	@DisplayName("Normalization")
	class NormalizationTest {

		@Test
		@DisplayName("a folded unique lookup folds canonically-equivalent Unicode forms (NFC vs NFD)")
		void shouldNormalizeFoldedLookup() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema = FOLDABLE_SCALAR_CODE;

			final String nfc = Normalizer.normalize("Café", Normalizer.Form.NFC); // "Café" precomposed
			final String nfd = Normalizer.normalize("Café", Normalizer.Form.NFD); // decomposed
			// sanity: the two forms are distinct strings but canonically equivalent
			assertNotEquals(nfc, nfd);

			insertFolded(index, schema, nfc, 1);

			final UniqueIndex view = foldedView(index, schema);
			assertEquals(1, view.getRecordIdByUniqueValue(nfc));
			assertEquals(1, view.getRecordIdByUniqueValue(nfd));
		}
	}
}
