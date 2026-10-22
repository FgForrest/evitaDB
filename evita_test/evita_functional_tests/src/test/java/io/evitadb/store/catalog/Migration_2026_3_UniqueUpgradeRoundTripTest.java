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

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.ObsoleteStorageProtocolException;
import io.evitadb.export.file.ExportFileService;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.CatalogIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueLeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import io.evitadb.spi.store.engine.CatalogFolderOperations;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.store.catalog.Migration_2025_6.NoChangeHeaderInfoSupplier;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drives the REAL v6→v7 upgrade of the standalone unique indexes end to end - the boot-time
 * `DefaultCatalogPersistenceService#verifyAndUpgradeStorageFormat` dispatch into
 * {@link Migration_2026_3#upgradeFromStorageProtocolVersion_6_to_7}, its collision pre-pass, its rewrite and its
 * flush - over a protocol-6 catalog built in code, with no binary fixture.
 *
 * {@link Migration_2026_3_UniqueRekeyTest} pins the re-key as a pure transform; it proves nothing about the
 * orchestration around it: that every unique part in the catalog file and in every collection file is found, inline
 * and paged, that a collision refuses the upgrade before a single byte is written, and that the rewritten parts load.
 * Only a catalog on disk can show that.
 *
 * # How the protocol-6 catalog is built
 *
 * For these parts the 2026.2 layout IS the current layout - only the values differ. A 2026.2 standalone unique index
 * stored every value exactly as written (an NFC text, a decimal finer than the indexed decimal places, an
 * `OffsetDateTime` at its own offset), ordered by the value's natural order (`BigDecimal` by value, then scale). So
 * the catalog is written by the current engine, and then, through the current persistence services and with the
 * engine stopped, every value of every standalone unique part is replaced by the spelling 2026.2 would have kept for
 * it, the entries are re-sorted into 2026.2's order across the original leaf boundaries, the catalog header is
 * rewritten with storage protocol 6 and the result is published by a new bootstrap record. The entity bodies already
 * hold the raw spellings, because the engine stores attribute values as written.
 *
 * Two departures from a release-written catalog, neither of which the upgrade reads: the bootstrap record carries the
 * current protocol version (the upgrade is dispatched on the CATALOG HEADER's version), and in the collision scenario
 * the colliding owner's entity body holds a placeholder - the current engine refuses to write the colliding value
 * itself, and the pre-pass reads only the unique parts.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Migration_2026_3 — standalone unique indexes through the real v6→v7 upgrade")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@SuppressWarnings("removal") // the migration interface is @Deprecated(forRemoval); testing it is the point
class Migration_2026_3_UniqueUpgradeRoundTripTest implements EvitaTestSupport {
	private static final String CATALOG = "uniqueUpgrade";
	private static final String PRODUCT = "product";
	private static final String CATEGORY = "category";
	/** Catalog attribute, `String`, `uniqueGlobally`. */
	private static final String CODE = "code";
	/** Catalog attribute, `BigDecimal`, `uniqueGlobally`, two indexed decimal places. */
	private static final String PRICE = "price";
	/** Catalog attribute, `OffsetDateTime`, `uniqueGlobally`. */
	private static final String VALID_FROM = "validFrom";
	/** Product attribute, `String`, localized, unique across locales - a standalone owner unique index. */
	private static final String URL = "url";
	/** Product attribute, `BigDecimal`, localized, unique across locales, two indexed decimal places. */
	private static final String WEIGHT = "weight";
	/** Product attribute, `OffsetDateTime`, localized, unique across locales. */
	private static final String RELEASED_AT = "releasedAt";
	/** The `indexedDecimalPlaces` of both decimal attributes. */
	private static final int DECIMAL_PLACES = 2;
	/** The declared type of every unique attribute of the catalog. */
	private static final Map<String, Class<? extends Serializable>> ATTRIBUTE_TYPES = Map.of(
		CODE, String.class, PRICE, BigDecimal.class, VALID_FROM, OffsetDateTime.class,
		URL, String.class, WEIGHT, BigDecimal.class, RELEASED_AT, OffsetDateTime.class
	);
	/** Every standalone unique index the catalog holds, as {@link StoredUniqueIndex#label()} names them. */
	private static final Set<String> STANDALONE_INDEXES = Set.of(
		"catalog " + CODE, "catalog " + PRICE, "catalog " + VALID_FROM,
		PRODUCT + " " + URL, PRODUCT + " " + WEIGHT, PRODUCT + " " + RELEASED_AT
	);
	/**
	 * The order a 2026.2 unique tree kept its raw values in: natural order, except that `BigDecimal` breaks a numeric
	 * tie by scale (`UniqueIndexBPlusTreeSupport.BIG_DECIMAL_EXACT_ORDER` of v2026.2).
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static final Comparator<Serializable> LEGACY_ORDER = (first, second) -> {
		if (first instanceof BigDecimal firstDecimal && second instanceof BigDecimal secondDecimal) {
			final int byValue = firstDecimal.compareTo(secondDecimal);
			return byValue == 0 ? Integer.compare(firstDecimal.scale(), secondDecimal.scale()) : byValue;
		}
		return ((Comparable) first).compareTo(second);
	};

	private static final String CHAJ_NFC = nfc("čaj");
	private static final String CHAJ_NFD = nfd("čaj");
	private static final String URL_NFC = nfc("/čaj");
	private static final String URL_NFD = nfd("/čaj");
	private static final String CATEGORY_CODE_NFC = nfc("kategorie-ř");
	private static final OffsetDateTime NOON_UTC = OffsetDateTime.of(2026, 5, 20, 12, 0, 0, 0, ZoneOffset.UTC);
	private static final ZoneOffset PLUS_TWO = ZoneOffset.ofHours(2);
	private static final ZoneOffset MINUS_FIVE = ZoneOffset.ofHours(-5);
	/** `validFrom` of `product:1`: noon UTC spelled at +02:00. */
	private static final OffsetDateTime PRODUCT_VALID_FROM = NOON_UTC.withOffsetSameInstant(PLUS_TWO);
	/** `releasedAt` of `product:1`: a day after noon UTC, spelled at -05:00. */
	private static final OffsetDateTime PRODUCT_RELEASED_AT = NOON_UTC.plusDays(1).withOffsetSameInstant(MINUS_FIVE);
	/** `validFrom` of `category:1`: two days after noon UTC, spelled at +09:00. */
	private static final OffsetDateTime CATEGORY_VALID_FROM =
		NOON_UTC.plusDays(2).withOffsetSameInstant(ZoneOffset.ofHours(9));
	/** Fillers start a year after every value of the named entities, so no filler can collide with them. */
	private static final OffsetDateTime FILLER_BASE = NOON_UTC.plusYears(1);
	private static final int FIRST_FILLER = 3;

	private final List<TestPaths> allocatedPaths = new ArrayList<>(2);

	/**
	 * The two shapes a standalone unique part is stored in.
	 */
	enum Shape {
		/** A handful of values - every unique index is an inline root. */
		INLINE(0),
		/** Three hundred extra products - every unique index spans several leaf page parts. */
		PAGED(300);

		/**
		 * How many filler products the shape adds to the named entities.
		 */
		private final int fillers;

		Shape(int fillers) {
			this.fillers = fillers;
		}

		/**
		 * Returns the primary key of the last filler product, or one less than the first when there is none.
		 */
		int lastFiller() {
			return FIRST_FILLER + this.fillers - 1;
		}

		/**
		 * Tells whether every standalone unique index of the shape is stored paged.
		 */
		boolean paged() {
			return this.fillers > 0;
		}
	}

	@AfterEach
	void tearDown() {
		this.allocatedPaths.forEach(this::cleanupTestPaths);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(Shape.class)
	@DisplayName("Should re-key raw unique values on upgrade so every equivalent spelling finds and claims them")
	void shouldRekeyRawUniqueValuesOnUpgrade(@Nonnull Shape shape) {
		final TestPaths paths = allocatePaths("raw_" + shape.name());
		final Spellings spellings = new Spellings();
		writeCatalog(
			paths,
			session -> {
				writeFirstProduct(session, spellings);
				session.createNewEntity(PRODUCT, 2)
					.setAttribute(CODE, spellings.raw(CODE, "tea"))
					.setAttribute(PRICE, spellings.raw(PRICE, new BigDecimal("2.5")))
					.setAttribute(URL, Locale.ENGLISH, spellings.raw(URL, "/tea"))
					.upsertVia(session);
				session.createNewEntity(CATEGORY, 1)
					.setAttribute(CODE, spellings.raw(CODE, CATEGORY_CODE_NFC))
					.setAttribute(VALID_FROM, spellings.raw(VALID_FROM, CATEGORY_VALID_FROM))
					.upsertVia(session);
				writeFillers(session, shape, spellings);
			}
		);
		assertEquals(
			STANDALONE_INDEXES, stampLegacyUniqueState(paths, spellings, shape),
			"every standalone unique index must hold raw spellings before the upgrade, or the test proves nothing"
		);

		try (Evita evita = boot(paths)) {
			assertEquals(CatalogState.ALIVE, evita.getCatalogState(CATALOG).orElseThrow());
			assertEverySpellingFinds(evita, shape);
			assertEquivalentSpellingsRefused(evita, shape);
		}
		withOfflineCatalog(paths, Migration_2026_3_UniqueUpgradeRoundTripTest::assertStoredCanonically);
		// the re-keyed catalog loads again as it is, and still answers the same way
		try (Evita evita = boot(paths)) {
			assertEquals(CatalogState.ALIVE, evita.getCatalogState(CATALOG).orElseThrow());
			assertEverySpellingFinds(evita, shape);
		}
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(Shape.class)
	@DisplayName("Should refuse the upgrade over two owners of one value, naming every pair and writing nothing")
	void shouldRefuseUpgradeOverCollidingOwners(@Nonnull Shape shape) {
		final TestPaths paths = allocatePaths("collisions_" + shape.name());
		final Spellings spellings = new Spellings();
		writeCatalog(
			paths,
			session -> {
				writeFirstProduct(session, spellings);
				// each placeholder is stored by 2026.2 as a spelling of `product:1`'s value - see the class javadoc
				session.createNewEntity(PRODUCT, 2)
					.setAttribute(URL, Locale.ENGLISH, spellings.respelled(URL, "/placeholder", URL_NFD))
					.setAttribute(WEIGHT, Locale.ENGLISH, spellings.respelled(WEIGHT, new BigDecimal("9.99"), new BigDecimal("0.126")))
					.setAttribute(
						RELEASED_AT, Locale.ENGLISH,
						spellings.respelled(RELEASED_AT, NOON_UTC.plusDays(3), PRODUCT_RELEASED_AT.withOffsetSameInstant(ZoneOffset.UTC))
					)
					.upsertVia(session);
				session.createNewEntity(CATEGORY, 1)
					.setAttribute(CODE, spellings.respelled(CODE, "placeholder", CHAJ_NFD))
					.setAttribute(PRICE, spellings.respelled(PRICE, new BigDecimal("7.77"), new BigDecimal("1.236")))
					.setAttribute(VALID_FROM, spellings.respelled(VALID_FROM, CATEGORY_VALID_FROM, NOON_UTC))
					.upsertVia(session);
				writeFillers(session, shape, spellings);
			}
		);
		assertEquals(STANDALONE_INDEXES, stampLegacyUniqueState(paths, spellings, shape));
		final Map<String, String> before = catalogFileDigests(paths);

		try (Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
			final Exception failure = assertThrows(Exception.class, evita::waitUntilFullyInitialized);
			assertEquals(CatalogState.CORRUPTED, evita.getCatalogState(CATALOG).orElseThrow());
			final ObsoleteStorageProtocolException refusal = findCause(failure, ObsoleteStorageProtocolException.class);
			final String message = refusal.getMessage();
			assertTrue(message.contains("cannot be upgraded to storage protocol version 7"), message);
			assertTrue(message.contains("6 unique value(s)"), message);
			final List<String> pairs = List.of(
				message.substring(message.indexOf("Collisions: ") + "Collisions: ".length(), message.length() - 1)
					.split(", ")
			);
			assertAll(
				() -> assertEquals(6, pairs.size(), message),
				() -> assertPairNamed(pairs, "catalog attribute `code` (scope LIVE)", "`product` 1", "`category` 1"),
				() -> assertPairNamed(pairs, "catalog attribute `price` (scope LIVE)", "`product` 1", "`category` 1"),
				() -> assertPairNamed(pairs, "catalog attribute `validFrom` (scope LIVE)", "`product` 1", "`category` 1"),
				() -> assertPairNamed(pairs, "attribute `url` of entity `product`", "`product` 1", "`product` 2"),
				() -> assertPairNamed(pairs, "attribute `weight` of entity `product`", "`product` 1", "`product` 2"),
				() -> assertPairNamed(pairs, "attribute `releasedAt` of entity `product`", "`product` 1", "`product` 2"),
				// two spellings of one text must be told apart in the message, or the operator cannot find them
				() -> assertTrue(message.contains("c\\u030Caj"), message),
				() -> assertTrue(message.contains("\\u010Daj"), message)
			);
		}
		assertEquals(before, catalogFileDigests(paths), "a refused upgrade must write nothing");
	}

	/**
	 * Returns the NFC spelling of `text`.
	 */
	@Nonnull
	private static String nfc(@Nonnull String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFC);
	}

	/**
	 * Returns the NFD spelling of `text`.
	 */
	@Nonnull
	private static String nfd(@Nonnull String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFD);
	}

	/**
	 * Writes `product:1`, which holds a raw spelling in every standalone unique index: NFC texts, decimals finer than
	 * the indexed decimal places and instants at non-UTC offsets.
	 */
	private static void writeFirstProduct(@Nonnull EvitaSessionContract session, @Nonnull Spellings spellings) {
		session.createNewEntity(PRODUCT, 1)
			.setAttribute(CODE, spellings.raw(CODE, CHAJ_NFC))
			.setAttribute(PRICE, spellings.raw(PRICE, new BigDecimal("1.235")))
			.setAttribute(VALID_FROM, spellings.raw(VALID_FROM, PRODUCT_VALID_FROM))
			.setAttribute(URL, Locale.ENGLISH, spellings.raw(URL, URL_NFC))
			.setAttribute(WEIGHT, Locale.ENGLISH, spellings.raw(WEIGHT, new BigDecimal("0.125")))
			.setAttribute(RELEASED_AT, Locale.ENGLISH, spellings.raw(RELEASED_AT, PRODUCT_RELEASED_AT))
			.upsertVia(session);
	}

	/**
	 * Writes the filler products of `shape`, each with a raw spelling in every standalone unique index (German values
	 * for the localized ones), so that every index spans several leaf pages.
	 */
	private static void writeFillers(
		@Nonnull EvitaSessionContract session,
		@Nonnull Shape shape,
		@Nonnull Spellings spellings
	) {
		for (int i = FIRST_FILLER; i <= shape.lastFiller(); i++) {
			session.createNewEntity(PRODUCT, i)
				.setAttribute(CODE, spellings.raw(CODE, nfc("ž-" + i)))
				.setAttribute(PRICE, spellings.raw(PRICE, new BigDecimal(i + ".005")))
				.setAttribute(VALID_FROM, spellings.raw(VALID_FROM, FILLER_BASE.plusHours(i).withOffsetSameInstant(PLUS_TWO)))
				.setAttribute(URL, Locale.GERMAN, spellings.raw(URL, nfc("/řeka-" + i)))
				.setAttribute(WEIGHT, Locale.GERMAN, spellings.raw(WEIGHT, new BigDecimal(i + ".125")))
				.setAttribute(
					RELEASED_AT, Locale.GERMAN,
					spellings.raw(RELEASED_AT, FILLER_BASE.plusMinutes(i).withOffsetSameInstant(MINUS_FIVE))
				)
				.upsertVia(session);
		}
	}

	/**
	 * Asserts that every value of the raw-keys catalog is found by each of its equivalent spellings - an NFC or NFD
	 * text, a decimal equal at two decimal places, an instant at any offset - catalog-wide for the catalog attributes
	 * and within `product` (in the value's locale) for the localized ones.
	 */
	private static void assertEverySpellingFinds(@Nonnull Evita evita, @Nonnull Shape shape) {
		final List<Executable> lookups = new ArrayList<>(64);
		for (String code : new String[]{CHAJ_NFC, CHAJ_NFD}) {
			lookups.add(finds(evita, "product:1", null, null, attributeEquals(CODE, code)));
		}
		for (String price : new String[]{"1.235", "1.24", "1.2449"}) {
			lookups.add(finds(evita, "product:1", null, null, attributeEquals(PRICE, new BigDecimal(price))));
		}
		lookups.add(finds(evita, "product:2", null, null, attributeEquals(PRICE, new BigDecimal("2.50"))));
		for (ZoneOffset offset : new ZoneOffset[]{PLUS_TWO, ZoneOffset.UTC, MINUS_FIVE}) {
			lookups.add(finds(evita, "product:1", null, null, attributeEquals(VALID_FROM, NOON_UTC.withOffsetSameInstant(offset))));
			lookups.add(finds(
				evita, "product:1", PRODUCT, Locale.ENGLISH,
				attributeEquals(RELEASED_AT, PRODUCT_RELEASED_AT.withOffsetSameInstant(offset))
			));
		}
		for (String url : new String[]{URL_NFC, URL_NFD}) {
			lookups.add(finds(evita, "product:1", PRODUCT, Locale.ENGLISH, attributeEquals(URL, url)));
		}
		for (String weight : new String[]{"0.125", "0.13"}) {
			lookups.add(finds(evita, "product:1", PRODUCT, Locale.ENGLISH, attributeEquals(WEIGHT, new BigDecimal(weight))));
		}
		lookups.add(finds(evita, "category:1", null, null, attributeEquals(CODE, nfd("kategorie-ř"))));
		lookups.add(finds(
			evita, "category:1", null, null,
			attributeEquals(VALID_FROM, CATEGORY_VALID_FROM.withOffsetSameInstant(ZoneOffset.UTC))
		));
		if (shape.paged()) {
			for (int i : new int[]{FIRST_FILLER, (FIRST_FILLER + shape.lastFiller()) / 2, shape.lastFiller()}) {
				final String expected = "product:" + i;
				lookups.add(finds(evita, expected, null, null, attributeEquals(CODE, nfd("ž-" + i))));
				lookups.add(finds(evita, expected, null, null, attributeEquals(PRICE, new BigDecimal(i + ".01"))));
				lookups.add(finds(evita, expected, null, null, attributeEquals(VALID_FROM, FILLER_BASE.plusHours(i))));
				lookups.add(finds(evita, expected, PRODUCT, Locale.GERMAN, attributeEquals(URL, nfd("/řeka-" + i))));
				lookups.add(finds(evita, expected, PRODUCT, Locale.GERMAN, attributeEquals(WEIGHT, new BigDecimal(i + ".13"))));
				lookups.add(finds(evita, expected, PRODUCT, Locale.GERMAN, attributeEquals(RELEASED_AT, FILLER_BASE.plusMinutes(i))));
			}
		}
		assertAll(lookups);
	}

	/**
	 * Asserts that an equivalent spelling of a stored value is refused for another owner, in every standalone unique
	 * index - a write path that consults the re-keyed tree rather than a query that might be answered elsewhere.
	 */
	private static void assertEquivalentSpellingsRefused(@Nonnull Evita evita, @Nonnull Shape shape) {
		final List<Executable> refusals = new ArrayList<>(12);
		refusals.add(refused(evita, session -> session.createNewEntity(CATEGORY, 2)
			.setAttribute(CODE, CHAJ_NFD).upsertVia(session)));
		refusals.add(refused(evita, session -> session.createNewEntity(CATEGORY, 2)
			.setAttribute(PRICE, new BigDecimal("1.24")).upsertVia(session)));
		refusals.add(refused(evita, session -> session.createNewEntity(CATEGORY, 2)
			.setAttribute(VALID_FROM, NOON_UTC).upsertVia(session)));
		refusals.add(refused(evita, session -> session.createNewEntity(PRODUCT, 1000)
			.setAttribute(URL, Locale.GERMAN, URL_NFD).upsertVia(session)));
		refusals.add(refused(evita, session -> session.createNewEntity(PRODUCT, 1000)
			.setAttribute(WEIGHT, Locale.GERMAN, new BigDecimal("0.13")).upsertVia(session)));
		refusals.add(refused(evita, session -> session.createNewEntity(PRODUCT, 1000)
			.setAttribute(RELEASED_AT, Locale.GERMAN, PRODUCT_RELEASED_AT.withOffsetSameInstant(ZoneOffset.UTC))
			.upsertVia(session)));
		if (shape.paged()) {
			final int middle = (FIRST_FILLER + shape.lastFiller()) / 2;
			refusals.add(refused(evita, session -> session.createNewEntity(CATEGORY, 2)
				.setAttribute(CODE, nfd("ž-" + middle)).upsertVia(session)));
			refusals.add(refused(evita, session -> session.createNewEntity(PRODUCT, 1000)
				.setAttribute(URL, Locale.ENGLISH, nfd("/řeka-" + middle)).upsertVia(session)));
		}
		assertAll(refusals);
	}

	/**
	 * Returns an assertion that `write` is refused with a {@link UniqueValueViolationException}.
	 */
	@Nonnull
	private static Executable refused(@Nonnull Evita evita, @Nonnull Consumer<EvitaSessionContract> write) {
		return () -> assertThrows(UniqueValueViolationException.class, () -> evita.updateCatalog(CATALOG, write::accept));
	}

	/**
	 * Returns an assertion that `filter` (in `locale` when given) finds exactly `expected` within `collection`, or
	 * across the catalog when `collection` is `null`.
	 */
	@Nonnull
	private static Executable finds(
		@Nonnull Evita evita,
		@Nonnull String expected,
		@Nullable String collection,
		@Nullable Locale locale,
		@Nonnull FilterConstraint filter
	) {
		final FilterConstraint constraint = locale == null ? filter : and(filter, entityLocaleEquals(locale));
		return () -> assertEquals(
			List.of(expected),
			evita.queryCatalog(
				CATALOG,
				// a block body with `return` - an expression lambda also matches the `Consumer` overload
				session -> {
					return session.queryListOfEntityReferences(
							collection == null ?
								query(filterBy(constraint)) :
								query(collection(collection), filterBy(constraint))
						)
						.stream()
						.map(Migration_2026_3_UniqueUpgradeRoundTripTest::describe)
						.sorted()
						.toList();
				}
			),
			String.valueOf(filter)
		);
	}

	/**
	 * Renders a reference as `type:pk`.
	 */
	@Nonnull
	private static String describe(@Nonnull EntityReferenceContract reference) {
		return reference.getType() + ":" + reference.getPrimaryKey();
	}

	/**
	 * Asserts that exactly one entry of the refusal's collision list belongs to `structure` and names both owners.
	 */
	private static void assertPairNamed(
		@Nonnull List<String> pairs,
		@Nonnull String structure,
		@Nonnull String firstOwner,
		@Nonnull String secondOwner
	) {
		final List<String> matching = pairs.stream().filter(it -> it.startsWith(structure + ": ")).toList();
		assertEquals(1, matching.size(), () -> structure + " in " + pairs);
		assertTrue(matching.get(0).contains("held by " + firstOwner), matching.get(0));
		assertTrue(matching.get(0).contains("held by " + secondOwner), matching.get(0));
	}

	/**
	 * Returns the first throwable of type `type` in the cause chain of `throwable`, failing the test when there is none.
	 */
	@Nonnull
	private static <T extends Throwable> T findCause(@Nonnull Throwable throwable, @Nonnull Class<T> type) {
		Throwable current = throwable;
		for (int depth = 0; current != null && depth < 16; depth++) {
			if (type.isInstance(current)) {
				return type.cast(current);
			}
			current = current.getCause() == current ? null : current.getCause();
		}
		return fail("No " + type.getSimpleName() + " in the cause chain of " + throwable, throwable);
	}

	/**
	 * Asserts that the upgrade left the catalog at storage protocol 7 and every standalone unique index holding only
	 * the canonical form of its values - the rewrite itself, not merely a load that tolerates raw values.
	 */
	@Nonnull
	private static Set<String> assertStoredCanonically(@Nonnull DefaultCatalogPersistenceService service) {
		final long catalogVersion = service.getLastCatalogVersion();
		final CatalogHeader<LogFileRecordReference, CollectionFileReference> header =
			service.getCatalogHeader(catalogVersion);
		assertEquals(7, header.storageProtocolVersion());
		final List<StoredUniqueIndex> indexes = new ArrayList<>(
			readGlobalUniqueIndexes(catalogVersion, service.getStoragePartPersistenceService(catalogVersion))
		);
		for (CollectionFileReference collection : header.getEntityTypeFileIndexes()) {
			indexes.addAll(
				readOwnerUniqueIndexes(
					catalogVersion,
					service.getOrCreateEntityCollectionPersistenceService(
						catalogVersion, collection.entityType(), collection.entityTypePrimaryKey()
					)
				)
			);
		}
		final Set<String> labels = new HashSet<>();
		for (StoredUniqueIndex index : indexes) {
			labels.add(index.label());
			final Class<? extends Serializable> type = Objects.requireNonNull(ATTRIBUTE_TYPES.get(index.attributeName()));
			for (Serializable[] page : index.pageValues()) {
				for (Serializable value : page) {
					assertEquals(UniqueIndex.toPersistedValue(type, DECIMAL_PLACES, value), value, index.label());
				}
			}
		}
		assertEquals(STANDALONE_INDEXES, labels);
		return labels;
	}

	/**
	 * Turns the catalog written by the current engine into the state 2026.2 would have left on disk: every standalone
	 * unique part, in the catalog file and in every collection file, holds the raw spelling of each value in 2026.2's
	 * order across its original leaf boundaries, and the catalog header carries storage protocol 6. The changes are
	 * published by a new bootstrap record, exactly as a flush would publish them.
	 *
	 * @return the labels of the indexes that now hold raw spellings
	 */
	@Nonnull
	private Set<String> stampLegacyUniqueState(@Nonnull TestPaths paths, @Nonnull Spellings spellings, @Nonnull Shape shape) {
		final Path catalogFolder = EvitaTestSupport.catalogDirectory(paths.storage(), CATALOG);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		final int catalogFileIndex;
		try (
			Stream<CatalogBootstrap> bootstrapRecords = DefaultCatalogPersistenceService.getCatalogBootstrapRecordStream(
				CATALOG, catalogFolder,
				new StorageSettings(configuration.storage(), configuration.transaction()).modifyForBootstrapFile()
			)
		) {
			catalogFileIndex = bootstrapRecords.reduce((previous, next) -> next).orElseThrow().catalogFileIndex();
		}
		return withOfflineCatalog(
			paths,
			service -> {
				final long catalogVersion = service.getLastCatalogVersion();
				final CatalogOffsetIndexStoragePartPersistenceService catalogParts =
					service.getStoragePartPersistenceService(catalogVersion);
				final CatalogHeader<LogFileRecordReference, CollectionFileReference> header =
					catalogParts.getCatalogHeader(catalogVersion);
				final Set<String> respelled = new HashSet<>();
				for (StoredUniqueIndex index : readGlobalUniqueIndexes(catalogVersion, catalogParts)) {
					respellIntoLegacyOrder(index, spellings, shape, respelled);
				}
				final Map<String, CollectionFileReference> collectionFileIndex = new HashMap<>(header.collectionFileIndex());
				for (CollectionFileReference collection : header.getEntityTypeFileIndexes()) {
					final DefaultEntityCollectionPersistenceService collectionService =
						service.getOrCreateEntityCollectionPersistenceService(
							catalogVersion, collection.entityType(), collection.entityTypePrimaryKey()
						);
					boolean collectionChanged = false;
					for (StoredUniqueIndex index : readOwnerUniqueIndexes(catalogVersion, collectionService)) {
						collectionChanged |= respellIntoLegacyOrder(index, spellings, shape, respelled);
					}
					if (collectionChanged) {
						// the same flush-and-re-address sequence the migration itself performs on a rewritten collection
						final OffsetIndexDescriptor descriptor = collectionService.flush(
							catalogVersion, new NoChangeHeaderInfoSupplier(collectionService.getEntityCollectionHeader())
						);
						catalogParts.putStoragePart(catalogVersion, collectionService.getEntityCollectionHeader());
						collectionFileIndex.put(
							collection.entityType(),
							new CollectionFileReference(
								collection.entityType(), collection.entityTypePrimaryKey(), collection.fileIndex(),
								descriptor.fileLocation()
							)
						);
					}
				}
				catalogParts.writeCatalogHeader(
					6, catalogVersion, catalogFolder, header.walFileReference(), collectionFileIndex,
					header.catalogId(), header.catalogName(), header.catalogState(),
					header.lastEntityCollectionPrimaryKey()
				);
				service.recordBootstrap(catalogVersion, CATALOG, catalogFileIndex, null);
				return respelled;
			}
		);
	}

	/**
	 * Replaces every value of `index` the spellings know by the spelling 2026.2 kept for it, re-sorts the entries into
	 * 2026.2's order and writes them back with the original number of entries per page.
	 *
	 * @param respelled receives the label of the index when any value changed
	 * @return `true` when any value changed
	 */
	private static boolean respellIntoLegacyOrder(
		@Nonnull StoredUniqueIndex index,
		@Nonnull Spellings spellings,
		@Nonnull Shape shape,
		@Nonnull Set<String> respelled
	) {
		final Map<Serializable, Serializable> spelling = spellings.of(index.attributeName());
		final List<Serializable> values = new ArrayList<>();
		final List<Long> owners = new ArrayList<>();
		for (int page = 0; page < index.pageValues().size(); page++) {
			values.addAll(Arrays.asList(index.pageValues().get(page)));
			for (long owner : index.pageOwners().get(page)) {
				owners.add(owner);
			}
		}
		final List<Integer> order = new ArrayList<>(values.size());
		boolean changed = false;
		for (int i = 0; i < values.size(); i++) {
			final Serializable legacySpelling = spelling.getOrDefault(values.get(i), values.get(i));
			changed |= !legacySpelling.equals(values.get(i));
			values.set(i, legacySpelling);
			order.add(i);
		}
		if (!changed) {
			return false;
		}
		assertEquals(shape.paged(), index.paged(), () -> index.label() + " is not stored in the expected shape");
		order.sort((first, second) -> LEGACY_ORDER.compare(values.get(first), values.get(second)));
		int position = 0;
		for (int page = 0; page < index.pageValues().size(); page++) {
			final int pageSize = index.pageValues().get(page).length;
			final Serializable[] pageValues = new Serializable[pageSize];
			final long[] pageOwners = new long[pageSize];
			for (int i = 0; i < pageSize; i++, position++) {
				pageValues[i] = values.get(order.get(position));
				pageOwners[i] = owners.get(order.get(position));
			}
			index.writer().write(page, pageValues, pageOwners);
		}
		respelled.add(index.label());
		return true;
	}

	/**
	 * Reads every global unique index of the catalog file - every shared unique attribute of every scope.
	 */
	@Nonnull
	private static List<StoredUniqueIndex> readGlobalUniqueIndexes(
		long catalogVersion,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService service
	) {
		final List<StoredUniqueIndex> indexes = new ArrayList<>();
		for (Scope scope : Scope.values()) {
			final CatalogIndexStoragePart catalogIndex = service.getStoragePart(
				catalogVersion, CatalogIndexStoragePart.getStoragePartPKForScope(scope), CatalogIndexStoragePart.class
			);
			if (catalogIndex == null) {
				continue;
			}
			for (AttributeKey attributeKey : catalogIndex.getSharedAttributeUniqueIndexes()) {
				final long rootPk = GlobalUniqueIndexStoragePart.computeUniquePartId(
					scope, attributeKey, service.getReadOnlyKeyCompressor()
				);
				final GlobalUniqueIndexStoragePart root = Objects.requireNonNull(
					service.getStoragePart(catalogVersion, rootPk, GlobalUniqueIndexStoragePart.class)
				);
				final String label = "catalog " + attributeKey.attributeName();
				if (root.isPaged()) {
					final int streamId = service.getReadOnlyKeyCompressor()
						.getId(new GlobalUniqueLeafStreamKey(scope, attributeKey));
					final int[] pageSequences = root.getLeafPageSequences();
					final List<Serializable[]> pageValues = new ArrayList<>(pageSequences.length);
					final List<long[]> pageOwners = new ArrayList<>(pageSequences.length);
					for (int pageSequence : pageSequences) {
						final GlobalUniqueIndexLeafPagePart page = Objects.requireNonNull(
							service.getStoragePart(
								catalogVersion,
								GlobalUniqueIndexLeafPagePart.computeUniquePartId(streamId, pageSequence),
								GlobalUniqueIndexLeafPagePart.class
							)
						);
						pageValues.add(page.getValues());
						pageOwners.add(page.getPayloads());
					}
					indexes.add(
						new StoredUniqueIndex(
							label, attributeKey.attributeName(), true, pageValues, pageOwners,
							(page, values, owners) -> service.putStoragePart(
								catalogVersion,
								new GlobalUniqueIndexLeafPagePart(
									streamId, pageSequences[page], values, owners,
									GlobalUniqueIndexLeafPagePart.computeUniquePartId(streamId, pageSequences[page])
								)
							)
						)
					);
				} else {
					indexes.add(
						new StoredUniqueIndex(
							label, attributeKey.attributeName(), false,
							List.<Serializable[]>of(Objects.requireNonNull(root.getValues())),
							List.of(Objects.requireNonNull(root.getPayloads())),
							(page, values, owners) -> service.putStoragePart(
								catalogVersion,
								new GlobalUniqueIndexStoragePart(
									scope, attributeKey, root.getType(), values, owners, root.getLocaleIndex(), rootPk
								)
							)
						)
					);
				}
			}
		}
		return indexes;
	}

	/**
	 * Reads every standalone owner unique index of one collection, in any of its entity indexes. Folded unique parts
	 * (views over the filter index) carry no values of their own and are skipped.
	 */
	@Nonnull
	private static List<StoredUniqueIndex> readOwnerUniqueIndexes(
		long catalogVersion,
		@Nonnull DefaultEntityCollectionPersistenceService collection
	) {
		final OffsetIndexStoragePartPersistenceService service = collection.getStoragePartPersistenceService();
		final EntityCollectionFileHeader header = collection.getEntityCollectionHeader();
		final Set<Integer> indexIds = new HashSet<>(header.usedEntityIndexPrimaryKeys());
		if (header.globalEntityIndexPrimaryKey() != null) {
			indexIds.add(header.globalEntityIndexPrimaryKey());
		}
		final List<StoredUniqueIndex> indexes = new ArrayList<>();
		for (Integer indexId : indexIds) {
			final EntityIndexStoragePart entityIndex = Objects.requireNonNull(
				service.getStoragePart(catalogVersion, indexId, EntityIndexStoragePart.class)
			);
			for (AttributeIndexStorageKey storageKey : entityIndex.getAttributeIndexes()) {
				if (storageKey.indexType() != AttributeIndexType.UNIQUE) {
					continue;
				}
				final long rootPk = AttributeIndexStoragePart.computeUniquePartId(
					indexId, AttributeIndexType.UNIQUE, storageKey.attribute(), service.getReadOnlyKeyCompressor()
				);
				final UniqueIndexStoragePart root = service.getStoragePart(
					catalogVersion, rootPk, UniqueIndexStoragePart.class
				);
				if (root == null || (!root.isPaged() && root.getValues() == null)) {
					continue;
				}
				final String attributeName = root.getAttributeIndexKey().attributeName();
				final String label = header.entityType() + " " + attributeName;
				if (root.isPaged()) {
					final int streamId = service.getReadOnlyKeyCompressor().getId(
						new LeafStreamKey(
							indexId, new AttributeKeyWithIndexType(root.getAttributeIndexKey(), AttributeIndexType.UNIQUE)
						)
					);
					final int[] pageSequences = root.getLeafPageSequences();
					final List<Serializable[]> pageValues = new ArrayList<>(pageSequences.length);
					final List<long[]> pageOwners = new ArrayList<>(pageSequences.length);
					for (int pageSequence : pageSequences) {
						final UniqueIndexLeafPagePart page = Objects.requireNonNull(
							service.getStoragePart(
								catalogVersion,
								AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence),
								UniqueIndexLeafPagePart.class
							)
						);
						pageValues.add(page.getValues());
						pageOwners.add(Arrays.stream(page.getRecordIds()).asLongStream().toArray());
					}
					indexes.add(
						new StoredUniqueIndex(
							label, attributeName, true, pageValues, pageOwners,
							(page, values, owners) -> service.putStoragePart(
								catalogVersion,
								new UniqueIndexLeafPagePart(
									streamId, pageSequences[page], values, toRecordIds(owners),
									AbstractLeafPagePart.computeUniquePartId(streamId, pageSequences[page])
								)
							)
						)
					);
				} else {
					indexes.add(
						new StoredUniqueIndex(
							label, attributeName, false,
							List.<Serializable[]>of(root.getValues()),
							List.of(Arrays.stream(Objects.requireNonNull(root.getRecordIds())).asLongStream().toArray()),
							(page, values, owners) -> service.putStoragePart(
								catalogVersion,
								new UniqueIndexStoragePart(
									indexId, root.getAttributeIndexKey(), root.getType(), values, toRecordIds(owners),
									rootPk
								)
							)
						)
					);
				}
			}
		}
		return indexes;
	}

	/**
	 * Narrows owners read as `long` back to the record ids an owner unique index stores.
	 */
	@Nonnull
	private static int[] toRecordIds(@Nonnull long[] owners) {
		return Arrays.stream(owners).mapToInt(Math::toIntExact).toArray();
	}

	/**
	 * Opens the catalog with the engine stopped, through the same persistence service the engine loads it with, runs
	 * `work` on it and closes it again, together with the scheduler and export service it needs.
	 */
	@Nonnull
	private <T> T withOfflineCatalog(
		@Nonnull TestPaths paths,
		@Nonnull Function<DefaultCatalogPersistenceService, T> work
	) {
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		final CatalogFolderId folderId = new CatalogFolderId(
			EvitaTestSupport.catalogDirectory(paths.storage(), CATALOG).getFileName().toString()
		);
		final Scheduler scheduler = new Scheduler(new ScheduledThreadPoolExecutor(1));
		try (
			ExportFileService exportService = new ExportFileService(configuration.export(), scheduler);
			DefaultCatalogPersistenceService service = new DefaultCatalogPersistenceService(
				new UnusableCatalog(
					CATALOG, CatalogState.ALIVE, folderId, paths.storage(),
					CatalogFolderOperations.unsupported("The test opens the catalog only to read and stamp its parts."),
					(catalogName, folder, root) -> new IllegalStateException("The test never queries the catalog.")
				),
				CATALOG, folderId, configuration.storage(), configuration.transaction(), scheduler, exportService
			)
		) {
			return work.apply(service);
		} finally {
			scheduler.shutdownNow();
			try {
				assertTrue(scheduler.awaitTermination(30, TimeUnit.SECONDS), "the offline scheduler did not stop");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Defines the catalog and its schema in a fresh engine, writes the data `writer` produces in warm-up, takes the
	 * catalog live and shuts the engine down.
	 */
	private void writeCatalog(@Nonnull TestPaths paths, @Nonnull Consumer<EvitaSessionContract> writer) {
		try (Evita evita = boot(paths)) {
			evita.defineCatalog(CATALOG);
			try (EvitaSessionContract session = evita.createReadWriteSession(CATALOG)) {
				session.getCatalogSchema().openForWrite()
					.withAttribute(CODE, String.class, attribute -> attribute.uniqueGlobally().nullable())
					.withAttribute(
						PRICE, BigDecimal.class,
						attribute -> attribute.uniqueGlobally().nullable().indexDecimalPlaces(DECIMAL_PLACES)
					)
					.withAttribute(VALID_FROM, OffsetDateTime.class, attribute -> attribute.uniqueGlobally().nullable())
					.updateVia(session);
				session.defineEntitySchema(PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH, Locale.GERMAN)
					.withGlobalAttribute(CODE)
					.withGlobalAttribute(PRICE)
					.withGlobalAttribute(VALID_FROM)
					.withAttribute(URL, String.class, attribute -> attribute.localized().unique().nullable())
					.withAttribute(
						WEIGHT, BigDecimal.class,
						attribute -> attribute.localized().unique().nullable().indexDecimalPlaces(DECIMAL_PLACES)
					)
					.withAttribute(RELEASED_AT, OffsetDateTime.class, attribute -> attribute.localized().unique().nullable())
					.updateVia(session);
				session.defineEntitySchema(CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(CODE)
					.withGlobalAttribute(PRICE)
					.withGlobalAttribute(VALID_FROM)
					.updateVia(session);
				writer.accept(session);
				session.goLiveAndClose();
			}
		}
	}

	/**
	 * Starts the engine over `paths` and waits until every catalog has finished loading.
	 */
	@Nonnull
	private Evita boot(@Nonnull TestPaths paths) {
		final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build());
		evita.waitUntilFullyInitialized();
		return evita;
	}

	/**
	 * Allocates fresh test paths, removed after the test.
	 */
	@Nonnull
	private TestPaths allocatePaths(@Nonnull String label) {
		final TestPaths paths = createTestPaths("Migration_2026_3_UniqueUpgrade_" + label);
		this.allocatedPaths.add(paths);
		return paths;
	}

	/**
	 * Returns the SHA-256 digest of every file of the catalog folder, by file name.
	 */
	@Nonnull
	private static Map<String, String> catalogFileDigests(@Nonnull TestPaths paths) {
		final Map<String, String> digests = new TreeMap<>();
		try (Stream<Path> files = Files.list(EvitaTestSupport.catalogDirectory(paths.storage(), CATALOG))) {
			for (Path file : files.toList()) {
				digests.put(
					file.getFileName().toString(),
					HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
				);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		return digests;
	}

	/**
	 * Writes the values of one page of a stored unique index back to storage.
	 */
	@FunctionalInterface
	private interface PageWriter {

		/**
		 * Stores `values` with their `owners` as page `page` (the root, for an inline index).
		 *
		 * @param page   the position of the page in the index's leaf order
		 * @param values the values of the page, in the order they are to be stored
		 * @param owners the owner of each value - a record id or a packed entity tuple
		 */
		void write(int page, @Nonnull Serializable[] values, @Nonnull long[] owners);

	}

	/**
	 * One standalone unique index as it is stored.
	 *
	 * @param label         names the index: `catalog <attribute>` or `<entity type> <attribute>`
	 * @param attributeName the attribute the index is for
	 * @param paged         whether the index is stored as leaf page parts rather than an inline root
	 * @param pageValues    the values of every page in leaf order; a single page for an inline index
	 * @param pageOwners    the owners of every page, positionally aligned with `pageValues`
	 * @param writer        writes a page back
	 */
	private record StoredUniqueIndex(
		@Nonnull String label,
		@Nonnull String attributeName,
		boolean paged,
		@Nonnull List<Serializable[]> pageValues,
		@Nonnull List<long[]> pageOwners,
		@Nonnull PageWriter writer
	) {
	}

	/**
	 * Remembers, per attribute, which spelling a 2026.2 unique index kept for each value the current engine persists -
	 * keyed by the canonical value the current engine stores ({@link UniqueIndex#toPersistedValue}).
	 */
	private static final class Spellings {
		private final Map<String, Map<Serializable, Serializable>> byAttribute = new HashMap<>();

		/**
		 * Records that `value` is written and that 2026.2 kept it exactly as written.
		 *
		 * @return `value`
		 */
		@Nonnull
		<T extends Serializable> T raw(@Nonnull String attributeName, @Nonnull T value) {
			return respelled(attributeName, value, value);
		}

		/**
		 * Records that `written` is written while 2026.2 kept `stored` in its place.
		 *
		 * @return `written`
		 */
		@Nonnull
		<T extends Serializable> T respelled(@Nonnull String attributeName, @Nonnull T written, @Nonnull Serializable stored) {
			final Class<? extends Serializable> type = Objects.requireNonNull(ATTRIBUTE_TYPES.get(attributeName));
			final Serializable previous = this.byAttribute
				.computeIfAbsent(attributeName, name -> new HashMap<>())
				.put(UniqueIndex.toPersistedValue(type, DECIMAL_PLACES, written), stored);
			assertTrue(previous == null, () -> "two written values of `" + attributeName + "` share one key");
			return written;
		}

		/**
		 * Returns the spellings of one attribute.
		 */
		@Nonnull
		Map<Serializable, Serializable> of(@Nonnull String attributeName) {
			return this.byAttribute.getOrDefault(attributeName, Map.of());
		}
	}

}
