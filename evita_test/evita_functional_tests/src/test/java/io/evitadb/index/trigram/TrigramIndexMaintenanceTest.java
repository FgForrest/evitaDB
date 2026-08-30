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

package io.evitadb.index.trigram;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that a {@link GlobalEntityIndex} maintains a {@link TrigramIndex} for exactly the attributes that declare
 * {@link AttributeFilterAccelerator#SUBSTRING_SEARCH} in its own scope, that the postings it fills are keyed by the
 * value ids the shared value tree mints, and that both structures appear and disappear together.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("Trigram index maintenance on the global entity index")
class TrigramIndexMaintenanceTest {

	private static final String ENTITY_TYPE = "Product";
	private static final String REFERENCE_NAME = "brand";
	private static final String ATTRIBUTE_TITLE = "title";
	private static final String ATTRIBUTE_TAGS = "tags";
	private static final String ATTRIBUTE_PLAIN = "plain";
	private static final String ATTRIBUTE_LOCALIZED_TITLE = "localizedTitle";
	private static final String ATTRIBUTE_ARCHIVED_ONLY = "archivedOnly";
	private static final String ATTRIBUTE_ACCENTED = "accented";
	private static final Set<Locale> ALLOWED_LOCALES = Set.of(Locale.ENGLISH);
	private static final int INDEX_PK = 1;

	/**
	 * `COMBINING ACUTE ACCENT` — the mark Unicode NFD splits off an `é`, and therefore a code point the shared value
	 * tree really stores for an accented `String` attribute.
	 */
	private static final int COMBINING_ACUTE_ACCENT = 0x0301;

	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG, NamingConvention.generate(APITestConstants.TEST_CATALOG),
		null,
		EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
	);

	/**
	 * One product schema carrying every attribute shape these tests need:
	 *
	 * - `title` — filterable `String` with the SUBSTRING capability in the LIVE scope
	 * - `tags` — the same, of a `String[]` type, so the array write path is exercised
	 * - `plain` — filterable `String` WITHOUT the capability, the negative control
	 * - `localizedTitle` — localized, so each locale gets its own trigram index
	 * - `archivedOnly` — the capability declared in the ARCHIVED scope only
	 * - `accented` — capability-declaring, written with text the `String` normalizer visibly rewrites
	 *
	 * There is deliberately no unique capability-declaring attribute: a unique attribute is implicitly filterable and
	 * may not be declared filterable as well, so a filter capability cannot be attached to one at all — the trigram
	 * write path and the folded-uniqueness enforcement can never meet on the same attribute.
	 */
	private static final EntitySchemaContract SCHEMA = new InternalEntitySchemaBuilder(
		CATALOG_SCHEMA, EntitySchema._internalBuild(ENTITY_TYPE)
	)
		.withAttribute(ATTRIBUTE_TITLE, String.class,
			thatIs -> thatIs.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
		)
		.withAttribute(ATTRIBUTE_TAGS, String[].class,
			thatIs -> thatIs.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
		)
		.withAttribute(ATTRIBUTE_PLAIN, String.class, AttributeSchemaEditor::filterable)
		.withAttribute(
			ATTRIBUTE_LOCALIZED_TITLE, String.class,
			thatIs -> thatIs.localized().filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
		)
		.withAttribute(
			ATTRIBUTE_ARCHIVED_ONLY, String.class,
			thatIs -> thatIs
				.filterableInScope(Scope.ARCHIVED)
				.acceleratedForInScope(Scope.ARCHIVED, AttributeFilterAccelerator.SUBSTRING_SEARCH)
		)
		.withAttribute(ATTRIBUTE_ACCENTED, String.class,
			thatIs -> thatIs.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
		)
		.toInstance();

	/**
	 * The very same product, as the schema reads after the SUBSTRING capability has been withdrawn from `title` —
	 * which is a legal mutation even on a populated collection, because dropping an index needs no data. The write
	 * path decides what to maintain from the attribute schema it is handed, so this is exactly what it sees from the
	 * withdrawal onwards.
	 */
	private static final EntitySchemaContract SCHEMA_AFTER_WITHDRAWAL = new InternalEntitySchemaBuilder(
		CATALOG_SCHEMA, EntitySchema._internalBuild(ENTITY_TYPE)
	)
		.withAttribute(ATTRIBUTE_TITLE, String.class, AttributeSchemaEditor::filterable)
		.toInstance();

	/**
	 * @param name the attribute name declared on {@link #SCHEMA}
	 * @return the assembled entity-level attribute schema
	 */
	@Nonnull
	private static EntityAttributeSchemaContract attribute(@Nonnull String name) {
		return SCHEMA.getAttribute(name).orElseThrow();
	}

	/**
	 * @param name the attribute name declared on {@link #SCHEMA_AFTER_WITHDRAWAL}
	 * @return the same attribute as {@link #attribute(String)} names, minus the SUBSTRING capability
	 */
	@Nonnull
	private static EntityAttributeSchemaContract attributeWithoutCapability(@Nonnull String name) {
		return SCHEMA_AFTER_WITHDRAWAL.getAttribute(name).orElseThrow();
	}

	/**
	 * @param scope the scope of the index
	 * @return a fresh, empty global index in that scope
	 */
	@Nonnull
	private static GlobalEntityIndex indexInScope(@Nonnull Scope scope) {
		return new GlobalEntityIndex(INDEX_PK, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL, scope));
	}

	/**
	 * @param name   the attribute name
	 * @param locale the locale, or `null` for a language-agnostic attribute
	 * @return the key both the shared value tree and the trigram map are filed under
	 */
	@Nonnull
	private static AttributeIndexKey keyOf(@Nonnull String name, Locale locale) {
		return new AttributeIndexKey(null, name, locale);
	}

	/**
	 * @param text the three characters of the trigram
	 * @return the packed key they form
	 */
	private static long trigram(@Nonnull String text) {
		return TrigramCodec.pack(text.charAt(0), text.charAt(1), text.charAt(2));
	}

	/**
	 * Resolves the id the shared value tree stamped a value with, which is what the postings are keyed by.
	 *
	 * @param index the index holding the tree
	 * @param key   the attribute and locale
	 * @param value the raw value
	 * @return the stable value id
	 */
	private static int valueIdOf(
		@Nonnull GlobalEntityIndex index, @Nonnull AttributeIndexKey key, @Nonnull Serializable value
	) {
		final FilterIndex filterIndex = index.getFilterIndex(key);
		assertNotNull(filterIndex, "the shared value tree must exist by now");
		return filterIndex.getInvertedIndex().getValueId(value);
	}

	@Nested
	@DisplayName("only the attributes that declare the capability get one")
	class Applicability {

		@Test
		@DisplayName("the first write to a capable attribute creates its trigram index")
		void shouldCreateTheIndexOnTheFirstWrite() {
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			assertTrue(index.getTrigramIndexKeys().isEmpty());

			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);

			assertEquals(Set.of(keyOf(ATTRIBUTE_TITLE, null)), index.getTrigramIndexKeys());
			assertNotNull(index.getTrigramIndex(keyOf(ATTRIBUTE_TITLE, null)));
		}

		@Test
		@DisplayName("an attribute without the capability gets none")
		void shouldNotCreateOneWithoutTheCapability() {
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_PLAIN), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);
			assertTrue(index.getTrigramIndexKeys().isEmpty());
			assertNull(index.getTrigramIndex(keyOf(ATTRIBUTE_PLAIN, null)));
		}

		@Test
		@DisplayName("a capability declared in another scope does not reach this index")
		void shouldIgnoreACapabilityOfAnotherScope() {
			// capabilities are per scope, and a LIVE index maintaining an ARCHIVED-only capability would spend memory
			// on postings no query in its scope can ever consult
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_ARCHIVED_ONLY), ALLOWED_LOCALES, Scope.ARCHIVED, null, "abcd", 1);
			assertTrue(index.getTrigramIndexKeys().isEmpty());
		}

		@Test
		@DisplayName("a localized attribute gets one index per locale")
		void shouldKeepOneIndexPerLocale() {
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			final Set<Locale> locales = Set.of(Locale.ENGLISH, Locale.GERMAN);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_LOCALIZED_TITLE), locales, Scope.LIVE, Locale.ENGLISH, "abcd", 1);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_LOCALIZED_TITLE), locales, Scope.LIVE, Locale.GERMAN, "wxyz", 2);

			assertEquals(
				Set.of(
					keyOf(ATTRIBUTE_LOCALIZED_TITLE, Locale.ENGLISH),
					keyOf(ATTRIBUTE_LOCALIZED_TITLE, Locale.GERMAN)
				),
				index.getTrigramIndexKeys()
			);
			final TrigramIndex english = index.getTrigramIndex(keyOf(ATTRIBUTE_LOCALIZED_TITLE, Locale.ENGLISH));
			assertNotNull(english);
			assertEquals(0, english.cardinalityOf(trigram("wxy")), "a locale must not see another locale's values");
		}

		@Test
		@DisplayName("a reduced index maintains none, and pays for no id column either")
		void shouldNotMaintainAnIndexOnAReducedIndex() {
			// the four primitives are intercepted on the GLOBAL index rather than on the attribute index precisely so
			// that a reduced index keeps reaching the untouched base implementation: it hosts no postings, so an id
			// column there would be written by every entity fan-out and read by nobody
			final ReducedEntityIndex index = new ReducedEntityIndex(
				INDEX_PK, ENTITY_TYPE,
				new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
					new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, 1))
				)
			);
			final ReferenceSchemaContract referenceSchema = mock(ReferenceSchemaContract.class);
			when(referenceSchema.getReferenceIndexType(any(Scope.class)))
				.thenReturn(ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING);

			index.insertFilterAttribute(
				referenceSchema, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, null, "abcd", 1, false);

			final FilterIndex filterIndex = index.getFilterIndex(keyOf(ATTRIBUTE_TITLE, null));
			assertNotNull(filterIndex, "the ordinary filter structure is still maintained here");
			assertFalse(filterIndex.getInvertedIndex().carriesValueIds());
			assertTrue(filterIndex.getInvertedIndex().getValueIdConsumerNames().isEmpty());
		}

		@Test
		@DisplayName("a capability-declaring reference attribute is refused, not half-indexed")
		void shouldRefuseMaintainingAnIndexForAReferenceAttribute() {
			// the load path skips every reference-scoped key - it resolves attribute names against the ENTITY schema
			// alone - so a write that built postings for one would have them silently discarded on the next catalog
			// open, and substring queries would under-report after a restart with nothing saying why. The schema layer
			// refuses the declaration today and documents the refusal as liftable; this premise is what makes the day
			// it is lifted loud rather than silent, and it is the write-side half of the load-side skip
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			final ReferenceSchemaContract referenceSchema = mock(ReferenceSchemaContract.class);
			when(referenceSchema.getName()).thenReturn(REFERENCE_NAME);

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> index.insertFilterAttribute(
					referenceSchema, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, null, "abcd", 1, false)
			);
			assertTrue(
				error.getPrivateMessage().contains(REFERENCE_NAME),
				"the refusal must name the reference, but was: " + error.getPrivateMessage()
			);
			assertTrue(index.getTrigramIndexKeys().isEmpty(), "and nothing may have been built before it threw");
		}

		@Test
		@DisplayName("a reference attribute without the capability is written as usual")
		void shouldLeaveAPlainReferenceAttributeAlone() {
			// the premise above must fire on the capability, not on the reference: an ordinary reference attribute
			// still reaches the base primitive on the global index, exactly as it did before the interception existed
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			final ReferenceSchemaContract referenceSchema = mock(ReferenceSchemaContract.class);
			when(referenceSchema.getName()).thenReturn(REFERENCE_NAME);

			index.insertFilterAttribute(
				referenceSchema, attribute(ATTRIBUTE_PLAIN), ALLOWED_LOCALES, null, "abcd", 1, false);

			assertTrue(index.getTrigramIndexKeys().isEmpty());
			// the structure is filed under an ENTITY-level key even though a reference schema was passed: the key
			// factory drops the reference name for every entity attribute schema, which is the second reason - beside
			// the schema-mutation refusal - that a reference-scoped trigram key cannot arise today
			assertNotNull(
				index.getFilterIndex(keyOf(ATTRIBUTE_PLAIN, null)),
				"the ordinary filter structure is still maintained"
			);
		}

		@Test
		@DisplayName("interception leaves the filter structure exactly as the base primitive would")
		void shouldLeaveTheFilterStructureIdenticalWhetherOrNotItIntercepts() {
			// the intercepting override does not call `super` - it re-issues the very same call itself, plus the sink -
			// so a future addition to the base primitive could be skipped on the trigram branch with nothing to notice
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_PLAIN), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);

			final FilterIndex intercepted = index.getFilterIndex(keyOf(ATTRIBUTE_TITLE, null));
			final FilterIndex untouched = index.getFilterIndex(keyOf(ATTRIBUTE_PLAIN, null));
			assertArrayEquals(
				untouched.getAllRecords().getArray(), intercepted.getAllRecords().getArray(),
				"both writes must have reached the same records"
			);
			assertArrayEquals(
				untouched.getRecordsEqualTo("abcd").getArray(), intercepted.getRecordsEqualTo("abcd").getArray(),
				"and the same records must answer the same value"
			);
			// the id column is the ONLY difference the interception is allowed to make
			assertTrue(intercepted.getInvertedIndex().carriesValueIds());
			assertFalse(untouched.getInvertedIndex().carriesValueIds());
		}

	}

	@Nested
	@DisplayName("postings follow the shared value tree's value ids")
	class Postings {

		@Test
		@DisplayName("a written value posts against the id its bucket was stamped with")
		void shouldPostAgainstTheMintedValueId() {
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);

			final AttributeIndexKey key = keyOf(ATTRIBUTE_TITLE, null);
			final int valueId = valueIdOf(index, key, "abcd");
			final TrigramIndex trigramIndex = index.getTrigramIndex(key);
			assertNotNull(trigramIndex);
			assertArrayEquals(new int[]{valueId}, trigramIndex.getValueIdsOf(trigram("abc")).getArray());
			assertArrayEquals(new int[]{valueId}, trigramIndex.getValueIdsOf(trigram("bcd")).getArray());
		}

		@Test
		@DisplayName("a second entity repeating a value mints no id and changes no posting")
		void shouldLeaveThePostingsAloneWhenAValueRepeats() {
			// the update-amplification collapse the whole design exists for: two entities sharing a value cost the
			// substring index nothing whatsoever
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			final AttributeIndexKey key = keyOf(ATTRIBUTE_TITLE, null);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);

			final TrigramIndex trigramIndex = index.getTrigramIndex(key);
			assertNotNull(trigramIndex);
			final int trigramsBefore = trigramIndex.getTrigramCount();
			final int nextValueIdBefore = index.getFilterIndex(key).getInvertedIndex().getNextValueId();

			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 2);

			assertEquals(nextValueIdBefore, index.getFilterIndex(key).getInvertedIndex().getNextValueId());
			assertEquals(trigramsBefore, trigramIndex.getTrigramCount());
			assertEquals(1, trigramIndex.cardinalityOf(trigram("abc")));
		}

		@Test
		@DisplayName("a value posts for as long as any entity holds it, and no longer")
		void shouldDropTheValueWhenItsLastEntityLeaves() {
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			final AttributeIndexKey key = keyOf(ATTRIBUTE_TITLE, null);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 2);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "wxyz", 3);
			final TrigramIndex trigramIndex = index.getTrigramIndex(key);
			assertNotNull(trigramIndex);

			index.removeAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);
			assertEquals(1, trigramIndex.cardinalityOf(trigram("abc")), "the value still has an entity");

			index.removeAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 2);
			assertEquals(0, trigramIndex.cardinalityOf(trigram("abc")), "the value has lost its last entity");
			assertEquals(1, trigramIndex.cardinalityOf(trigram("wxy")), "the other value is untouched");
		}

		@Test
		@DisplayName("every element of an array attribute is indexed on its own")
		void shouldIndexEveryElementOfAnArray() {
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			final AttributeIndexKey key = keyOf(ATTRIBUTE_TAGS, null);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TAGS), ALLOWED_LOCALES, Scope.LIVE, null,
				new String[]{"abcd", "wxyz"}, 1
			);

			final TrigramIndex trigramIndex = index.getTrigramIndex(key);
			assertNotNull(trigramIndex);
			assertArrayEquals(
				new int[]{valueIdOf(index, key, "abcd")}, trigramIndex.getValueIdsOf(trigram("abc")).getArray()
			);
			assertArrayEquals(
				new int[]{valueIdOf(index, key, "wxyz")}, trigramIndex.getValueIdsOf(trigram("wxy")).getArray()
			);
		}

		@Test
		@DisplayName("the elements an array delta adds are indexed on their own")
		void shouldIndexTheElementsAnArrayDeltaAdds() {
			// the delta path is a separate primitive from the whole-value upsert and reaches a different
			// InvertedIndex overload, so it needs its own coverage rather than riding on the array upsert above
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			final AttributeIndexKey key = keyOf(ATTRIBUTE_TAGS, null);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TAGS), ALLOWED_LOCALES, Scope.LIVE, null, new String[]{"abcd"}, 1);

			index.addDeltaFilterAttribute(
				null, attribute(ATTRIBUTE_TAGS), ALLOWED_LOCALES, null, new String[]{"wxyz"}, 1, false);

			final TrigramIndex trigramIndex = index.getTrigramIndex(key);
			assertNotNull(trigramIndex);
			assertArrayEquals(
				new int[]{valueIdOf(index, key, "wxyz")}, trigramIndex.getValueIdsOf(trigram("wxy")).getArray()
			);
			assertArrayEquals(
				new int[]{valueIdOf(index, key, "abcd")}, trigramIndex.getValueIdsOf(trigram("abc")).getArray()
			);
		}

		@Test
		@DisplayName("the elements an array delta removes leave the postings")
		void shouldDropTheElementsAnArrayDeltaRemoves() {
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			final AttributeIndexKey key = keyOf(ATTRIBUTE_TAGS, null);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TAGS), ALLOWED_LOCALES, Scope.LIVE, null,
				new String[]{"abcd", "wxyz"}, 1
			);

			index.removeDeltaFilterAttribute(
				null, attribute(ATTRIBUTE_TAGS), ALLOWED_LOCALES, null, new String[]{"wxyz"}, 1);

			final TrigramIndex trigramIndex = index.getTrigramIndex(key);
			assertNotNull(trigramIndex);
			assertEquals(0, trigramIndex.cardinalityOf(trigram("wxy")));
			assertEquals(1, trigramIndex.cardinalityOf(trigram("abc")));
		}

		@Test
		@DisplayName("a value too short to carry a trigram is indexed by nothing")
		void shouldIndexNothingForAShortValue() {
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "ab", 1);
			final TrigramIndex trigramIndex = index.getTrigramIndex(keyOf(ATTRIBUTE_TITLE, null));
			assertNotNull(trigramIndex, "the index is created for the attribute even when this value contributes none");
			assertTrue(trigramIndex.isEmpty());
		}

		@Test
		@DisplayName("the trigrams posted are those of the normalized form the shared value tree stores")
		void shouldIndexTheNormalizedFormTheSharedValueTreeStores() {
			// a `String` attribute is stored in Unicode NFD, so `café` reaches the codec as FIVE code points with the
			// accent split off its base letter. The query path derives a pattern's trigrams from the same normalized
			// form, so an index built over the precomposed text would answer nothing for the accented half of it
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_ACCENTED), ALLOWED_LOCALES, Scope.LIVE, null, "café", 1);

			final TrigramIndex trigramIndex = index.getTrigramIndex(keyOf(ATTRIBUTE_ACCENTED, null));
			assertNotNull(trigramIndex);
			assertEquals(3, trigramIndex.getTrigramCount(), "five code points contribute three trigrams");
			assertEquals(
				1, trigramIndex.cardinalityOf(TrigramCodec.pack('f', 'e', COMBINING_ACUTE_ACCENT)),
				"the trigram straddling the decomposed accent must be posted"
			);
			assertEquals(
				0, trigramIndex.cardinalityOf(TrigramCodec.pack('a', 'f', 'é')),
				"and nothing must be posted for the precomposed form the caller passed in"
			);
		}

		@Test
		@DisplayName("a value replaced outright is dropped, and the replacement posts against a fresh id")
		void shouldFollowAValueThroughAnUpdateThatReplacesIt() {
			// the most ordinary update there is: a delta removes the old value first, which empties the shared value
			// tree of a single-entity collection and takes the trigram index with it, so the insert half has to mint
			// both again - with the value id sequence restarted at one
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			final AttributeIndexKey key = keyOf(ATTRIBUTE_TITLE, null);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);

			index.applyAttributeDelta(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", "wxyz", 1);

			assertEquals(Set.of(key), index.getTrigramIndexKeys(), "the index must have been minted again");
			final TrigramIndex trigramIndex = index.getTrigramIndex(key);
			assertNotNull(trigramIndex);
			assertEquals(0, trigramIndex.cardinalityOf(trigram("abc")), "the replaced value posts nothing");
			assertArrayEquals(
				new int[]{valueIdOf(index, key, "wxyz")}, trigramIndex.getValueIdsOf(trigram("wxy")).getArray()
			);
		}

		@Test
		@DisplayName("a value replaced while another entity keeps the tree alive reuses the same index")
		void shouldKeepTheIndexWhenAnotherEntityHoldsTheValue() {
			// the other half of the same update: the tree survives, so its id sequence carries on and the very same
			// trigram index has to absorb both the death and the birth. The two halves fail differently, and only
			// together do they pin the claim that the two structures move in lockstep
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			final AttributeIndexKey key = keyOf(ATTRIBUTE_TITLE, null);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "qrst", 2);
			final TrigramIndex trigramIndex = index.getTrigramIndex(key);
			assertNotNull(trigramIndex);

			index.applyAttributeDelta(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", "wxyz", 1);

			assertSame(trigramIndex, index.getTrigramIndex(key), "a tree that survives keeps its own index");
			assertEquals(0, trigramIndex.cardinalityOf(trigram("abc")));
			assertArrayEquals(
				new int[]{valueIdOf(index, key, "wxyz")}, trigramIndex.getValueIdsOf(trigram("wxy")).getArray()
			);
			assertArrayEquals(
				new int[]{valueIdOf(index, key, "qrst")}, trigramIndex.getValueIdsOf(trigram("qrs")).getArray(),
				"the value the other entity holds is untouched"
			);
		}

		@Test
		@DisplayName("an array repeating one element posts it exactly once")
		void shouldReportEachDistinctElementOnceWhenAnArrayRepeatsOne() {
			// the array write does not deduplicate its elements, so the repeat reaches the shared value tree a second
			// time and has to be absorbed there - the tree's bucket count does not move, so no second birth is
			// reported and the value id is not added twice
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			final AttributeIndexKey key = keyOf(ATTRIBUTE_TAGS, null);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TAGS), ALLOWED_LOCALES, Scope.LIVE, null,
				new String[]{"abcd", "abcd"}, 1
			);

			final TrigramIndex trigramIndex = index.getTrigramIndex(key);
			assertNotNull(trigramIndex);
			assertEquals(1, trigramIndex.cardinalityOf(trigram("abc")));
			assertArrayEquals(
				new int[]{valueIdOf(index, key, "abcd")}, trigramIndex.getValueIdsOf(trigram("abc")).getArray()
			);
		}

		@Test
		@DisplayName("a whole-array removal takes out every element it held")
		void shouldDropEveryElementAnArrayRemovalTakesOut() {
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			final AttributeIndexKey key = keyOf(ATTRIBUTE_TAGS, null);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TAGS), ALLOWED_LOCALES, Scope.LIVE, null,
				new String[]{"abcd", "wxyz"}, 1
			);

			index.removeAttribute(
				null, attribute(ATTRIBUTE_TAGS), ALLOWED_LOCALES, Scope.LIVE, null,
				new String[]{"abcd", "wxyz"}, 1
			);

			assertNull(index.getFilterIndex(key), "the shared value tree lost its last record");
			assertNull(index.getTrigramIndex(key), "and the trigram index left with it");
		}

		@Test
		@DisplayName("a value too short to carry a trigram dies without complaint")
		void shouldTolerateTheDeathOfAValueThatContributedNoTrigram() {
			// the removal side extracts nothing for such a value and therefore never reaches the divergence premise.
			// If the minimal-length rule ever diverged between the two sides, an ordinary write would start refusing
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "ab", 1);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 2);

			index.removeAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "ab", 1);

			final TrigramIndex trigramIndex = index.getTrigramIndex(keyOf(ATTRIBUTE_TITLE, null));
			assertNotNull(trigramIndex);
			assertEquals(1, trigramIndex.cardinalityOf(trigram("abc")), "the indexable value is untouched");
		}

	}

	@Nested
	@DisplayName("maintenance survives a commit")
	@Tag(TRANSACTION)
	class Transactions {

		@Test
		@DisplayName("an index created inside a transaction is published with its postings")
		void shouldPublishAnIndexCreatedInsideATransaction() {
			// the map entry, the shared value tree, its allocator and the postings are all born inside the same
			// transaction here, so this is the one case where every part of the wiring has to merge correctly at once
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			assertStateAfterCommit(
				index,
				original -> original.upsertAttribute(
					null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1),
				(original, committed) -> {
					final AttributeIndexKey key = keyOf(ATTRIBUTE_TITLE, null);
					final TrigramIndex trigramIndex = committed.getTrigramIndex(key);
					assertNotNull(trigramIndex, "the committed index must carry the accelerator");
					assertArrayEquals(
						new int[]{valueIdOf(committed, key, "abcd")},
						trigramIndex.getValueIdsOf(trigram("abc")).getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("a value born inside a transaction reaches the postings of the committed index")
		void shouldPublishAValueBornInsideATransaction() {
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);
			assertStateAfterCommit(
				index,
				original -> original.upsertAttribute(
					null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "xabc", 2),
				(original, committed) -> {
					final AttributeIndexKey key = keyOf(ATTRIBUTE_TITLE, null);
					final TrigramIndex trigramIndex = committed.getTrigramIndex(key);
					assertNotNull(trigramIndex);
					assertEquals(2, trigramIndex.cardinalityOf(trigram("abc")));
					assertArrayEquals(
						new int[]{valueIdOf(committed, key, "xabc")},
						trigramIndex.getValueIdsOf(trigram("xab")).getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("a drop performed inside a transaction is published too")
		void shouldPublishADropPerformedInsideATransaction() {
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);
			assertStateAfterCommit(
				index,
				original -> original.removeAttribute(
					null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1),
				(original, committed) -> {
					assertNull(committed.getFilterIndex(keyOf(ATTRIBUTE_TITLE, null)));
					assertNull(committed.getTrigramIndex(keyOf(ATTRIBUTE_TITLE, null)));
				}
			);
		}

		@Test
		@DisplayName("a rolled-back transaction leaves no accelerator behind")
		void shouldLeaveNoAcceleratorBehindWhenTheTransactionRollsBack() {
			// the map entry and the shared value tree are created by one and the same write, so they have to roll back
			// together too - an entry surviving a rolled-back tree is the orphan shape the fast path cannot survive
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			assertStateAfterRollback(
				index,
				original -> original.upsertAttribute(
					null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1),
				(original, committed) -> {
					assertTrue(original.getTrigramIndexKeys().isEmpty(), "the map entry must have rolled back");
					assertNull(original.getFilterIndex(keyOf(ATTRIBUTE_TITLE, null)), "and the tree with it");
				}
			);
		}

		@Test
		@DisplayName("the key set answers through the caller's own transaction")
		void shouldAnswerTheKeysThroughTheCallersOwnTransaction() {
			// the snapshot test proves only that the set is copied - it runs outside any transaction, where the map is
			// mutated in place, so it says nothing about whether an uncommitted key is visible to anyone but its writer
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);
			assertStateAfterRollback(
				index,
				original -> {
					original.upsertAttribute(
						null, attribute(ATTRIBUTE_TAGS), ALLOWED_LOCALES, Scope.LIVE, null,
						new String[]{"wxyz"}, 1
					);
					assertEquals(
						Set.of(keyOf(ATTRIBUTE_TITLE, null), keyOf(ATTRIBUTE_TAGS, null)),
						original.getTrigramIndexKeys(),
						"the writer must see the attribute it has just started indexing"
					);
				},
				(original, committed) -> assertEquals(
					Set.of(keyOf(ATTRIBUTE_TITLE, null)), original.getTrigramIndexKeys(),
					"and nobody else ever does, because the transaction rolled back"
				)
			);
		}

	}

	@Nested
	@DisplayName("the accelerators are re-derived on load")
	class Reload {

		/**
		 * Builds the shared value trees a freshly loaded index would hand the rebuild — one carrying value ids for a
		 * capable attribute, one plain tree for an attribute without the capability, and one filed under a reference
		 * name.
		 *
		 * @return the trees, keyed as the attribute index keys them
		 */
		@Nonnull
		private Map<AttributeIndexKey, InvertedIndex> reloadedSharedValueTrees() {
			final Map<AttributeIndexKey, InvertedIndex> trees = new HashMap<>(4);
			final InvertedIndex capable = new InvertedIndex(
				String.class, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0
			);
			capable.addRecord("abcd", 1);
			capable.addRecord("xabc", 2);
			// exactly what the loader leaves behind: the ids are restored from the persisted column and high-water,
			// and NO consumer is registered - the registry is owner-resident and never reaches disk
			capable.restoreValueIds(3, new int[]{1, 2});
			trees.put(keyOf(ATTRIBUTE_TITLE, null), capable);

			final InvertedIndex plain = new InvertedIndex(
				String.class, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0
			);
			plain.addRecord("abcd", 1);
			trees.put(keyOf(ATTRIBUTE_PLAIN, null), plain);

			final InvertedIndex onAReference = new InvertedIndex(
				String.class, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0
			);
			onAReference.addRecord("abcd", 1);
			trees.put(new AttributeIndexKey(REFERENCE_NAME, ATTRIBUTE_TITLE, null), onAReference);
			return trees;
		}

		/**
		 * Builds a single-value shared value tree in the state a load leaves it in — the value present, its id
		 * restored from the persisted column, and no consumer registered.
		 *
		 * @param value the one value the tree holds
		 * @return the reloaded tree
		 */
		@Nonnull
		private InvertedIndex reloadedTree(@Nonnull String value) {
			final InvertedIndex tree = new InvertedIndex(
				String.class, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0
			);
			tree.addRecord(value, 1);
			tree.restoreValueIds(2, new int[]{1});
			return tree;
		}

		@Test
		@DisplayName("only the capable entity-level attributes are rebuilt")
		void shouldRebuildOnlyTheCapableEntityLevelAttributes() {
			final Map<AttributeIndexKey, TrigramIndex> rebuilt =
				TrigramIndex.rebuildAll(SCHEMA, Scope.LIVE, reloadedSharedValueTrees());

			assertEquals(Set.of(keyOf(ATTRIBUTE_TITLE, null)), rebuilt.keySet());
			final TrigramIndex titleIndex = rebuilt.get(keyOf(ATTRIBUTE_TITLE, null));
			assertArrayEquals(new int[]{1, 2}, titleIndex.getValueIdsOf(trigram("abc")).getArray());
			assertArrayEquals(new int[]{1}, titleIndex.getValueIdsOf(trigram("bcd")).getArray());
			assertArrayEquals(new int[]{2}, titleIndex.getValueIdsOf(trigram("xab")).getArray());
		}

		@Test
		@DisplayName("the value id consumer is registered again, because the tree does not persist it")
		void shouldReattachTheValueIdConsumer() {
			// the id column comes back inside the pages but the registry is owner-resident, so without this the tree
			// would carry ids nothing admits to needing - and an operator asking what they are for gets no answer
			final Map<AttributeIndexKey, InvertedIndex> trees = reloadedSharedValueTrees();
			TrigramIndex.rebuildAll(SCHEMA, Scope.LIVE, trees);
			assertEquals(
				Set.of(TrigramIndex.VALUE_ID_CONSUMER_NAME),
				trees.get(keyOf(ATTRIBUTE_TITLE, null)).getValueIdConsumerNames()
			);
		}

		@Test
		@DisplayName("a tree the rebuild cannot use fails the load instead of being skipped")
		void shouldFailTheLoadRatherThanSkipAnUnusableTree() {
			// persisted state and schema disagreeing - a populated tree carrying no ids under an attribute that
			// declares the capability - must stop the catalog opening. Opening it with the accelerator silently
			// absent would make every substring query against that attribute quietly match too few entities
			final Map<AttributeIndexKey, InvertedIndex> trees = reloadedSharedValueTrees();
			final InvertedIndex withoutIds = new InvertedIndex(
				String.class, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0
			);
			withoutIds.addRecord("abcd", 1);
			trees.put(keyOf(ATTRIBUTE_TAGS, null), withoutIds);

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> TrigramIndex.rebuildAll(SCHEMA, Scope.LIVE, trees)
			);
			// naming WHICH premise fires matters: the rebuild attaches the consumer before it walks the tree, so the
			// refusal comes from the attach's empty-tree rule rather than from the walk's. Asserting only the
			// exception type would not notice the attach moving after the walk, where the failure would change shape
			assertTrue(
				error.getPrivateMessage().contains("still empty"),
				"the refusal must name the empty-tree premise, but was: " + error.getPrivateMessage()
			);
		}

		@Test
		@DisplayName("a capability the schema no longer declares in this scope rebuilds nothing")
		void shouldRebuildNothingForAnotherScope() {
			// a capability withdrawn while the catalog was down simply stops costing anything from the next load on
			assertTrue(TrigramIndex.rebuildAll(SCHEMA, Scope.ARCHIVED, reloadedSharedValueTrees()).isEmpty());
		}

		@Test
		@DisplayName("a tree whose attribute the schema no longer declares is passed by")
		void shouldSkipATreeWhoseAttributeTheSchemaNoLongerDeclares() {
			// an attribute DELETED while the catalog was down leaves its tree on disk with nothing in the schema to
			// resolve it against; that is a different arm from a capability withdrawal, and the load must simply pass
			// it by rather than fail on the missing declaration
			final Map<AttributeIndexKey, InvertedIndex> trees = reloadedSharedValueTrees();
			trees.put(keyOf("goneAttribute", null), reloadedTree("abcd"));

			final Map<AttributeIndexKey, TrigramIndex> rebuilt =
				TrigramIndex.rebuildAll(SCHEMA, Scope.LIVE, trees);

			assertEquals(Set.of(keyOf(ATTRIBUTE_TITLE, null)), rebuilt.keySet());
		}

		@Test
		@DisplayName("a localized attribute comes back as one independent index per locale")
		void shouldRebuildOneIndexPerLocaleOfALocalizedAttribute() {
			// every other reload fixture here is language-agnostic, so a rebuild keyed by attribute name rather than by
			// the whole attribute key would pass the entire suite while collapsing every locale onto one set of
			// postings - and a substring query in one language would answer with another language's entities
			final Map<AttributeIndexKey, InvertedIndex> trees = reloadedSharedValueTrees();
			trees.put(keyOf(ATTRIBUTE_LOCALIZED_TITLE, Locale.ENGLISH), reloadedTree("abcd"));
			trees.put(keyOf(ATTRIBUTE_LOCALIZED_TITLE, Locale.GERMAN), reloadedTree("wxyz"));

			final Map<AttributeIndexKey, TrigramIndex> rebuilt =
				TrigramIndex.rebuildAll(SCHEMA, Scope.LIVE, trees);

			assertEquals(
				Set.of(
					keyOf(ATTRIBUTE_TITLE, null),
					keyOf(ATTRIBUTE_LOCALIZED_TITLE, Locale.ENGLISH),
					keyOf(ATTRIBUTE_LOCALIZED_TITLE, Locale.GERMAN)
				),
				rebuilt.keySet()
			);
			final TrigramIndex english = rebuilt.get(keyOf(ATTRIBUTE_LOCALIZED_TITLE, Locale.ENGLISH));
			final TrigramIndex german = rebuilt.get(keyOf(ATTRIBUTE_LOCALIZED_TITLE, Locale.GERMAN));
			assertArrayEquals(new int[]{1}, english.getValueIdsOf(trigram("abc")).getArray());
			assertEquals(0, english.cardinalityOf(trigram("wxy")), "a locale must not see another locale's values");
			assertArrayEquals(new int[]{1}, german.getValueIdsOf(trigram("wxy")).getArray());
			assertEquals(0, german.cardinalityOf(trigram("abc")));
		}

	}

	@Nested
	@DisplayName("the two structures live and die together")
	class Lifecycle {

		@Test
		@DisplayName("the trigram index is dropped when its shared value tree empties out")
		void shouldDropTheIndexWithItsSharedValueTree() {
			// the postings are keyed by that tree's value ids, and a tree created again later starts its id sequence
			// over - so a surviving trigram index would post yesterday's ids against tomorrow's values
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			final AttributeIndexKey key = keyOf(ATTRIBUTE_TITLE, null);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);
			assertNotNull(index.getTrigramIndex(key));

			index.removeAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);

			assertNull(index.getFilterIndex(key), "the shared value tree must be gone");
			assertNull(index.getTrigramIndex(key), "and the trigram index with it");
			assertTrue(index.getTrigramIndexKeys().isEmpty());
		}

		@Test
		@DisplayName("the key set handed out is a snapshot, not a live view of the map")
		void shouldHandOutTheKeysAsASnapshot() {
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);
			final Set<AttributeIndexKey> keys = index.getTrigramIndexKeys();

			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TAGS), ALLOWED_LOCALES, Scope.LIVE, null, new String[]{"wxyz"}, 1);

			assertEquals(
				Set.of(keyOf(ATTRIBUTE_TITLE, null)), keys,
				"a set handed out earlier must not observe an attribute indexed afterwards"
			);
		}

		@Test
		@DisplayName("the id column of the shared value tree is switched on for the capable attribute only")
		void shouldSwitchTheIdColumnOnForTheCapableAttributeOnly() {
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_PLAIN), ALLOWED_LOCALES, Scope.LIVE, null, "abcd", 1);

			assertTrue(index.getFilterIndex(keyOf(ATTRIBUTE_TITLE, null)).getInvertedIndex().carriesValueIds());
			assertEquals(
				Set.of(TrigramIndex.VALUE_ID_CONSUMER_NAME),
				index.getFilterIndex(keyOf(ATTRIBUTE_TITLE, null)).getInvertedIndex().getValueIdConsumerNames()
			);
			// an attribute without the capability must not pay for an id column at all
			assertFalse(index.getFilterIndex(keyOf(ATTRIBUTE_PLAIN, null)).getInvertedIndex().carriesValueIds());
		}

		@Test
		@DisplayName("a capability withdrawn from a populated attribute takes its trigram index with it")
		void shouldDropTheIndexWhenTheCapabilityIsWithdrawn() {
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			final AttributeIndexKey key = keyOf(ATTRIBUTE_TITLE, null);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "alphabet", 1);
			assertNotNull(index.getTrigramIndex(key));

			// the capability is gone from here on: the write path is handed the attribute as the mutated schema
			// declares it, and the value tree stays alive because the first entity still holds its own value
			index.upsertAttribute(
				null, attributeWithoutCapability(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "betamax", 2);

			assertNull(
				index.getTrigramIndex(key),
				"an index nothing maintains any more must not outlive the capability that asked for it"
			);
			assertEquals(Set.of(), index.getTrigramIndexKeys());
			// the shared value tree is untouched by the reconciliation - only the accelerator over it goes away
			assertTrue(valueIdOf(index, key, "betamax") > 0);
		}

		@Test
		@DisplayName("a removal is enough to reconcile a withdrawn capability too")
		void shouldDropTheIndexWhenTheFirstWriteAfterAWithdrawalIsARemoval() {
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			final AttributeIndexKey key = keyOf(ATTRIBUTE_TITLE, null);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "alphabet", 1);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "betamax", 2);
			assertNotNull(index.getTrigramIndex(key));

			// the removal shape has to reconcile as well: whichever write comes first after the withdrawal is the one
			// that has to notice, and a removal that leaves the tree alive would otherwise never reach the drop hook
			index.removeAttribute(
				null, attributeWithoutCapability(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "betamax", 2);

			assertNull(index.getTrigramIndex(key));
			assertEquals(Set.of(), index.getTrigramIndexKeys());
			assertNotNull(index.getFilterIndex(key), "the tree still holds the value the first entity carries");
		}

		@Test
		@DisplayName("a capability declared again after its value tree emptied indexes the next write")
		void shouldRebuildTheIndexAfterTheCapabilityComesBack() {
			final GlobalEntityIndex index = indexInScope(Scope.LIVE);
			final AttributeIndexKey key = keyOf(ATTRIBUTE_TITLE, null);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "alphabet", 1);

			// withdraw, then let the last entity go: the removal takes the base path, and reconciling the map there is
			// what keeps the accelerator from outliving the tree whose ids its postings are keyed by
			index.removeAttribute(
				null, attributeWithoutCapability(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "alphabet", 1);
			assertNull(index.getFilterIndex(key), "the shared value tree is gone");
			assertNull(index.getTrigramIndex(key), "and so is the trigram index that indexed it");

			// re-declaring the capability is accepted, because the collection is empty again by now
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "betamax", 2);

			final TrigramIndex rebuilt = index.getTrigramIndex(key);
			assertNotNull(rebuilt, "the write that follows the re-declaration creates a fresh index");
			assertEquals(
				Set.of(TrigramIndex.VALUE_ID_CONSUMER_NAME),
				index.getFilterIndex(key).getInvertedIndex().getValueIdConsumerNames(),
				"the brand new tree has to be told to mint ids again"
			);
			assertArrayEquals(
				new int[]{valueIdOf(index, key, "betamax")}, rebuilt.getValueIdsOf(trigram("bet")).getArray(),
				"and the postings name the id the new tree minted, not one of the dead sequence"
			);
		}

	}

}
