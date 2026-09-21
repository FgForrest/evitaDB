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


package io.evitadb.store.catalog;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex.AttributeCardinalityKey;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Function;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the audit half of the v6 to v7 upgrade: the part that does not repair anything, but tells the operator
 * that a reindex is needed and — just as importantly — when it could not tell them anything at all.
 *
 * {@link Migration_2026_3_Test} pins the counter re-key as a pure transform and
 * {@link Migration_2026_3_RepairRoundTripTest} drives that transform over real bytes. Neither can reach this
 * code, because the audit compares a counter against the SIBLING value tree and therefore needs both halves of a
 * storage pair. `EvitaBackwardCompatibilityTest` has both halves but holds no damaged catalog and no colliding
 * pair, so the branches that matter never execute there either.
 *
 * The test this file exists for is the first one. An audit that normalizes the counter key and then looks it up
 * among RAW stored buckets misses on every entry of a perfectly healthy catalog and tells its operator to
 * reindex — a false alarm is strictly worse than no diagnostic, because it burns the trust that makes the true
 * alarm actionable.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Migration_2026_3 — pre-existing damage audit (v6→v7)")
@Tag(STORAGE)
@Tag(INDEXING)
@SuppressWarnings("removal") // the migration interface is @Deprecated(forRemoval); testing it is the point
class Migration_2026_3_AuditTest {

	private static final int ENTITY_INDEX_PK = 42;
	private static final AttributeIndexKey ATTRIBUTE = new AttributeIndexKey("variants", "size", null);
	/** The scale the schema froze into the index — two owners' values agree only once rescaled to it. */
	private static final int SCALE = 2;
	private static final int FIRST_OWNER = 100;
	private static final int SECOND_OWNER = 200;

	/** Precomposed `é` — one code point, U+00E9. */
	private static final String PRECOMPOSED = "café";
	/** NFD decomposition of the same word — `e` followed by a combining acute accent. */
	private static final String DECOMPOSED = "café";

	// ---------------------------------------------------------------------------------------------------------
	// the stored side is normalized too
	// ---------------------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a healthy range counter over buckets written before ranges were rescaled reports NO damage")
	void shouldNotReportDamageOverStoredRangesThatWereNeverRescaled() {
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(BigDecimalNumberRange.class, SCALE);
		// what a catalog that reached protocol 6 by upgrade from 2026.1 actually holds: `getNormalizer` had no
		// range branch at all before 2026.2 and `Migration_2026_2#rekeyFilterIndex` re-keys only String and
		// BigDecimal parts, so the stored bucket carries the range at its INTRINSIC scale
		final BigDecimalNumberRange storedRange = BigDecimalNumberRange.between(
			new BigDecimal("1.5"), new BigDecimal("2.5")
		);
		final Serializable canonicalRange = normalizer.apply(storedRange);
		assertNotEquals(
			storedRange, canonicalRange,
			"premise of this whole test: `NumberRange` equality is defined on the SCALED thresholds, so the raw " +
				"and rescaled forms of one range must be unequal - were they equal, the audit could not tell a " +
				"normalized lookup from a raw one and this test would pass for the wrong reason"
		);

		final int orphaned = Migration_2026_3.countOrphanedCounters(
			Map.of(new AttributeCardinalityKey(FIRST_OWNER, canonicalRange), 1),
			single(BigDecimalNumberRange.class, new ValueToRecordBitmap(storedRange, FIRST_OWNER)),
			BigDecimalNumberRange.class,
			normalizer
		);

		assertEquals(
			0, orphaned,
			"the owner IS in its bucket, so this collection is undamaged - looking a canonical key up among raw " +
				"stored buckets misses here and tells the operator to reindex an intact catalog"
		);
	}

	@Test
	@DisplayName("two stored buckets that collapse onto one key are UNIONED, so neither side reads as orphaned")
	void shouldUnionStoredBucketsThatCollapseOntoOneKey() {
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(BigDecimalNumberRange.class, SCALE);
		// the same mathematical range written by two owners at different precision: 2026.1 stored each at its own
		// intrinsic scale, so they are two DISTINCT stored buckets that rescale onto a single key
		final BigDecimalNumberRange coarse = BigDecimalNumberRange.between(new BigDecimal("1.5"), new BigDecimal("2.5"));
		final BigDecimalNumberRange fine = BigDecimalNumberRange.between(new BigDecimal("1.50"), new BigDecimal("2.50"));
		assertNotEquals(coarse, fine, "premise: the two stored buckets must be distinct keys on disk");
		final Serializable canonicalRange = normalizer.apply(coarse);
		assertEquals(
			canonicalRange, normalizer.apply(fine),
			"premise: and they must collapse onto ONE key once rescaled, or nothing needs unioning"
		);

		final int orphaned = Migration_2026_3.countOrphanedCounters(
			Map.of(
				new AttributeCardinalityKey(FIRST_OWNER, canonicalRange), 1,
				new AttributeCardinalityKey(SECOND_OWNER, canonicalRange), 1
			),
			single(
				BigDecimalNumberRange.class,
				new ValueToRecordBitmap(coarse, FIRST_OWNER),
				new ValueToRecordBitmap(fine, SECOND_OWNER)
			),
			BigDecimalNumberRange.class,
			normalizer
		);

		assertEquals(
			0, orphaned,
			"both owners are in a bucket that normalizes onto their key - keeping only the last of the two " +
				"colliding buckets would drop the other owner's records and report them as missing entries"
		);
	}

	// ---------------------------------------------------------------------------------------------------------
	// what the audit is FOR: real damage, and only real damage
	// ---------------------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a counter whose record left the bucket it names is counted as one missing entry")
	void shouldCountACounterWhoseEntryIsGone() {
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(String.class, 0);
		final Serializable canonical = normalizer.apply(PRECOMPOSED);

		final int orphaned = Migration_2026_3.countOrphanedCounters(
			Map.of(
				new AttributeCardinalityKey(FIRST_OWNER, canonical), 1,
				new AttributeCardinalityKey(SECOND_OWNER, canonical), 1
			),
			// the shared entry survived for the first owner but no longer lists the second - exactly the state the
			// defect leaves behind when the first owner to depart drains a counter the second still needed
			single(String.class, new ValueToRecordBitmap(DECOMPOSED, FIRST_OWNER)),
			String.class,
			normalizer
		);

		assertEquals(1, orphaned, "the second owner's entry is gone and must be reported");
	}

	@Test
	@DisplayName("an undamaged string counter reports nothing")
	void shouldReportNothingWhenEveryCounterHasItsRecord() {
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(String.class, 0);
		final Serializable canonical = normalizer.apply(PRECOMPOSED);

		final int orphaned = Migration_2026_3.countOrphanedCounters(
			Map.of(
				new AttributeCardinalityKey(FIRST_OWNER, canonical), 1,
				new AttributeCardinalityKey(SECOND_OWNER, canonical), 1
			),
			single(String.class, new ValueToRecordBitmap(DECOMPOSED, FIRST_OWNER, SECOND_OWNER)),
			String.class,
			normalizer
		);

		assertEquals(0, orphaned, "both owners are in the shared entry, so there is nothing to report");
	}

	// ---------------------------------------------------------------------------------------------------------
	// silence must never read as a clean bill of health
	// ---------------------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a paged filter index is reported as not checked, naming the paging as the reason")
	void shouldNotVerifyAPagedFilterIndex() {
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(String.class, 0);
		final FilterIndexStoragePart pagedPart = FilterIndexStoragePart.paged(
			ENTITY_INDEX_PK, ATTRIBUTE, String.class, null, 0, 0, new int[]{0}, 1L
		);

		assertEquals(
			Migration_2026_3.NOT_VERIFIABLE,
			Migration_2026_3.countOrphanedCounters(
				Map.of(new AttributeCardinalityKey(FIRST_OWNER, normalizer.apply(PRECOMPOSED)), 1),
				pagedPart, String.class, normalizer
			),
			"the buckets live in separate leaf-page records, so nothing in this part can answer the question"
		);
		assertEquals(
			"filter index is stored in paged form", Migration_2026_3.whyNotChecked(String.class, pagedPart),
			"the operator must be told WHICH of the two blind spots they are in"
		);
	}

	@Test
	@DisplayName("a counter with no surviving filter sibling is reported as not checked, naming the absence")
	void shouldNotVerifyACounterWithNoSiblingFilterIndex() {
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(String.class, 0);

		assertEquals(
			Migration_2026_3.NOT_VERIFIABLE,
			Migration_2026_3.countOrphanedCounters(
				Map.of(new AttributeCardinalityKey(FIRST_OWNER, normalizer.apply(PRECOMPOSED)), 1),
				null, String.class, normalizer
			),
			"there is no value tree to compare the counter against"
		);
		assertEquals(
			"no sibling filter index", Migration_2026_3.whyNotChecked(String.class, null),
			"a missing index and an unwalkable one are different situations and must read differently"
		);
	}

	@Test
	@DisplayName("a type that cannot collide answers zero rather than staying silent")
	void shouldAnswerZeroForATypeThatCannotCollide() {
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(Integer.class, 0);

		assertEquals(
			0,
			Migration_2026_3.countOrphanedCounters(
				Map.of(new AttributeCardinalityKey(FIRST_OWNER, 7), 1), null, Integer.class, normalizer
			),
			"an Integer counter key is the identity in every version that could have written this catalog, so two " +
				"owners could never have shared one entry - zero is an ANSWER here, not a blind spot, and the " +
				"missing sibling part is therefore irrelevant"
		);
		assertNull(
			Migration_2026_3.whyNotChecked(Integer.class, null),
			"nothing was skipped, so the operator must not be handed a reason as if something had been"
		);
	}

	@Test
	@DisplayName("every unverifiable count carries a reason, and every verified one carries none")
	void shouldPairEveryNotVerifiableCountWithAReason() {
		final FilterIndexStoragePart pagedPart = FilterIndexStoragePart.paged(
			ENTITY_INDEX_PK, ATTRIBUTE, String.class, null, 0, 0, new int[]{0}, 1L
		);
		final Class<?>[] types = {
			String.class, BigDecimal.class, BigDecimalNumberRange.class, java.time.OffsetDateTime.class,
			Integer.class, Boolean.class, java.time.LocalDateTime.class
		};
		final FilterIndexStoragePart[] siblings = {null, pagedPart, single(String.class)};

		for (final Class<?> type : types) {
			for (final FilterIndexStoragePart sibling : siblings) {
				final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(type, SCALE);
				final int count = Migration_2026_3.countOrphanedCounters(Map.of(), sibling, type, normalizer);
				final String reason = Migration_2026_3.whyNotChecked(type, sibling);
				assertEquals(
					count == Migration_2026_3.NOT_VERIFIABLE, reason != null,
					"the count and the explanation are two halves of one report and must agree for " +
						type.getSimpleName() + " with sibling " + describeSibling(sibling) + " - a count with no " +
						"reason leaves an unexplained gap, and a reason with no count names a cause that did not " +
						"apply"
				);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// which scale the counter is canonicalized at
	// ---------------------------------------------------------------------------------------------------------

	@Test
	@DisplayName("the sibling's frozen scale wins over a schema that has since changed")
	void shouldPreferTheSiblingFrozenScaleOverTheSchema() {
		final OptionalInt scale = Migration_2026_3.resolveScale(
			schemaWithDecimalPlaces(4), null, storageKey(),
			// the scale the tree's keys were actually encoded at, frozen into the index when it was created
			single(BigDecimal.class, 2), BigDecimal.class
		);

		assertTrue(scale.isPresent());
		assertEquals(
			2, scale.getAsInt(),
			"the counter's job is to agree with the tree beside it, and the tree's keys are at the scale the tree " +
				"froze - re-keying at the schema's newer 4 would move every key to a bucket the tree does not have"
		);
	}

	@Test
	@DisplayName("the schema answers when no filter sibling survives to be asked")
	void shouldFallBackToTheSchemaScale() {
		final OptionalInt scale = Migration_2026_3.resolveScale(
			schemaWithDecimalPlaces(4), null, storageKey(), null, BigDecimal.class
		);

		assertTrue(scale.isPresent());
		assertEquals(4, scale.getAsInt(), "with no frozen scale on disk the schema is the only authority left");
	}

	@Test
	@DisplayName("an attribute the schema cannot answer for yields no scale, so its counter is left alone")
	void shouldYieldNoScaleWhenTheSchemaCannotAnswer() {
		final OptionalInt scale = Migration_2026_3.resolveScale(
			// a schema that never heard of `size` - a reflected reference's inherited attribute, or one the schema
			// has dropped while its index lingers
			schemaWithDecimalPlaces(4), "variants", storageKey(), null, BigDecimal.class
		);

		assertTrue(
			scale.isEmpty(),
			"guessing a scale would move every key to the wrong bucket; the caller must leave the part untouched " +
				"and say so, rather than abort the whole catalog upgrade over one unpriceable attribute"
		);
	}

	@Test
	@DisplayName("a type with no scale answers zero without consulting anything")
	void shouldAnswerZeroScaleForTypesThatHaveNone() {
		for (final Class<?> type : new Class<?>[]{String.class, Integer.class, java.time.OffsetDateTime.class}) {
			final OptionalInt scale = Migration_2026_3.resolveScale(
				schemaWithDecimalPlaces(4), "variants", storageKey(), null, type
			);
			assertTrue(scale.isPresent(), type.getSimpleName() + " must resolve without a schema or a sibling");
			assertEquals(
				0, scale.getAsInt(),
				"`getNormalizer` reads `indexedDecimalPlaces` only for the two BigDecimal-backed types, so every " +
					"other type must short-circuit before a schema lookup that would throw for " + type.getSimpleName()
			);
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// the message the operator actually reads
	// ---------------------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a long attribute list is capped and says how many it left out")
	void shouldCapTheAttributeList() {
		final List<String> attributes = new ArrayList<>();
		for (int i = 0; i < Migration_2026_3.MAX_REPORTED_ATTRIBUTES + 3; i++) {
			attributes.add("attribute" + i);
		}

		final String formatted = Migration_2026_3.formatAttributeList(attributes);

		assertTrue(formatted.startsWith("attribute0, attribute1,"), "the first names must survive verbatim");
		assertTrue(
			formatted.endsWith(" and 3 more"),
			"the tally is what keeps a corrupted collection's hundreds of attributes from pushing the REINDEX " +
				"instruction off the operator's screen, so it must say how many it dropped"
		);
		assertEquals(
			Migration_2026_3.MAX_REPORTED_ATTRIBUTES,
			formatted.split(", ").length,
			"exactly the cap many names, with the remainder folded into the tally"
		);
	}

	@Test
	@DisplayName("a shorter list is spelled out in full")
	void shouldSpellOutAShortAttributeList() {
		assertEquals(
			"a, b",
			Migration_2026_3.formatAttributeList(List.of("a", "b")),
			"below the cap nothing is dropped and no tally is invented"
		);
	}

	@Test
	@DisplayName("a counter is named by its reference, attribute, locale and index — never by its part id")
	void shouldNameACounterTheWayAnOperatorCanActOnIt() {
		assertEquals(
			"variants.size in entity index 42",
			Migration_2026_3.describeCounter(ENTITY_INDEX_PK, storageKey()),
			"the operator has no way to resolve a compressed storage part id back to an attribute"
		);
		assertEquals(
			"size in entity index 42",
			Migration_2026_3.describeCounter(
				ENTITY_INDEX_PK, storageKey(new AttributeIndexKey(null, "size", null))
			),
			"an entity-level attribute has no reference to prefix it with"
		);
		assertEquals(
			"variants.size [cs] in entity index 42",
			Migration_2026_3.describeCounter(
				ENTITY_INDEX_PK, storageKey(new AttributeIndexKey("variants", "size", Locale.forLanguageTag("cs")))
			),
			"one locale of a localized attribute can be damaged while the others are intact, so the locale is part " +
				"of the address"
		);
	}

	// ---------------------------------------------------------------------------------------------------------
	// fixtures
	// ---------------------------------------------------------------------------------------------------------

	/**
	 * Builds a `SINGLE`-shaped filter part holding the given buckets inline, at {@link #SCALE}.
	 */
	@Nonnull
	private static FilterIndexStoragePart single(
		@Nonnull Class<?> attributeType,
		@Nonnull ValueToRecordBitmap... buckets
	) {
		return single(attributeType, SCALE, buckets);
	}

	/**
	 * Builds a `SINGLE`-shaped filter part whose frozen `indexedDecimalPlaces` is stated explicitly.
	 */
	@Nonnull
	private static FilterIndexStoragePart single(
		@Nonnull Class<?> attributeType,
		int indexedDecimalPlaces,
		@Nonnull ValueToRecordBitmap... buckets
	) {
		return new FilterIndexStoragePart(
			ENTITY_INDEX_PK, ATTRIBUTE, attributeType, buckets, null, indexedDecimalPlaces, 1L
		);
	}

	/**
	 * Names a sibling shape for an assertion message — the three cases the audit distinguishes.
	 */
	@Nonnull
	private static String describeSibling(@Nullable FilterIndexStoragePart sibling) {
		return sibling == null ? "absent" : sibling.isPaged() ? "paged" : "inline";
	}

	/**
	 * The storage key of the counter under test, addressing the entity-level index of the collection.
	 */
	@Nonnull
	private static AttributeIndexStorageKey storageKey() {
		return storageKey(ATTRIBUTE);
	}

	@Nonnull
	private static AttributeIndexStorageKey storageKey(@Nonnull AttributeIndexKey attribute) {
		return new AttributeIndexStorageKey(
			new EntityIndexKey(EntityIndexType.GLOBAL), AttributeIndexType.CARDINALITY, attribute
		);
	}

	/**
	 * An entity schema declaring the ENTITY-LEVEL attribute `size` as a `BigDecimal` at the given scale — and
	 * declaring no reference, so a reference-scoped lookup of the same name is unanswerable.
	 */
	@Nonnull
	private static EntitySchema schemaWithDecimalPlaces(int indexedDecimalPlaces) {
		final EntitySchema base = EntitySchema._internalBuild("product");
		final EntitySchemaContract built = new InternalEntitySchemaBuilder(
			CatalogSchema._internalBuild(
				"testCatalog", NamingConvention.generate("testCatalog"), null,
				EnumSet.allOf(CatalogEvolutionMode.class),
				EmptyEntitySchemaAccessor.INSTANCE
			),
			base
		)
			.withAttribute(
				ATTRIBUTE.attributeName(), BigDecimal.class,
				whichIs -> whichIs.filterable().indexDecimalPlaces(indexedDecimalPlaces)
			)
			.toInstance();
		return (EntitySchema) built;
	}

}
