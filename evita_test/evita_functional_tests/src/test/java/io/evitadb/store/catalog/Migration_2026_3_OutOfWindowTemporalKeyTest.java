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
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.exception.ObsoleteStorageProtocolException;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.attribute.LegacyOwnerSortIndexCorrection;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.core.Evita;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex.AttributeCardinalityKey;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordPrimitive;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.EntitySchemaContext;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart.EntityAttributesSetKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeCardinalityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.CatalogIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.EntitySchemaStoragePart;
import io.evitadb.store.catalog.Migration_2025_6.NoChangeHeaderInfoSupplier;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.store.catalog.OfflineCatalogImages.lastBootstrap;
import static io.evitadb.store.catalog.OfflineCatalogImages.stamp;
import static io.evitadb.store.catalog.OfflineCatalogImages.withOfflineCatalog;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drives the REAL v6→v7 upgrade over a catalog in which evitaDB 2026.2 stored a temporal value outside the window
 * this version represents - the epoch milliseconds a `long` holds, roughly ±292 million years around 1970.
 *
 * # Why 2026.2 holds such values
 *
 * 2026.2 accepted every `OffsetDateTime` and `LocalDateTime`, the JDK sentinels `MIN` / `MAX` included, and its gRPC
 * converter decoded every moment of year 9999 (year 1) as `OffsetDateTime.MAX` (`MIN`) - a C# `DateTime.MaxValue` or
 * any "valid until 9999-12-31" therefore reached storage as the sentinel. It stored the value without a range check in
 * every structure: a filter index keyed it by its nanosecond-exact `Instant` (`LocalDateTime` anchored at UTC), the
 * standalone unique indexes, the owner sort indexes and the cardinality counters kept the value as written, and so did
 * the entity body.
 *
 * This version clamps such a value on the write and the query path (`EvitaDataTypes#toSupportedType`), but keys a
 * temporal index by `Instant#toEpochMilli`, which throws for an instant outside the window. A value already on disk
 * passes neither clamp: it reaches the index key through `FilterIndex#getNormalizer`, the unique key through
 * `UniqueIndex#toPersistedValue`, and later writes through the entity body.
 *
 * # How the protocol-6 catalog is built
 *
 * No binary fixture. The catalog is written by the current engine with an in-window **placeholder** where the
 * sentinel belongs - the latest (or earliest) value of the attribute, so the sentinel takes the very position the
 * placeholder holds in every ordered structure. With the engine stopped, every filter, unique, cardinality, body and
 * representative-key part holding the placeholder is rewritten, through the current persistence services, to hold
 * what 2026.2 stored for the sentinel, and the result is stamped with storage protocol 6
 * ({@link OfflineCatalogImages#stamp}); the next start runs the migration.
 *
 * Departures from a release-written catalog, none of which the load path distinguishes:
 *
 * - the owner sort indexes, of an attribute and of a compound, are not rewritten from the current ones: the sort cases
 *   replace them with the parts {@link LegacyOwnerSortImages} builds, a replica of the release's own writer;
 * - the filter parts are written in the current layout, which appends a value id section the 2026.2 layout lacks; the
 *   2026.2 reader hands back the same buckets with no value ids and flags second-granularity range thresholds, which
 *   only a `DateTimeRange` attribute reads - none is involved here;
 * - the cardinality and unique parts are written in the current layout too, carrying the raw values 2026.2 kept;
 * - only the bodies holding the placeholder - entity and reference attributes alike - are rewritten, and they are
 *   written through Kryo with the 2026.2 `serialVersionUID` ({@link ReleasedAttributeValueImages}); every other body
 *   keeps the current one, which the version dispatch tells apart value by value.
 *
 * The cases with an in-window value of the 2026.2 shape - nanosecond-exact, stored unclamped - are the controls: they
 * run the same image through the same steps and must load, so a failure of the out-of-window cases is the value and
 * not the image.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Migration_2026_3 — temporal values outside the supported window that 2026.2 stored (v6→v7)")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(DATA_TYPE)
@SuppressWarnings("removal") // the migration is @Deprecated(forRemoval); testing it is the point
class Migration_2026_3_OutOfWindowTemporalKeyTest implements EvitaTestSupport {
	private static final String CATALOG = "outOfWindowTemporal";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	/** Product attribute, filterable. */
	private static final String ATTRIBUTE_VALID_UNTIL = "validUntil";
	/** Product attribute, localized and unique across locales - a standalone owner unique index. */
	private static final String ATTRIBUTE_RELEASED_AT = "releasedAt";
	/** Catalog attribute, unique globally - a global unique index. */
	private static final String ATTRIBUTE_VALID_FROM = "validFrom";
	/** Product attribute, sortable only - an owner sort index. */
	private static final String ATTRIBUTE_PUBLISHED_AT = "publishedAt";
	/** Attribute of the product's reference to a brand, filterable. */
	private static final String ATTRIBUTE_SINCE = "since";
	/** A second attribute of the product's reference to a brand, filterable. */
	private static final String ATTRIBUTE_UNTIL = "until";
	/** The product's reference to a brand. */
	private static final String REFERENCE_BRAND = "brand";
	/** Catalog attribute of the rank of a product - an element of {@link #COMPOUND_RANK_PUBLISHED_AT}. */
	private static final String ATTRIBUTE_RANK = "rank";
	/** The compound of the rank and the moment of a product, both ascending - an owner sort index. */
	private static final String COMPOUND_RANK_PUBLISHED_AT = "rankThenPublishedAt";
	/** Stands in for a second sentinel at the end of the timeline, earlier than {@link #LATEST_PLACEHOLDER}. */
	private static final OffsetDateTime SECOND_LATEST_PLACEHOLDER =
		OffsetDateTime.of(2998, 12, 31, 0, 0, 0, 0, ZoneOffset.UTC);
	/** What 2026.2 stored for it: the end of the `LocalDateTime` calendar at UTC, earlier than `OffsetDateTime.MAX`. */
	private static final OffsetDateTime SECOND_LATEST_RAW = OffsetDateTime.of(LocalDateTime.MAX, ZoneOffset.UTC);
	/** The storage protocol of a catalog 2026.2 wrote - the one the upgrade starts from. */
	private static final int RELEASED_PROTOCOL = 6;
	/** The product holding the sentinel. */
	private static final int SENTINEL_OWNER = 1;
	/** Ordinary values are hours after this moment. */
	private static final OffsetDateTime FIRST_MOMENT = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	/** Stands in for a sentinel at the end of the timeline: later than every ordinary value. */
	private static final OffsetDateTime LATEST_PLACEHOLDER =
		OffsetDateTime.of(2999, 12, 31, 0, 0, 0, 0, ZoneOffset.UTC);
	/** Stands in for a sentinel at the start of the timeline: earlier than every ordinary value. */
	private static final OffsetDateTime EARLIEST_PLACEHOLDER =
		OffsetDateTime.of(1000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

	private final List<TestPaths> allocatedPaths = new ArrayList<>(2);
	@Nullable private Evita evita;

	/**
	 * A temporal value 2026.2 stored as it was written.
	 */
	enum Sentinel {
		/** `OffsetDateTime.MAX` - what 2026.2's gRPC converter made of every moment of year 9999. */
		OFFSET_DATE_TIME_MAX(OffsetDateTime.MAX, true),
		/** `OffsetDateTime.MIN` - what 2026.2's gRPC converter made of every moment of year 1. */
		OFFSET_DATE_TIME_MIN(OffsetDateTime.MIN, false),
		/** `LocalDateTime.MAX`. */
		LOCAL_DATE_TIME_MAX(LocalDateTime.MAX, true),
		/** `LocalDateTime.MIN`. */
		LOCAL_DATE_TIME_MIN(LocalDateTime.MIN, false),
		/** The control: the last nanosecond of year 9999, inside the window and below millisecond precision. */
		IN_WINDOW_OFFSET_DATE_TIME(OffsetDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_999, ZoneOffset.UTC), true),
		/** The control of the `LocalDateTime` cases. */
		IN_WINDOW_LOCAL_DATE_TIME(LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_999), true);

		/**
		 * The value as 2026.2 stored it.
		 */
		@Nonnull private final Serializable raw;
		/**
		 * TRUE when the value is later than every ordinary value, FALSE when earlier.
		 */
		private final boolean latest;

		Sentinel(@Nonnull Serializable raw, boolean latest) {
			this.raw = raw;
			this.latest = latest;
		}

		/**
		 * Returns the declared type of an attribute holding the value.
		 */
		@Nonnull
		Class<? extends Serializable> type() {
			return this.raw instanceof OffsetDateTime ? OffsetDateTime.class : LocalDateTime.class;
		}

		/**
		 * Returns the declared type of an attribute holding an array of the value.
		 */
		@Nonnull
		Class<? extends Serializable> arrayType() {
			return this.raw instanceof OffsetDateTime ? OffsetDateTime[].class : LocalDateTime[].class;
		}

		/**
		 * Returns the value as 2026.2 stored it.
		 */
		@Nonnull
		Serializable raw() {
			return this.raw;
		}

		/**
		 * Returns the value of `owner` as 2026.2 stored it: the sentinel for {@link #SENTINEL_OWNER}, an ordinary value
		 * otherwise.
		 */
		@Nonnull
		Serializable rawValueOf(int owner) {
			return owner == SENTINEL_OWNER ? this.raw : ordinaryValueOf(owner);
		}

		/**
		 * Returns the value the current engine writes for `owner`: the placeholder for {@link #SENTINEL_OWNER}, an
		 * ordinary value otherwise.
		 */
		@Nonnull
		Serializable writtenValueOf(int owner) {
			return owner == SENTINEL_OWNER ?
				ofType(this.latest ? LATEST_PLACEHOLDER : EARLIEST_PLACEHOLDER) :
				ordinaryValueOf(owner);
		}

		/**
		 * Returns how the products are ordered by an ascending sort of the value, the sentinel's owner among them.
		 */
		@Nonnull
		List<Integer> ascendingOrderOf(int owners) {
			final List<Integer> order = new ArrayList<>(owners);
			for (int owner = SENTINEL_OWNER + 1; owner <= owners; owner++) {
				order.add(owner);
			}
			order.add(this.latest ? order.size() : 0, SENTINEL_OWNER);
			return order;
		}

		/**
		 * Returns a value of the declared type that lies `owner` hours after {@link #FIRST_MOMENT}.
		 */
		@Nonnull
		Serializable ordinaryValueOf(int owner) {
			return ofType(FIRST_MOMENT.plusHours(owner));
		}

		/**
		 * Returns `moment` in the declared type - a `LocalDateTime` as its wall clock at UTC.
		 */
		@Nonnull
		private Serializable ofType(@Nonnull OffsetDateTime moment) {
			return this.raw instanceof OffsetDateTime ? moment : moment.toLocalDateTime();
		}
	}

	/**
	 * How many products a case writes, which decides whether the filter index of their values is stored inline or as
	 * leaf pages.
	 */
	enum Shape {
		/** A handful of values - an inline root. */
		INLINE(3),
		/** More distinct values than one leaf holds - a paged root with leaf page parts. */
		PAGED(300);

		/**
		 * How many products the shape writes.
		 */
		private final int owners;

		Shape(int owners) {
			this.owners = owners;
		}

		/**
		 * Returns how many products the shape writes.
		 */
		int owners() {
			return this.owners;
		}
	}

	/**
	 * How the reference to a brand is indexed when the catalog is upgraded.
	 */
	enum ReferenceIndexing {
		/** For filtering and partitioning - every partition stays an index object, the upgrade does not read it. */
		PARTITIONED,
		/** For filtering only - the upgrade reads every single-owner partition to convert it into a registry row. */
		CONVERTED
	}

	/**
	 * A temporal type this version truncates to whole milliseconds while 2026.2 kept it as written, so two values of it
	 * that 2026.2 held apart below the millisecond are one value here.
	 */
	enum SubMillisecondTwins {
		/** Two `OffsetDateTime` values of one millisecond. */
		OFFSET_DATE_TIME(OffsetDateTime.class),
		/** Two `LocalDateTime` values of one millisecond. */
		LOCAL_DATE_TIME(LocalDateTime.class),
		/** Two `LocalTime` values of one millisecond - a type with no window to leave, truncated all the same. */
		LOCAL_TIME(LocalTime.class);

		/**
		 * The declared type of the attribute holding the values.
		 */
		@Nonnull private final Class<? extends Serializable> type;

		SubMillisecondTwins(@Nonnull Class<? extends Serializable> type) {
			this.type = type;
		}

		/**
		 * Returns the declared type of the attribute holding the values.
		 */
		@Nonnull
		Class<? extends Serializable> type() {
			return this.type;
		}

		/**
		 * Returns the value the current engine writes for product `owner` - whole hours apart, because the engine
		 * truncates every value it is given to whole milliseconds, so only {@link #stampSubMillisecondTwins} can
		 * store the two twins.
		 */
		@Nonnull
		Serializable placeholderOf(int owner) {
			return inType(FIRST_MOMENT.plusHours(owner));
		}

		/**
		 * Returns the value 2026.2 stored for product `owner`: 100 microseconds after {@link #FIRST_MOMENT} for product
		 * 1, 200 for product 2 - two keys in 2026.2 and one millisecond in this version.
		 */
		@Nonnull
		Serializable twinOf(int owner) {
			return inType(FIRST_MOMENT.plusNanos(100_000L * owner));
		}

		/**
		 * Returns the moment as a value of {@link #type}.
		 */
		@Nonnull
		private Serializable inType(@Nonnull OffsetDateTime moment) {
			return switch (this) {
				case OFFSET_DATE_TIME -> moment;
				case LOCAL_DATE_TIME -> moment.toLocalDateTime();
				case LOCAL_TIME -> moment.toLocalTime();
			};
		}
	}

	/**
	 * Returns the state of the catalog in the engine whose start has failed.
	 */
	@Nonnull
	private CatalogState stateOfFailedStart() {
		return Objects.requireNonNull(this.evita).getCatalogState(CATALOG).orElseThrow();
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		this.evita = null;
		this.allocatedPaths.forEach(this::cleanupTestPaths);
	}

	/**
	 * A temporal entity attribute keyed by the shared filter value tree.
	 */
	@Nested
	@DisplayName("A filterable entity attribute")
	class FilterIndexOfAnEntityAttribute {

		@ParameterizedTest(name = "{0}")
		@EnumSource(Sentinel.class)
		@DisplayName("loads an inline filter index holding the value, finds the owner by it and removes the owner")
		void shouldLoadInlineFilterIndexWhenUpgradedWithValue(@Nonnull Sentinel sentinel) {
			assertFilterIndexUpgrades(sentinel, Shape.INLINE);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(Sentinel.class)
		@DisplayName("loads a paged filter index holding the value, finds the owner by it and removes the owner")
		void shouldLoadPagedFilterIndexWhenUpgradedWithValue(@Nonnull Sentinel sentinel) {
			assertFilterIndexUpgrades(sentinel, Shape.PAGED);
		}

		/**
		 * Writes `shape` products with a filterable attribute, stores the sentinel 2026.2's way, upgrades the catalog
		 * and asserts it is usable: the sentinel's owner is found by the sentinel - which the query path clamps - an
		 * ordinary owner by its value, and the sentinel's owner can be removed.
		 */
		private void assertFilterIndexUpgrades(@Nonnull Sentinel sentinel, @Nonnull Shape shape) {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("filter_" + shape + "_" + sentinel));
			writeCatalog(
				configuration,
				session -> {
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(ATTRIBUTE_VALID_UNTIL, sentinel.type(), thatIs -> thatIs.filterable().nullable())
						.updateVia(session);
					for (int owner = 1; owner <= shape.owners(); owner++) {
						session.createNewEntity(ENTITY_PRODUCT, owner)
							.setAttribute(ATTRIBUTE_VALID_UNTIL, sentinel.writtenValueOf(owner))
							.upsertVia(session);
					}
				}
			);
			final String layout = shape == Shape.PAGED ? "paged" : "inline";
			assertEquals(
				Set.of("FILTER validUntil in GLOBAL (" + layout + ")", "BODY validUntil"),
				stampReleasedState(configuration, Set.of(ATTRIBUTE_VALID_UNTIL), sentinel, true),
				"the image must hold the value where 2026.2 stored it, or the case proves nothing"
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);

			final Evita upgraded = start(configuration);
			assertUpgraded(upgraded);
			assertEquals(
				List.of(SENTINEL_OWNER),
				productsMatching(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, sentinel.raw()))
			);
			assertEquals(
				List.of(2),
				productsMatching(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, sentinel.ordinaryValueOf(2)))
			);
			assertOwnerRemovable(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, sentinel.raw()));
		}
	}

	/**
	 * A temporal attribute held by a standalone unique index - one the upgrade re-keys.
	 */
	@Nested
	@DisplayName("A unique attribute with a standalone unique index")
	class StandaloneUniqueIndex {

		@ParameterizedTest(name = "{0}")
		@EnumSource(Sentinel.class)
		@DisplayName("re-keys an owner unique index holding the value, finds the owner by it and removes the owner")
		void shouldRekeyOwnerUniqueIndexWhenUpgradedWithValue(@Nonnull Sentinel sentinel) {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("ownerUnique_" + sentinel));
			writeCatalog(
				configuration,
				session -> {
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withLocale(Locale.ENGLISH)
						.withAttribute(
							ATTRIBUTE_RELEASED_AT, sentinel.type(),
							thatIs -> thatIs.localized().unique().nullable()
						)
						.updateVia(session);
					for (int owner = 1; owner <= Shape.INLINE.owners(); owner++) {
						session.createNewEntity(ENTITY_PRODUCT, owner)
							.setAttribute(ATTRIBUTE_RELEASED_AT, Locale.ENGLISH, sentinel.writtenValueOf(owner))
							.upsertVia(session);
					}
				}
			);
			final Set<String> stamped =
				stampReleasedState(configuration, Set.of(ATTRIBUTE_RELEASED_AT), sentinel, true);
			assertTrue(
				stamped.containsAll(Set.of("UNIQUE releasedAt in GLOBAL (inline)", "BODY releasedAt")),
				"the image must hold the value where 2026.2 stored it, or the case proves nothing: " + stamped
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);

			final Evita upgraded = start(configuration);
			assertUpgraded(upgraded);
			final FilterConstraint bySentinel = and(
				attributeEquals(ATTRIBUTE_RELEASED_AT, sentinel.raw()), entityLocaleEquals(Locale.ENGLISH)
			);
			assertEquals(List.of(SENTINEL_OWNER), productsMatching(upgraded, bySentinel));
			assertOwnerRemovable(upgraded, bySentinel);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(Sentinel.class)
		@DisplayName("re-keys a global unique index holding the value and finds the owner by it catalog-wide")
		void shouldRekeyGlobalUniqueIndexWhenUpgradedWithValue(@Nonnull Sentinel sentinel) {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("globalUnique_" + sentinel));
			writeCatalog(
				configuration,
				session -> {
					session.getCatalogSchema().openForWrite()
						.withAttribute(
							ATTRIBUTE_VALID_FROM, sentinel.type(), thatIs -> thatIs.uniqueGlobally().nullable()
						)
						.updateVia(session);
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withGlobalAttribute(ATTRIBUTE_VALID_FROM)
						.updateVia(session);
					for (int owner = 1; owner <= Shape.INLINE.owners(); owner++) {
						session.createNewEntity(ENTITY_PRODUCT, owner)
							.setAttribute(ATTRIBUTE_VALID_FROM, sentinel.writtenValueOf(owner))
							.upsertVia(session);
					}
				}
			);
			final Set<String> stamped = stampReleasedState(configuration, Set.of(ATTRIBUTE_VALID_FROM), sentinel, true);
			assertTrue(
				stamped.containsAll(Set.of("GLOBAL_UNIQUE validFrom", "BODY validFrom")),
				"the image must hold the value where 2026.2 stored it, or the case proves nothing: " + stamped
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);

			final Evita upgraded = start(configuration);
			assertUpgraded(upgraded);
			assertEquals(
				List.of(ENTITY_PRODUCT + ":" + SENTINEL_OWNER),
				upgraded.queryCatalog(
					CATALOG,
					session -> {
						return session.queryListOfEntityReferences(
								query(filterBy(attributeEquals(ATTRIBUTE_VALID_FROM, sentinel.raw())))
							)
							.stream()
							.map(reference -> reference.getType() + ":" + reference.getPrimaryKey())
							.toList();
					}
				)
			);
			assertOwnerRemovable(upgraded, attributeEquals(ATTRIBUTE_VALID_FROM, sentinel.raw()));
		}
	}

	/**
	 * A temporal attribute of a reference, held by the filter indexes of the reference's partitions and of the
	 * referenced type, and by the cardinality counters of the latter.
	 */
	@Nested
	@DisplayName("A filterable reference attribute")
	class FilterIndexOfAReferenceAttribute {

		@ParameterizedTest(name = "{0}")
		@EnumSource(Sentinel.class)
		@DisplayName("loads the partitions of a partitioned reference holding the value and finds the owner by it")
		void shouldLoadPartitionedReferenceWhenUpgradedWithValue(@Nonnull Sentinel sentinel) {
			assertReferenceUpgrades(sentinel, ReferenceIndexing.PARTITIONED);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(Sentinel.class)
		@DisplayName("converts the single-owner partitions holding the value and finds the owner by it")
		void shouldConvertSingleOwnerPartitionsWhenUpgradedWithValue(@Nonnull Sentinel sentinel) {
			assertReferenceUpgrades(sentinel, ReferenceIndexing.CONVERTED);
		}

		/**
		 * Writes three products, each referencing a brand of its own with a filterable attribute - so every partition
		 * of the reference has a single owner and is an index object, as 2026.2 kept it - optionally changes the
		 * reference to filtering only, which keeps the objects for the upgrade to convert, stores the sentinel 2026.2's
		 * way, upgrades the catalog and asserts the owner is found by the sentinel.
		 */
		private void assertReferenceUpgrades(@Nonnull Sentinel sentinel, @Nonnull ReferenceIndexing indexing) {
			final EvitaConfiguration configuration =
				configurationOf(allocatePaths("reference_" + indexing + "_" + sentinel));
			writeCatalog(
				configuration,
				session -> {
					session.defineEntitySchema(ENTITY_BRAND).withoutGeneratedPrimaryKey().updateVia(session);
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withReferenceToEntity(
							REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedForFilteringAndPartitioning()
								.withAttribute(
									ATTRIBUTE_SINCE, sentinel.type(), thatIs -> thatIs.filterable().nullable()
								)
						)
						.updateVia(session);
					for (int owner = 1; owner <= Shape.INLINE.owners(); owner++) {
						session.createNewEntity(ENTITY_BRAND, owner).upsertVia(session);
						final int brand = owner;
						session.createNewEntity(ENTITY_PRODUCT, owner)
							.setReference(
								REFERENCE_BRAND, brand,
								whichIs -> whichIs.setAttribute(ATTRIBUTE_SINCE, sentinel.writtenValueOf(brand))
							)
							.upsertVia(session);
					}
				},
				indexing == ReferenceIndexing.CONVERTED ?
					session -> session.updateEntitySchema(
						new ModifyEntitySchemaMutation(
							ENTITY_PRODUCT,
							new SetReferenceSchemaIndexedMutation(
								REFERENCE_BRAND,
								new ScopedReferenceIndexType[]{
									new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
								},
								new ScopedReferenceIndexedComponents[]{
									new ScopedReferenceIndexedComponents(
										Scope.LIVE,
										new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_ENTITY}
									)
								}
							)
						)
					) :
					null
			);
			final Set<String> stamped = stampReleasedState(configuration, Set.of(ATTRIBUTE_SINCE), sentinel, true);
			assertTrue(
				stamped.containsAll(
					Set.of(
						"FILTER since in REFERENCED_ENTITY (inline)",
						"FILTER since in REFERENCED_ENTITY_TYPE (inline)",
						"CARDINALITY since in REFERENCED_ENTITY_TYPE",
						"REFERENCE_BODY since"
					)
				),
				"the image must hold the value where 2026.2 stored it, or the case proves nothing: " + stamped
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);

			final Evita upgraded = start(configuration);
			assertUpgraded(upgraded);
			assertEquals(
				List.of(SENTINEL_OWNER),
				productsMatching(
					upgraded, referenceHaving(REFERENCE_BRAND, attributeEquals(ATTRIBUTE_SINCE, sentinel.raw()))
				)
			);
			// the reference body holds the value too: removing the reference attribute reads it back
			upgraded.updateCatalog(
				CATALOG,
				session -> {
					session.upsertEntity(
						session.getEntity(
								ENTITY_PRODUCT, SENTINEL_OWNER, referenceContentAll(), attributeContentAll()
							)
							.orElseThrow()
							.openForWrite()
							.setReference(
								REFERENCE_BRAND, SENTINEL_OWNER, whichIs -> whichIs.removeAttribute(ATTRIBUTE_SINCE)
							)
					);
				}
			);
			assertEquals(
				List.of(),
				productsMatching(
					upgraded, referenceHaving(REFERENCE_BRAND, attributeEquals(ATTRIBUTE_SINCE, sentinel.raw()))
				)
			);
		}
	}

	/**
	 * A temporal attribute that is only sortable - an owner sort index, which the upgrade rewrites into the millisecond
	 * form this version compares.
	 */
	@Nested
	@DisplayName("A sort-only entity attribute")
	class OwnerSortIndexOfAnEntityAttribute {

		@ParameterizedTest(name = "{0}")
		@EnumSource(Sentinel.class)
		@DisplayName("rewrites an inline owner sort index holding the value and orders the owner by it")
		void shouldRewriteOwnerSortIndexWhenUpgradedWithValue(@Nonnull Sentinel sentinel) {
			assertOwnerSortIndexUpgrades(sentinel, Shape.INLINE);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(Sentinel.class)
		@DisplayName("rewrites a paged owner sort index holding the value and orders the owner by it")
		void shouldRewritePagedOwnerSortIndexWhenUpgradedWithValue(@Nonnull Sentinel sentinel) {
			assertOwnerSortIndexUpgrades(sentinel, Shape.PAGED);
		}

		/**
		 * Writes `shape` products with a sort-only attribute, replaces their owner sort index with the one 2026.2
		 * wrote - the raw values, in the order of its comparator, in the layout of `shape` - stores the sentinel's body
		 * 2026.2's way, upgrades the catalog and asserts the owners are ordered by the value, the sentinel's owner
		 * included, and stay ordered once it is removed.
		 */
		private void assertOwnerSortIndexUpgrades(@Nonnull Sentinel sentinel, @Nonnull Shape shape) {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("sort_" + shape + "_" + sentinel));
			writeCatalog(
				configuration,
				session -> {
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(ATTRIBUTE_PUBLISHED_AT, sentinel.type(), thatIs -> thatIs.sortable().nullable())
						.updateVia(session);
					for (int owner = 1; owner <= shape.owners(); owner++) {
						session.createNewEntity(ENTITY_PRODUCT, owner)
							.setAttribute(ATTRIBUTE_PUBLISHED_AT, sentinel.writtenValueOf(owner))
							.upsertVia(session);
					}
				}
			);
			// the release stored the raw value of every record, in the order of its own comparator
			final List<LegacyOwnerSortImages.LegacySortIndex> legacy = LegacyOwnerSortImages.writeLegacySortIndexes(
				configuration, CATALOG, ENTITY_PRODUCT, Set.of(ATTRIBUTE_PUBLISHED_AT), sentinel::rawValueOf
			);
			assertEquals(1, legacy.size(), "the products hold one owner sort index: " + legacy);
			assertEquals(shape == Shape.PAGED, legacy.getFirst().paged(), "the legacy sort index must be " + shape);
			assertEquals(
				Set.of("BODY publishedAt"),
				stampReleasedState(configuration, Set.of(ATTRIBUTE_PUBLISHED_AT), sentinel, true)
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);

			final Evita upgraded = start(configuration);
			assertUpgraded(upgraded);
			assertEquals(sentinel.ascendingOrderOf(shape.owners()), naturalOrder(upgraded, ATTRIBUTE_PUBLISHED_AT));
			// the owner's body is read clamped, so a removal must find the key the rewritten sort index holds
			upgraded.updateCatalog(CATALOG, session -> {
				assertTrue(session.deleteEntity(ENTITY_PRODUCT, SENTINEL_OWNER));
			});
			final List<Integer> remaining = new ArrayList<>(sentinel.ascendingOrderOf(shape.owners()));
			remaining.remove(Integer.valueOf(SENTINEL_OWNER));
			assertEquals(remaining, naturalOrder(upgraded, ATTRIBUTE_PUBLISHED_AT));
		}
	}

	/**
	 * A temporal array attribute: every element is a key of the filter index and a counter of its own, and the body
	 * holds the array.
	 */
	@Nested
	@DisplayName("A filterable array attribute")
	class FilterIndexOfAnArrayAttribute {

		@ParameterizedTest(name = "{0}")
		@EnumSource(
			value = Sentinel.class,
			names = {"OFFSET_DATE_TIME_MAX", "OFFSET_DATE_TIME_MIN", "LOCAL_DATE_TIME_MAX", "LOCAL_DATE_TIME_MIN"}
		)
		@DisplayName("loads the index, finds the owner by an element and removes the owner")
		void shouldLoadArrayAttributeWhenUpgradedWithValue(@Nonnull Sentinel sentinel) {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("array_" + sentinel));
			writeCatalog(
				configuration,
				session -> {
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(
							ATTRIBUTE_VALID_UNTIL, sentinel.arrayType(), thatIs -> thatIs.filterable().nullable()
						)
						.updateVia(session);
					for (int owner = 1; owner <= Shape.INLINE.owners(); owner++) {
						session.createNewEntity(ENTITY_PRODUCT, owner)
							.setAttribute(
								ATTRIBUTE_VALID_UNTIL,
								owner == SENTINEL_OWNER ?
									arrayOf(sentinel, sentinel.ordinaryValueOf(owner), sentinel.writtenValueOf(owner)) :
									arrayOf(sentinel, sentinel.ordinaryValueOf(owner))
							)
							.upsertVia(session);
					}
				}
			);
			final Set<String> stamped = stampReleasedState(
				configuration, Set.of(ATTRIBUTE_VALID_UNTIL), sentinel, true
			);
			assertTrue(
				stamped.containsAll(Set.of("FILTER validUntil in GLOBAL (inline)", "BODY validUntil")),
				"the image must hold the value where 2026.2 stored it, or the case proves nothing: " + stamped
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);

			final Evita upgraded = start(configuration);
			assertUpgraded(upgraded);
			assertEquals(
				List.of(SENTINEL_OWNER),
				productsMatching(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, sentinel.raw()))
			);
			assertEquals(
				List.of(SENTINEL_OWNER),
				productsMatching(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, sentinel.ordinaryValueOf(1)))
			);
			assertOwnerRemovable(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, sentinel.raw()));
			assertEquals(
				List.of(),
				productsMatching(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, sentinel.ordinaryValueOf(1)))
			);
		}
	}

	/**
	 * An entity in the archive is not indexed by the attribute, so nothing but its body holds the value until the
	 * entity is restored - when the body is the only source of the key the filter index is given.
	 */
	@Nested
	@DisplayName("An archived entity whose body holds the value")
	class ArchivedEntityHoldingTheValue {

		@ParameterizedTest(name = "{0}")
		@EnumSource(
			value = Sentinel.class,
			names = {"OFFSET_DATE_TIME_MAX", "OFFSET_DATE_TIME_MIN", "LOCAL_DATE_TIME_MAX", "LOCAL_DATE_TIME_MIN"}
		)
		@DisplayName("indexes the value when the entity is restored and removes the owner again")
		void shouldIndexValueWhenArchivedEntityIsRestored(@Nonnull Sentinel sentinel) {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("archived_" + sentinel));
			writeCatalog(
				configuration,
				session -> {
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(
							ATTRIBUTE_VALID_UNTIL, sentinel.type(),
							thatIs -> thatIs.filterableInScope(Scope.LIVE).nullable()
						)
						.updateVia(session);
					for (int owner = 1; owner <= Shape.INLINE.owners(); owner++) {
						session.createNewEntity(ENTITY_PRODUCT, owner)
							.setAttribute(ATTRIBUTE_VALID_UNTIL, sentinel.writtenValueOf(owner))
							.upsertVia(session);
					}
					session.archiveEntity(ENTITY_PRODUCT, SENTINEL_OWNER);
				}
			);
			assertEquals(
				Set.of("BODY validUntil"),
				stampReleasedState(configuration, Set.of(ATTRIBUTE_VALID_UNTIL), sentinel, false),
				"the archive indexes nothing, so the body is the only part that holds the value"
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);

			final Evita upgraded = start(configuration);
			assertUpgraded(upgraded);
			assertEquals(List.of(), productsMatching(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, sentinel.raw())));
			upgraded.updateCatalog(CATALOG, session -> {
				assertTrue(session.restoreEntity(ENTITY_PRODUCT, SENTINEL_OWNER));
			});
			assertEquals(
				List.of(SENTINEL_OWNER),
				productsMatching(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, sentinel.raw()))
			);
			assertOwnerRemovable(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, sentinel.raw()));
		}
	}

	/**
	 * A compound sort index holding a temporal element keeps the element in the owner sort index the upgrade rewrites.
	 */
	@Nested
	@DisplayName("A sortable attribute compound with a temporal element")
	class CompoundSortIndexOfAnEntityAttribute {

		@ParameterizedTest(name = "{0}")
		@EnumSource(Sentinel.class)
		@DisplayName("rewrites the compound sort index, orders the owner by it and removes the owner")
		void shouldRewriteCompoundSortIndexWhenUpgradedWithValue(@Nonnull Sentinel sentinel) {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("compound_" + sentinel));
			writeCatalog(
				configuration,
				session -> {
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(ATTRIBUTE_RANK, Integer.class, AttributeSchemaEditor::nullable)
						.withAttribute(ATTRIBUTE_PUBLISHED_AT, sentinel.type(), AttributeSchemaEditor::nullable)
						.withSortableAttributeCompound(
							COMPOUND_RANK_PUBLISHED_AT,
							AttributeElement.attributeElement(ATTRIBUTE_RANK, OrderDirection.ASC),
							AttributeElement.attributeElement(ATTRIBUTE_PUBLISHED_AT, OrderDirection.ASC)
						)
						.updateVia(session);
					for (int owner = 1; owner <= Shape.INLINE.owners(); owner++) {
						session.createNewEntity(ENTITY_PRODUCT, owner)
							.setAttribute(ATTRIBUTE_RANK, 1)
							.setAttribute(ATTRIBUTE_PUBLISHED_AT, sentinel.writtenValueOf(owner))
							.upsertVia(session);
					}
				}
			);
			assertFalse(
				LegacyOwnerSortImages.writeLegacySortIndexes(
					configuration, CATALOG, ENTITY_PRODUCT, Set.of(COMPOUND_RANK_PUBLISHED_AT),
					owner -> new ComparableArray(new Serializable[]{1, sentinel.rawValueOf(owner)})
				).isEmpty()
			);
			assertEquals(
				Set.of("BODY publishedAt"),
				stampReleasedState(configuration, Set.of(ATTRIBUTE_PUBLISHED_AT), sentinel, true)
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);

			final Evita upgraded = start(configuration);
			assertUpgraded(upgraded);
			assertEquals(
				sentinel.ascendingOrderOf(Shape.INLINE.owners()),
				naturalOrder(upgraded, COMPOUND_RANK_PUBLISHED_AT)
			);
			upgraded.updateCatalog(CATALOG, session -> {
				assertTrue(session.deleteEntity(ENTITY_PRODUCT, SENTINEL_OWNER));
			});
			final List<Integer> remaining = new ArrayList<>(sentinel.ascendingOrderOf(Shape.INLINE.owners()));
			remaining.remove(Integer.valueOf(SENTINEL_OWNER));
			assertEquals(remaining, naturalOrder(upgraded, COMPOUND_RANK_PUBLISHED_AT));
		}
	}

	/**
	 * Two different values beyond the window are held at one edge by this version, so their keys become one.
	 */
	@Nested
	@DisplayName("Two different values beyond the window in one filterable attribute")
	class DifferentValuesClampedOntoOneKey {

		@ParameterizedTest(name = "{0}")
		@EnumSource(Shape.class)
		@DisplayName("merges their buckets, finds both owners by either value and removes them one by one")
		void shouldMergeBucketsWhenUpgradedWithTwoValues(@Nonnull Shape shape) {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("merge_" + shape));
			writeTwoValues(configuration, shape.owners(), false);
			final String layout = shape == Shape.PAGED ? "paged" : "inline";
			assertEquals(
				Set.of("FILTER validUntil in GLOBAL (" + layout + ")", "BODY validUntil"),
				stampTwoValues(configuration)
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);

			final Evita upgraded = start(configuration);
			assertUpgraded(upgraded);
			assertEquals(
				List.of(1, 2), productsMatching(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, SECOND_LATEST_RAW))
			);
			assertEquals(
				List.of(1, 2), productsMatching(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, OffsetDateTime.MAX))
			);
			upgraded.updateCatalog(CATALOG, session -> { assertTrue(session.deleteEntity(ENTITY_PRODUCT, 1)); });
			assertEquals(
				List.of(2), productsMatching(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, OffsetDateTime.MAX))
			);
			upgraded.updateCatalog(CATALOG, session -> { assertTrue(session.deleteEntity(ENTITY_PRODUCT, 2)); });
			assertEquals(
				List.of(), productsMatching(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, OffsetDateTime.MAX))
			);
		}
	}

	/**
	 * The load path merges adjacent equal keys of a paged tree across the boundary of two leaf pages.
	 */
	@Nested
	@DisplayName("Two different values beyond the window on adjacent leaf pages")
	class DifferentValuesOnAdjacentLeafPages {

		@Test
		@DisplayName("loads the tree, merges the buckets across the page boundary and finds both owners")
		void shouldMergeBucketsAcrossLeafPages() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("mergeAcrossPages"));
			writeTwoValues(configuration, Shape.PAGED.owners(), false);
			final Set<String> stamped = stampTwoValues(configuration, ATTRIBUTE_VALID_UNTIL, true);
			assertTrue(
				stamped.containsAll(Set.of("FILTER validUntil in GLOBAL (paged, split)", "BODY validUntil")),
				"the two buckets must sit on two leaf pages, or the case proves nothing: " + stamped
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);

			final Evita upgraded = start(configuration);
			assertUpgraded(upgraded);
			assertEquals(
				List.of(1, 2), productsMatching(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, OffsetDateTime.MAX))
			);
			assertEquals(
				List.of(1, 2), productsMatching(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, SECOND_LATEST_RAW))
			);
			assertEquals(
				List.of(3),
				productsMatching(
					upgraded,
					attributeEquals(ATTRIBUTE_VALID_UNTIL, Sentinel.OFFSET_DATE_TIME_MAX.ordinaryValueOf(3))
				)
			);
			upgraded.updateCatalog(CATALOG, session -> { assertTrue(session.deleteEntity(ENTITY_PRODUCT, 1)); });
			assertEquals(
				List.of(2), productsMatching(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, OffsetDateTime.MAX))
			);
		}
	}

	/**
	 * A unique attribute folded onto its filter tree has no unique index of its own: the migration finds the owners
	 * whose different keys are clamped onto one in the filter tree.
	 */
	@Nested
	@DisplayName("A unique attribute folded onto its filter index")
	class FoldedUniqueAttribute {

		@ParameterizedTest(name = "{0}")
		@EnumSource(Shape.class)
		@DisplayName("refuses the upgrade over two owners whose different values are clamped onto one, writing nothing")
		void shouldRefuseUpgradeWhenTwoOwnersAreClampedOntoOneKey(@Nonnull Shape shape) {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("foldedCollision_" + shape));
			writeTwoValues(configuration, shape.owners(), true);
			final String layout = shape == Shape.PAGED ? "paged" : "inline";
			final Set<String> stamped = stampTwoValues(configuration);
			assertTrue(
				stamped.containsAll(Set.of("FILTER validUntil in GLOBAL (" + layout + ")", "BODY validUntil")),
				"the image must hold the values where 2026.2 stored them, or the case proves nothing: " + stamped
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);
			final Map<String, String> before = catalogFileDigests(configuration);

			final String message = refusalOfStart(configuration);
			assertTrue(message.contains("1 unique value(s)"), message);
			assertTrue(message.contains("beyond the supported date-time window"), message);
			assertTrue(message.contains("attribute `validUntil` of entity `product`"), message);
			assertTrue(message.contains("`product` 1") && message.contains("`product` 2"), message);
			assertEquals(before, catalogFileDigests(configuration), "a refused upgrade must write nothing");
		}

		@Test
		@DisplayName("refuses the upgrade when a clamped value loads under the millisecond of another owner's value")
		void shouldRefuseUpgradeWhenClampedKeyMeetsInWindowKeyOfAnotherOwner() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("foldedEdgeCollision"));
			final OffsetDateTime earlierPlaceholder = EARLIEST_PLACEHOLDER;
			final OffsetDateTime laterPlaceholder = EARLIEST_PLACEHOLDER.plusDays(1);
			// one nanosecond after the edge `OffsetDateTime.MIN` is clamped onto: inside the window, so not clamped,
			// yet truncated onto that very edge when the filter tree loads
			final OffsetDateTime justInsideEdge = OffsetDateTime.ofInstant(
				EvitaDataTypes.clampToSupportedWindow(OffsetDateTime.MIN).toInstant().plusNanos(1), ZoneOffset.UTC
			);
			writeCatalog(
				configuration,
				session -> {
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(
							ATTRIBUTE_VALID_UNTIL, OffsetDateTime.class,
							thatIs -> thatIs.unique().nullable()
						)
						.updateVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTRIBUTE_VALID_UNTIL, earlierPlaceholder)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setAttribute(ATTRIBUTE_VALID_UNTIL, laterPlaceholder)
						.upsertVia(session);
				}
			);
			assertTrue(
				stampReleasedState(
					configuration, Set.of(ATTRIBUTE_VALID_UNTIL),
					new Respelling(earlierPlaceholder, OffsetDateTime.MIN, 1), true
				).contains("FILTER validUntil in GLOBAL (inline)")
			);
			assertTrue(
				stampReleasedState(
					configuration,
					Set.of(ATTRIBUTE_VALID_UNTIL),
					new Respelling(laterPlaceholder, justInsideEdge, 2),
					true
				).contains("FILTER validUntil in GLOBAL (inline)")
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);
			final Map<String, String> before = catalogFileDigests(configuration);

			final String message = refusalOfStart(configuration);
			assertTrue(message.contains("1 unique value(s)"), message);
			assertTrue(message.contains("attribute `validUntil` of entity `product`"), message);
			assertTrue(message.contains("`product` 1") && message.contains("`product` 2"), message);
			assertEquals(before, catalogFileDigests(configuration), "a refused upgrade must write nothing");
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(SubMillisecondTwins.class)
		@DisplayName("refuses the upgrade over two owners whose values differ below the millisecond, writing nothing")
		void shouldRefuseUpgradeWhenTwoOwnersMeetOnlyThroughMillisecondTruncation(@Nonnull SubMillisecondTwins twins) {
			final EvitaConfiguration configuration =
				configurationOf(allocatePaths("foldedSubMillisecondTwins_" + twins));
			writeCatalog(
				configuration,
				session -> {
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(ATTRIBUTE_VALID_UNTIL, twins.type(), thatIs -> thatIs.unique().nullable())
						.updateVia(session);
					for (int owner = 1; owner <= 2; owner++) {
						session.createNewEntity(ENTITY_PRODUCT, owner)
							.setAttribute(ATTRIBUTE_VALID_UNTIL, twins.placeholderOf(owner))
							.upsertVia(session);
					}
				}
			);
			assertTrue(
				stampSubMillisecondTwins(configuration, ATTRIBUTE_VALID_UNTIL, twins)
					.containsAll(Set.of("FILTER validUntil in GLOBAL (inline)", "BODY validUntil"))
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);
			final Map<String, String> before = catalogFileDigests(configuration);

			final String message = refusalOfStart(configuration);
			assertTrue(message.contains("1 unique value(s)"), message);
			assertTrue(message.contains("attribute `validUntil` of entity `product`"), message);
			assertTrue(message.contains("`product` 1") && message.contains("`product` 2"), message);
			assertEquals(before, catalogFileDigests(configuration), "a refused upgrade must write nothing");
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = Sentinel.class, names = {"OFFSET_DATE_TIME_MAX", "OFFSET_DATE_TIME_MIN"})
		@DisplayName("upgrades when only one owner holds a value beyond the window")
		void shouldUpgradeWhenOneOwnerHoldsValueBeyondWindow(@Nonnull Sentinel sentinel) {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("foldedSingle_" + sentinel));
			writeCatalog(
				configuration,
				session -> {
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(
							ATTRIBUTE_VALID_UNTIL, sentinel.type(), thatIs -> thatIs.unique().nullable()
						)
						.updateVia(session);
					for (int owner = 1; owner <= Shape.INLINE.owners(); owner++) {
						session.createNewEntity(ENTITY_PRODUCT, owner)
							.setAttribute(ATTRIBUTE_VALID_UNTIL, sentinel.writtenValueOf(owner))
							.upsertVia(session);
					}
				}
			);
			assertTrue(
				stampReleasedState(configuration, Set.of(ATTRIBUTE_VALID_UNTIL), sentinel, true)
					.contains("FILTER validUntil in GLOBAL (inline)")
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);

			final Evita upgraded = start(configuration);
			assertUpgraded(upgraded);
			assertEquals(
				List.of(SENTINEL_OWNER),
				productsMatching(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, sentinel.raw()))
			);
			assertOwnerRemovable(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, sentinel.raw()));
		}
	}

	/**
	 * Standalone unique indexes keyed by two different values beyond the window are the ones this version holds at one
	 * edge: the refusal has to name the owners without the index it asks overflowing.
	 */
	@Nested
	@DisplayName("Two different values beyond the window in a standalone unique index")
	class StandaloneUniqueCollisions {

		@Test
		@DisplayName("refuses the upgrade over a global unique index, naming both owners and writing nothing")
		void shouldRefuseUpgradeOverGlobalUniqueIndex() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("globalCollision"));
			writeCatalog(
				configuration,
				session -> {
					session.getCatalogSchema().openForWrite()
						.withAttribute(
							ATTRIBUTE_VALID_FROM, OffsetDateTime.class, thatIs -> thatIs.uniqueGlobally().nullable()
						)
						.updateVia(session);
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withGlobalAttribute(ATTRIBUTE_VALID_FROM)
						.updateVia(session);
					for (int owner = 1; owner <= Shape.INLINE.owners(); owner++) {
						session.createNewEntity(ENTITY_PRODUCT, owner)
							.setAttribute(ATTRIBUTE_VALID_FROM, twoValuesOf(owner))
							.upsertVia(session);
					}
				}
			);
			assertTrue(stampTwoValues(configuration, ATTRIBUTE_VALID_FROM).contains("GLOBAL_UNIQUE validFrom"));
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);
			final Map<String, String> before = catalogFileDigests(configuration);

			final String message = refusalOfStart(configuration);
			// the catalog attribute is held by the global index and by the unique index of the collection
			assertTrue(message.contains("2 unique value(s)"), message);
			assertTrue(message.contains("beyond the supported date-time window"), message);
			assertTrue(message.contains("catalog attribute `validFrom` (scope LIVE)"), message);
			assertTrue(message.contains("`product` 1") && message.contains("`product` 2"), message);
			assertEquals(before, catalogFileDigests(configuration), "a refused upgrade must write nothing");
		}

		@Test
		@DisplayName("refuses the upgrade over an owner unique index, naming both owners and writing nothing")
		void shouldRefuseUpgradeOverOwnerUniqueIndex() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("ownerCollision"));
			writeCatalog(
				configuration,
				session -> {
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withLocale(Locale.ENGLISH)
						.withAttribute(
							ATTRIBUTE_RELEASED_AT, OffsetDateTime.class,
							thatIs -> thatIs.localized().unique().nullable()
						)
						.updateVia(session);
					for (int owner = 1; owner <= Shape.INLINE.owners(); owner++) {
						session.createNewEntity(ENTITY_PRODUCT, owner)
							.setAttribute(ATTRIBUTE_RELEASED_AT, Locale.ENGLISH, twoValuesOf(owner))
							.upsertVia(session);
					}
				}
			);
			assertTrue(
				stampTwoValues(configuration, ATTRIBUTE_RELEASED_AT).contains("UNIQUE releasedAt in GLOBAL (inline)")
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);
			final Map<String, String> before = catalogFileDigests(configuration);

			final String message = refusalOfStart(configuration);
			assertTrue(message.contains("1 unique value(s)"), message);
			assertTrue(message.contains("beyond the supported date-time window"), message);
			assertTrue(message.contains("attribute `releasedAt"), message);
			assertTrue(message.contains("`product` 1") && message.contains("`product` 2"), message);
			assertEquals(before, catalogFileDigests(configuration), "a refused upgrade must write nothing");
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(SubMillisecondTwins.class)
		@DisplayName("refuses the upgrade over an owner unique index holding values that differ below the millisecond")
		void shouldRefuseUpgradeWhenOwnerUniqueValuesMeetOnlyThroughMillisecondTruncation(
			@Nonnull SubMillisecondTwins twins
		) {
			final EvitaConfiguration configuration =
				configurationOf(allocatePaths("ownerSubMillisecondTwins_" + twins));
			writeCatalog(
				configuration,
				session -> {
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withLocale(Locale.ENGLISH)
						.withAttribute(
							ATTRIBUTE_RELEASED_AT, twins.type(), thatIs -> thatIs.localized().unique().nullable()
						)
						.updateVia(session);
					for (int owner = 1; owner <= 2; owner++) {
						session.createNewEntity(ENTITY_PRODUCT, owner)
							.setAttribute(ATTRIBUTE_RELEASED_AT, Locale.ENGLISH, twins.placeholderOf(owner))
							.upsertVia(session);
					}
				}
			);
			assertTrue(
				stampSubMillisecondTwins(configuration, ATTRIBUTE_RELEASED_AT, twins)
					.containsAll(Set.of("UNIQUE releasedAt in GLOBAL (inline)", "BODY releasedAt"))
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);
			final Map<String, String> before = catalogFileDigests(configuration);

			final String message = refusalOfStart(configuration);
			assertTrue(message.contains("1 unique value(s)"), message);
			assertTrue(message.contains("attribute `releasedAt"), message);
			assertTrue(message.contains("`product` 1") && message.contains("`product` 2"), message);
			assertEquals(before, catalogFileDigests(configuration), "a refused upgrade must write nothing");
		}
	}

	/**
	 * A reference partition is found again by a key recomputed from the entity body, which this version reads clamped.
	 */
	@Nested
	@DisplayName("A reference partition keyed by a representative date-time value")
	class RepresentativeReferenceKeyHoldingTheValue {

		@ParameterizedTest(name = "{0}")
		@EnumSource(
			value = Sentinel.class,
			names = {"OFFSET_DATE_TIME_MAX", "OFFSET_DATE_TIME_MIN", "LOCAL_DATE_TIME_MAX", "LOCAL_DATE_TIME_MIN"}
		)
		@DisplayName("refuses the upgrade over a value beyond the window, naming the partition and writing nothing")
		void shouldRefuseUpgradeWhenRepresentativeValueIsBeyondWindow(@Nonnull Sentinel sentinel) {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("representative_" + sentinel));
			writeRepresentativeCatalog(configuration, sentinel, List.of(ATTRIBUTE_SINCE));
			final Set<String> stamped = stampReleasedState(configuration, Set.of(ATTRIBUTE_SINCE), sentinel, true);
			assertTrue(
				stamped.contains("KEY since in REFERENCED_ENTITY"),
				"the image must hold the value where 2026.2 stored it, or the case proves nothing: " + stamped
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);
			final Map<String, String> before = catalogFileDigests(configuration);

			final Exception failure = assertThrows(Exception.class, () -> start(configuration));
			final ObsoleteStorageProtocolException refusal = findCause(failure, ObsoleteStorageProtocolException.class);
			final String message = refusal.getMessage();
			assertTrue(
				message.contains("representative attribute value beyond the supported date-time window"),
				message
			);
			assertTrue(message.contains("reference `brand` to 1 of `product`"), message);
			assertTrue(message.contains("stays on storage protocol version 6"), message);
			assertEquals(before, catalogFileDigests(configuration), "a refused upgrade must write nothing");
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = Sentinel.class, names = {"IN_WINDOW_OFFSET_DATE_TIME", "IN_WINDOW_LOCAL_DATE_TIME"})
		@DisplayName("upgrades when the representative value lies inside the window")
		void shouldUpgradeWhenRepresentativeValueIsInsideWindow(@Nonnull Sentinel sentinel) {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("representativeInside_" + sentinel));
			writeRepresentativeCatalog(configuration, sentinel, List.of(ATTRIBUTE_SINCE));
			final Set<String> stamped = stampReleasedState(configuration, Set.of(ATTRIBUTE_SINCE), sentinel, true);
			assertTrue(stamped.contains("KEY since in REFERENCED_ENTITY"), stamped.toString());
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);

			assertUpgraded(start(configuration));
		}

		@Test
		@DisplayName("names a partition keyed by several values beyond the window once, listing all of them")
		void shouldListPartitionOnceWhenSeveralRepresentativeValuesAreBeyondWindow() {
			final Sentinel sentinel = Sentinel.OFFSET_DATE_TIME_MAX;
			final EvitaConfiguration configuration = configurationOf(allocatePaths("representativeTwice"));
			writeRepresentativeCatalog(configuration, sentinel, List.of(ATTRIBUTE_SINCE, ATTRIBUTE_UNTIL));
			final Set<String> stamped =
				stampReleasedState(configuration, Set.of(ATTRIBUTE_SINCE, ATTRIBUTE_UNTIL), sentinel, true);
			assertTrue(
				stamped.contains("KEY since in REFERENCED_ENTITY"),
				"the image must hold the value where 2026.2 stored it, or the case proves nothing: " + stamped
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);
			final Map<String, String> before = catalogFileDigests(configuration);

			final Exception failure = assertThrows(Exception.class, () -> start(configuration));
			final String message = findCause(failure, ObsoleteStorageProtocolException.class).getMessage();
			assertTrue(message.contains(": 1 reference partition(s) are keyed"), message);
			final String partition = "reference `brand` to 1 of `product`";
			assertEquals(message.indexOf(partition), message.lastIndexOf(partition), message);
			// both values of the one key are named, which is what makes the key one of two out-of-window values
			final String value = "`" + OffsetDateTime.MAX + "`";
			assertEquals(2, (message.length() - message.replace(value, "").length()) / value.length(), message);
			assertEquals(before, catalogFileDigests(configuration), "a refused upgrade must write nothing");
		}

		/**
		 * Writes three products, each referencing a brand of its own, with representative temporal attributes that
		 * key the partition of the reference - each of them holding the same value.
		 */
		private void writeRepresentativeCatalog(
			@Nonnull EvitaConfiguration configuration,
			@Nonnull Sentinel sentinel,
			@Nonnull List<String> attributeNames
		) {
			writeCatalog(
				configuration,
				session -> {
					session.defineEntitySchema(ENTITY_BRAND).withoutGeneratedPrimaryKey().updateVia(session);
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withReferenceToEntity(
							REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
							whichIs -> {
								whichIs.indexedForFilteringAndPartitioning();
								for (final String attributeName : attributeNames) {
									whichIs.withAttribute(
										attributeName, sentinel.type(), thatIs -> thatIs.representative().filterable()
									);
								}
							}
						)
						.updateVia(session);
					for (int owner = 1; owner <= Shape.INLINE.owners(); owner++) {
						session.createNewEntity(ENTITY_BRAND, owner).upsertVia(session);
						final int brand = owner;
						session.createNewEntity(ENTITY_PRODUCT, owner)
							.setOrUpdateReference(
								REFERENCE_BRAND, brand, reference -> true,
								whichIs -> {
									for (final String attributeName : attributeNames) {
										whichIs.setAttribute(attributeName, sentinel.writtenValueOf(brand));
									}
								}
							)
							.upsertVia(session);
					}
				}
			);
		}
	}

	/**
	 * The audit compares each re-keyed counter with the bucket it names and must not report a catalog holding a value
	 * beyond the window as damaged.
	 */
	@Nested
	@DisplayName("The audit of cardinality counters")
	class CardinalityAudit {

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = Sentinel.class, names = {"OFFSET_DATE_TIME_MAX", "OFFSET_DATE_TIME_MIN"})
		@DisplayName("reports no damage when the counter and the bucket it names are both clamped")
		void shouldReportNoDamageWhenBothSidesAreClamped(@Nonnull Sentinel sentinel) {
			final Function<Object, Serializable> clamping =
				LegacyOwnerSortIndexCorrection.createClampingNormalizer(OffsetDateTime.class, 0);

			assertEquals(
				0,
				Migration_2026_3.countOrphanedCounters(
					Objects.requireNonNull(
						Migration_2026_3.rekeyCardinalities(releasedCountersOf(sentinel), clamping)
					),
					releasedFilterPartOf(sentinel), OffsetDateTime.class, clamping
				)
			);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = Sentinel.class, names = {"OFFSET_DATE_TIME_MAX", "OFFSET_DATE_TIME_MIN"})
		@DisplayName("reports a false orphan when only the counter is clamped")
		void shouldReportOrphanWhenOnlyTheCounterIsClamped(@Nonnull Sentinel sentinel) {
			final Function<Object, Serializable> clamping =
				LegacyOwnerSortIndexCorrection.createClampingNormalizer(OffsetDateTime.class, 0);
			final Function<Object, Serializable> plain = FilterIndex.getNormalizer(OffsetDateTime.class, 0);

			// the bucket the unclamped normalizer keeps is not the one the clamped counter names
			assertEquals(
				1,
				Migration_2026_3.countOrphanedCounters(
					Objects.requireNonNull(
						Migration_2026_3.rekeyCardinalities(releasedCountersOf(sentinel), clamping)
					),
					releasedFilterPartOf(sentinel), OffsetDateTime.class, plain
				)
			);
		}

		/**
		 * Returns the counters 2026.2 kept for the sentinel's owner and one ordinary owner: the raw values.
		 */
		@Nonnull
		private static Map<AttributeCardinalityKey, Integer> releasedCountersOf(@Nonnull Sentinel sentinel) {
			return Map.of(
				new AttributeCardinalityKey(SENTINEL_OWNER, sentinel.raw()), 1,
				new AttributeCardinalityKey(2, sentinel.ordinaryValueOf(2)), 1
			);
		}

		/**
		 * Returns the filter index holding the buckets {@link #releasedCountersOf} names: the sentinel under the key
		 * 2026.2 gave it, the ordinary value under its current key.
		 */
		@Nonnull
		private static FilterIndexStoragePart releasedFilterPartOf(@Nonnull Sentinel sentinel) {
			return new FilterIndexStoragePart(
				0, new AttributeIndexKey(null, ATTRIBUTE_VALID_UNTIL, null), OffsetDateTime.class,
				new ValueToRecordBitmap[]{
					new ValueToRecordBitmap(new Respelling(sentinel).releasedFilterKey(), SENTINEL_OWNER),
					new ValueToRecordBitmap(
						FilterIndex.getNormalizer(OffsetDateTime.class, 0).apply(sentinel.ordinaryValueOf(2)), 2
					)
				},
				null, 0L
			);
		}
	}

	/**
	 * The entity body is the one structure a later write derives index keys from: removing an attribute value, or its
	 * owner, looks the stored value up in every index holding it. These cases keep the index keys in the window - the
	 * state an upgrade clamping the indexes alone would leave - and only the body as 2026.2 wrote it.
	 */
	@Nested
	@DisplayName("An entity body holding the value over indexes inside the window")
	class EntityBodyOverClampedIndexes {

		@ParameterizedTest(name = "{0}")
		@EnumSource(
			value = Sentinel.class,
			names = {
				"OFFSET_DATE_TIME_MAX", "LOCAL_DATE_TIME_MAX", "IN_WINDOW_OFFSET_DATE_TIME", "IN_WINDOW_LOCAL_DATE_TIME"
			}
		)
		@DisplayName("removes the owner whose body holds the value")
		void shouldRemoveOwnerWhenBodyHoldsValue(@Nonnull Sentinel sentinel) {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("body_" + sentinel));
			// the current engine clamps the sentinel on its way in, so every index holds it inside the window
			writeCatalog(
				configuration,
				session -> {
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(ATTRIBUTE_VALID_UNTIL, sentinel.type(), thatIs -> thatIs.filterable().nullable())
						.updateVia(session);
					for (int owner = 1; owner <= Shape.INLINE.owners(); owner++) {
						session.createNewEntity(ENTITY_PRODUCT, owner)
							.setAttribute(ATTRIBUTE_VALID_UNTIL, sentinel.rawValueOf(owner))
							.upsertVia(session);
					}
				}
			);
			assertEquals(
				Set.of("BODY validUntil"),
				stampReleasedState(
					configuration, Set.of(ATTRIBUTE_VALID_UNTIL),
					new Respelling(
						Objects.requireNonNull(EvitaDataTypes.toSupportedStoredType(sentinel.raw())), sentinel.raw()
					),
					false
				)
			);
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);

			final Evita upgraded = start(configuration);
			assertUpgraded(upgraded);
			assertEquals(
				List.of(SENTINEL_OWNER),
				productsMatching(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, sentinel.raw()))
			);
			assertOwnerRemovable(upgraded, attributeEquals(ATTRIBUTE_VALID_UNTIL, sentinel.raw()));
		}
	}

	/**
	 * Builds an array of the declared type of `sentinel` out of values of that type.
	 */
	@Nonnull
	private static Serializable arrayOf(@Nonnull Sentinel sentinel, @Nonnull Serializable... values) {
		return sentinel.type() == OffsetDateTime.class ?
			Arrays.stream(values).map(OffsetDateTime.class::cast).toArray(OffsetDateTime[]::new) :
			Arrays.stream(values).map(LocalDateTime.class::cast).toArray(LocalDateTime[]::new);
	}

	/**
	 * Returns the primary keys of all products - as many as the largest {@link Shape} writes - ordered by an ascending
	 * sort of the attribute or compound.
	 */
	@Nonnull
	private static List<Integer> naturalOrder(@Nonnull Evita evita, @Nonnull String sortedBy) {
		return evita.queryCatalog(
			CATALOG,
			session -> {
				return session.queryListOfEntityReferences(
						query(
							collection(ENTITY_PRODUCT),
							orderBy(attributeNatural(sortedBy, OrderDirection.ASC)),
							require(page(1, Shape.PAGED.owners()))
						)
					)
					.stream()
					.map(EntityReferenceContract::getPrimaryKey)
					.toList();
			}
		);
	}

	/**
	 * Writes `owners` products of whom the first two hold two different values that 2026.2 stored beyond the window -
	 * their placeholders are {@link #LATEST_PLACEHOLDER} and {@link #SECOND_LATEST_PLACEHOLDER} - in a filterable
	 * attribute that is unique when `unique` is set.
	 */
	private void writeTwoValues(@Nonnull EvitaConfiguration configuration, int owners, boolean unique) {
		writeCatalog(
			configuration,
			session -> {
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(
						ATTRIBUTE_VALID_UNTIL, OffsetDateTime.class,
						thatIs -> {
							// a unique attribute is implicitly filterable
							if (unique) {
								thatIs.unique();
							} else {
								thatIs.filterable();
							}
							thatIs.nullable();
						}
					)
					.updateVia(session);
				for (int owner = 1; owner <= owners; owner++) {
					session.createNewEntity(ENTITY_PRODUCT, owner)
						.setAttribute(ATTRIBUTE_VALID_UNTIL, twoValuesOf(owner))
						.upsertVia(session);
				}
			}
		);
	}

	/**
	 * Stores the two values written by {@link #writeTwoValues} the way 2026.2 stored them: {@link #LATEST_PLACEHOLDER}
	 * as `OffsetDateTime.MAX` and {@link #SECOND_LATEST_PLACEHOLDER} as {@link #SECOND_LATEST_RAW}.
	 *
	 * @return the labels of the parts rewritten
	 */
	@Nonnull
	private static Set<String> stampTwoValues(@Nonnull EvitaConfiguration configuration) {
		return stampTwoValues(configuration, ATTRIBUTE_VALID_UNTIL);
	}

	/**
	 * Stores the two values of {@link #twoValuesOf} in the named attribute the way 2026.2 stored them.
	 *
	 * @return the labels of the parts rewritten
	 */
	@Nonnull
	private static Set<String> stampTwoValues(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String attributeName
	) {
		return stampTwoValues(configuration, attributeName, false);
	}

	/**
	 * Stores the two values of {@link #twoValuesOf} in the named attribute the way 2026.2 stored them, and when
	 * `splitPages` is set leaves the two buckets of a paged filter index on two adjacent leaf pages.
	 *
	 * @return the labels of the parts rewritten
	 */
	@Nonnull
	private static Set<String> stampTwoValues(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String attributeName,
		boolean splitPages
	) {
		final Set<String> stamped = new TreeSet<>();
		stamped.addAll(
			stampReleasedState(
				configuration, Set.of(attributeName), new Respelling(LATEST_PLACEHOLDER, OffsetDateTime.MAX, 1), true
			)
		);
		stamped.addAll(
			stampReleasedState(
				configuration, Set.of(attributeName),
				new Respelling(SECOND_LATEST_PLACEHOLDER, SECOND_LATEST_RAW, 2, splitPages), true
			)
		);
		return stamped;
	}

	/**
	 * Stores the values {@link SubMillisecondTwins#placeholderOf} gave products 1 and 2 in the named attribute the way
	 * 2026.2 stored two values of one millisecond, {@link SubMillisecondTwins#twinOf}.
	 *
	 * @return the labels of the parts rewritten
	 */
	@Nonnull
	private static Set<String> stampSubMillisecondTwins(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String attributeName,
		@Nonnull SubMillisecondTwins twins
	) {
		final Set<String> stamped = new TreeSet<>();
		for (int owner = 1; owner <= 2; owner++) {
			final Respelling respelling = new Respelling(twins.placeholderOf(owner), twins.twinOf(owner), owner);
			stamped.addAll(stampReleasedState(configuration, Set.of(attributeName), respelling, true));
		}
		return stamped;
	}

	/**
	 * Returns the value a product holds in the two-values cases: the two placeholders for the first two products, an
	 * ordinary value for the rest.
	 */
	@Nonnull
	private static OffsetDateTime twoValuesOf(int owner) {
		if (owner == 1) {
			return LATEST_PLACEHOLDER;
		}
		if (owner == 2) {
			return SECOND_LATEST_PLACEHOLDER;
		}
		return (OffsetDateTime) Sentinel.OFFSET_DATE_TIME_MAX.ordinaryValueOf(owner);
	}

	/**
	 * Starts the engine over the stamped catalog, asserts that the start is refused as an obsolete storage protocol
	 * upgrade and returns the message of the refusal.
	 */
	@Nonnull
	private String refusalOfStart(@Nonnull EvitaConfiguration configuration) {
		final Exception failure = assertThrows(Exception.class, () -> start(configuration));
		assertEquals(CatalogState.CORRUPTED, stateOfFailedStart());
		final String message = findCause(failure, ObsoleteStorageProtocolException.class).getMessage();
		assertTrue(
			message.contains(
				"cannot be upgraded to storage protocol version " + PersistenceService.STORAGE_PROTOCOL_VERSION
			),
			message
		);
		return message;
	}

	/**
	 * Returns the SHA-256 digest of every file of the catalog folder, by file name.
	 */
	@Nonnull
	private static Map<String, String> catalogFileDigests(@Nonnull EvitaConfiguration configuration) {
		final Map<String, String> digests = new TreeMap<>();
		try (Stream<Path> files = Files.list(OfflineCatalogImages.catalogFolder(configuration, CATALOG))) {
			for (final Path file : files.toList()) {
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
	 * Returns the first throwable of type `type` in the cause chain of `throwable`,
	 * failing the test when there is none.
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
	 * Asserts the catalog came up alive after the upgrade.
	 */
	private static void assertUpgraded(@Nonnull Evita upgraded) {
		assertEquals(CatalogState.ALIVE, upgraded.getCatalogState(CATALOG).orElseThrow());
	}

	/**
	 * Returns the primary keys of the products `filter` matches, in ascending order.
	 */
	@Nonnull
	private static List<Integer> productsMatching(@Nonnull Evita evita, @Nonnull FilterConstraint filter) {
		return evita.queryCatalog(
			CATALOG,
			session -> {
				return session.queryListOfEntityReferences(query(collection(ENTITY_PRODUCT), filterBy(filter)))
					.stream()
					.map(EntityReferenceContract::getPrimaryKey)
					.sorted()
					.toList();
			}
		);
	}

	/**
	 * Removes the owner of the sentinel - which takes its stored values out of every index holding them - and asserts
	 * `filter` no longer finds it.
	 */
	private static void assertOwnerRemovable(@Nonnull Evita evita, @Nonnull FilterConstraint filter) {
		evita.updateCatalog(
			CATALOG,
			session -> {
				assertTrue(session.deleteEntity(ENTITY_PRODUCT, SENTINEL_OWNER));
			}
		);
		assertEquals(List.of(), productsMatching(evita, filter));
	}

	/**
	 * The value the current engine stored for an owner, and the value 2026.2 stored in its place.
	 *
	 * @param written the value the current engine wrote
	 * @param raw     the value 2026.2 stored
	 * @param owner   the product whose entity body the value is respelled in
	 * @param splitLastBucket TRUE to move the last bucket of a paged filter index onto a leaf page of its own
	 */
	record Respelling(@Nonnull Serializable written, @Nonnull Serializable raw, int owner, boolean splitLastBucket) {

		/**
		 * Respells the value of the passed owner, leaving the leaf pages of a paged filter index as they are.
		 */
		Respelling(@Nonnull Serializable written, @Nonnull Serializable raw, int owner) {
			this(written, raw, owner, false);
		}

		/**
		 * Respells the value of {@link #SENTINEL_OWNER}.
		 */
		Respelling(@Nonnull Serializable written, @Nonnull Serializable raw) {
			this(written, raw, SENTINEL_OWNER, false);
		}

		/**
		 * Respells the placeholder the current engine wrote for the sentinel.
		 */
		Respelling(@Nonnull Sentinel sentinel) {
			this(sentinel.writtenValueOf(SENTINEL_OWNER), sentinel.raw(), SENTINEL_OWNER, false);
		}

		/**
		 * Returns the key the current engine gave the written value in a filter index or a cardinality counter.
		 */
		@Nonnull
		Serializable writtenKey() {
			return FilterIndex.getNormalizer(this.written.getClass(), 0).apply(this.written);
		}

		/**
		 * Returns the key 2026.2 gave the raw value in a filter index: its `Instant`, a `LocalDateTime` anchored at
		 * UTC, a `LocalTime` as it is - never truncated.
		 */
		@Nonnull
		Serializable releasedFilterKey() {
			if (this.raw instanceof OffsetDateTime offsetDateTime) {
				return offsetDateTime.toInstant();
			} else if (this.raw instanceof LocalDateTime localDateTime) {
				return localDateTime.toInstant(ZoneOffset.UTC);
			} else {
				return (LocalTime) this.raw;
			}
		}

		/**
		 * Returns the value the current engine persisted for the written value in a unique index.
		 */
		@Nonnull
		Serializable writtenUniqueValue() {
			return UniqueIndex.toPersistedValue(this.written.getClass(), 0, this.written);
		}
	}

	/**
	 * Shorthand of {@link #stampReleasedState(EvitaConfiguration, Set, Respelling, boolean)} for the placeholder the
	 * current engine wrote for `sentinel`.
	 */
	@Nonnull
	private static Set<String> stampReleasedState(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull Set<String> attributeNames,
		@Nonnull Sentinel sentinel,
		boolean indexes
	) {
		return stampReleasedState(configuration, attributeNames, new Respelling(sentinel), indexes);
	}

	/**
	 * Rewrites, with the engine stopped, every part of the catalog that holds the written value of one of the named
	 * attributes so that it holds what 2026.2 stored instead: the entity body of the sentinel's owner always, and when
	 * `indexes` is set every filter index (inline and paged), standalone owner unique index, global unique index and
	 * cardinality counter as well. The result is published under the current storage protocol, through the same
	 * flush-and-re-address sequence a migration publishes a rewritten collection with.
	 *
	 * @param configuration  the configuration of the engine
	 * @param attributeNames the attributes whose parts are rewritten
	 * @param respelling     the written value and its 2026.2 counterpart
	 * @param indexes        TRUE to rewrite the indexes too, FALSE for the entity body alone
	 * @return the labels of the parts rewritten - `<index type> <attribute> in <entity index type> (<layout>)`,
	 * `GLOBAL_UNIQUE <attribute>`, `BODY <attribute>`
	 */
	@Nonnull
	private static Set<String> stampReleasedState(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull Set<String> attributeNames,
		@Nonnull Respelling respelling,
		boolean indexes
	) {
		final int catalogFileIndex = lastBootstrap(configuration, CATALOG).catalogFileIndex();
		final Set<String> result = withOfflineCatalog(
			configuration, CATALOG,
			service -> {
				final long catalogVersion = service.getLastCatalogVersion();
				final CatalogOffsetIndexStoragePartPersistenceService catalogParts =
					service.getStoragePartPersistenceService(catalogVersion);
				final CatalogHeader<LogFileRecordReference, CollectionFileReference> header =
					catalogParts.getCatalogHeader(catalogVersion);
				final Set<String> stamped = new TreeSet<>();
				if (indexes) {
					stampGlobalUniqueIndexes(catalogVersion, catalogParts, attributeNames, respelling, stamped);
				}
				final Map<String, CollectionFileReference> collectionFileIndex =
					new HashMap<>(header.collectionFileIndex());
				for (final CollectionFileReference collection : header.getEntityTypeFileIndexes()) {
					final EntityCollectionFileHeader storedHeader = EntityCollectionHeaderReconciler.reconcile(
						CATALOG, collection,
						Objects.requireNonNull(
							catalogParts.getStoragePart(
								catalogVersion, collection.entityTypePrimaryKey(), EntityCollectionFileHeader.class
							)
						)
					);
					ReleasedAttributeValueImages.withReleasedBodies(
						configuration, CATALOG, catalogVersion, service.getCatalogStoragePath(), storedHeader,
						collectionService -> {
						final OffsetIndexStoragePartPersistenceService storage =
							collectionService.getStoragePartPersistenceService();
						boolean changed = false;
						if (indexes) {
							final EntityCollectionFileHeader collectionHeader =
								collectionService.getEntityCollectionHeader();
							for (final Integer indexPrimaryKey : listedIndexes(collectionHeader)) {
								changed |= stampEntityIndex(
									catalogVersion, storage, indexPrimaryKey, attributeNames, respelling, stamped
								);
							}
						}
						if (ENTITY_PRODUCT.equals(collection.entityType())) {
							changed |= stampBody(catalogVersion, storage, attributeNames, respelling, stamped);
						}
						if (changed) {
							final OffsetIndexDescriptor descriptor = collectionService.flush(
								catalogVersion,
								new NoChangeHeaderInfoSupplier(collectionService.getEntityCollectionHeader())
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
							return null;
						}
					);
				}
				catalogParts.writeCatalogHeader(
					PersistenceService.STORAGE_PROTOCOL_VERSION, catalogVersion, service.getCatalogStoragePath(),
					header.walFileReference(), collectionFileIndex, header.catalogId(), header.catalogName(),
					header.catalogState(), header.lastEntityCollectionPrimaryKey()
				);
				service.recordBootstrap(catalogVersion, CATALOG, catalogFileIndex, null);
				return stamped;
			}
		);
		return result;
	}

	/**
	 * Rewrites the global unique indexes of the named catalog attributes, in every scope, to hold the raw value where
	 * they hold the written one.
	 */
	private static void stampGlobalUniqueIndexes(
		long catalogVersion,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService catalogParts,
		@Nonnull Set<String> attributeNames,
		@Nonnull Respelling respelling,
		@Nonnull Set<String> stamped
	) {
		for (final Scope scope : Scope.values()) {
			final CatalogIndexStoragePart catalogIndex = catalogParts.getStoragePart(
				catalogVersion, CatalogIndexStoragePart.getStoragePartPKForScope(scope), CatalogIndexStoragePart.class
			);
			if (catalogIndex == null) {
				continue;
			}
			for (final AttributeKey attributeKey : catalogIndex.getSharedAttributeUniqueIndexes()) {
				if (!attributeNames.contains(attributeKey.attributeName())) {
					continue;
				}
				final long rootPk = GlobalUniqueIndexStoragePart.computeUniquePartId(
					scope, attributeKey, catalogParts.getReadOnlyKeyCompressor()
				);
				final GlobalUniqueIndexStoragePart root = Objects.requireNonNull(
					catalogParts.getStoragePart(catalogVersion, rootPk, GlobalUniqueIndexStoragePart.class)
				);
				assertFalse(root.isPaged(), "the case writes a handful of values - an inline global unique index");
				final Serializable[] values = respellValues(Objects.requireNonNull(root.getValues()), respelling);
				if (values != null) {
					catalogParts.putStoragePart(
						catalogVersion,
						new GlobalUniqueIndexStoragePart(
							scope, attributeKey, root.getType(), values, root.getPayloads(), root.getLocaleIndex(),
							rootPk
						)
					);
					stamped.add("GLOBAL_UNIQUE " + attributeKey.attributeName());
				}
			}
		}
	}

	/**
	 * Rewrites the filter, standalone unique and cardinality parts of the named attributes in one entity index.
	 *
	 * @return TRUE when any part was rewritten
	 */
	private static boolean stampEntityIndex(
		long catalogVersion,
		@Nonnull OffsetIndexStoragePartPersistenceService storage,
		int indexPrimaryKey,
		@Nonnull Set<String> attributeNames,
		@Nonnull Respelling respelling,
		@Nonnull Set<String> stamped
	) {
		final EntityIndexStoragePart manifest = Objects.requireNonNull(
			storage.getStoragePart(catalogVersion, indexPrimaryKey, EntityIndexStoragePart.class)
		);
		final String entityIndexType = manifest.getEntityIndexKey().type().name();
		boolean changed = stampRepresentativeKey(catalogVersion, storage, manifest, respelling, stamped);
		for (final AttributeIndexStorageKey storageKey : manifest.getAttributeIndexes()) {
			final String attributeName = storageKey.attribute().attributeName();
			if (!attributeNames.contains(attributeName)) {
				continue;
			}
			final long partPk = AttributeIndexStoragePart.computeUniquePartId(
				indexPrimaryKey, storageKey.indexType(), storageKey.attribute(), storage.getReadOnlyKeyCompressor()
			);
			// sort indexes are written by `LegacyOwnerSortImages`, a replica of the release's own writer
			final String label = switch (storageKey.indexType()) {
				case FILTER -> stampFilterIndex(catalogVersion, storage, indexPrimaryKey, partPk, respelling);
				case UNIQUE -> stampOwnerUniqueIndex(catalogVersion, storage, indexPrimaryKey, partPk, respelling);
				case CARDINALITY -> stampCardinalityIndex(catalogVersion, storage, partPk, respelling);
				case SORT, CHAIN -> null;
			};
			if (label != null) {
				stamped.add(storageKey.indexType() + " " + attributeName + " in " + entityIndexType + label);
				changed = true;
			}
		}
		return changed;
	}

	/**
	 * Rewrites the manifest of a reduced entity index keyed by a representative reference key so that the key holds the
	 * raw value where it holds the written one - the key is persisted whole, in the manifest.
	 *
	 * @return TRUE when the manifest was rewritten
	 */
	private static boolean stampRepresentativeKey(
		long catalogVersion,
		@Nonnull OffsetIndexStoragePartPersistenceService storage,
		@Nonnull EntityIndexStoragePart manifest,
		@Nonnull Respelling respelling,
		@Nonnull Set<String> stamped
	) {
		final EntityIndexKey indexKey = manifest.getEntityIndexKey();
		if (!(indexKey.discriminator() instanceof RepresentativeReferenceKey key)) {
			return false;
		}
		final Serializable[] values = key.representativeAttributeValues().clone();
		boolean changed = false;
		for (int i = 0; i < values.length; i++) {
			if (respelling.written().equals(values[i])) {
				values[i] = respelling.raw();
				changed = true;
			}
		}
		if (!changed) {
			return false;
		}
		storage.putStoragePart(
			catalogVersion,
			new EntityIndexStoragePart(
				manifest.getPrimaryKey(), manifest.getVersion(),
				new EntityIndexKey(
					indexKey.type(), indexKey.scope(), new RepresentativeReferenceKey(key.referenceKey(), values)
				),
				manifest.getAttributeIndexes(), manifest.getPriceIndexes(), manifest.isHierarchyIndex(),
				manifest.getFacetIndexes(), manifest.getHistogramIndexes(), manifest.getFulltextIndexes()
			)
		);
		stamped.add("KEY since in " + indexKey.type().name());
		return true;
	}

	/**
	 * Rewrites one filter index - its inline buckets, or every leaf page of a paged one - to key the bucket of the
	 * written value by the key 2026.2 gave the raw value. The sentinel takes the placeholder's position, so the
	 * buckets stay in order.
	 *
	 * @return the layout label when the index was rewritten, `null` when it holds no written value
	 */
	@Nullable
	private static String stampFilterIndex(
		long catalogVersion,
		@Nonnull OffsetIndexStoragePartPersistenceService storage,
		int indexPrimaryKey,
		long rootPk,
		@Nonnull Respelling respelling
	) {
		final FilterIndexStoragePart root = Objects.requireNonNull(
			storage.getStoragePart(catalogVersion, rootPk, FilterIndexStoragePart.class)
		);
		if (!root.isPaged()) {
			final ValueToRecordBitmap[] points = root.getHistogramPoints().clone();
			boolean changed = false;
			for (int i = 0; i < points.length; i++) {
				if (respelling.writtenKey().equals(points[i].getValue())) {
					points[i] = new ValueToRecordBitmap(respelling.releasedFilterKey(), points[i].getRecordIds());
					changed = true;
				}
			}
			if (!changed) {
				return null;
			}
			storage.putStoragePart(
				catalogVersion,
				new FilterIndexStoragePart(
					root.getEntityIndexPrimaryKey(), root.getAttributeIndexKey(), root.getAttributeType(), points,
					root.getRangeIndex(), root.getIndexedDecimalPlaces(), false, root.getHighWaterPageSequence(),
					root.getLeafPageSequences(), root.isRangePaged(), root.getRangeHighWaterPageSequence(),
					root.getRangeLeafPageSequences(), root.getNextValueId(), root.getInlineValueIds(), rootPk
				)
			);
			return " (inline)";
		}
		final int streamId = storage.getReadOnlyKeyCompressor().getId(
			new LeafStreamKey(
				indexPrimaryKey, new AttributeKeyWithIndexType(root.getAttributeIndexKey(), AttributeIndexType.FILTER)
			)
		);
		boolean changed = false;
		for (final int pageSequence : root.getLeafPageSequences()) {
			final long pagePk = AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence);
			final FilterIndexLeafPagePart page = Objects.requireNonNull(
				storage.getStoragePart(catalogVersion, pagePk, FilterIndexLeafPagePart.class)
			);
			final ValueToRecord[] buckets = page.getBuckets().clone();
			boolean pageChanged = false;
			for (int i = 0; i < buckets.length; i++) {
				if (respelling.writtenKey().equals(buckets[i].getValue())) {
					buckets[i] = buckets[i] instanceof ValueToRecordPrimitive primitive ?
						new ValueToRecordPrimitive(respelling.releasedFilterKey(), primitive.getRecordId()) :
						new ValueToRecordBitmap(respelling.releasedFilterKey(), buckets[i].getRecordIds());
					pageChanged = true;
				}
			}
			if (pageChanged) {
				storage.putStoragePart(
					catalogVersion,
					new FilterIndexLeafPagePart(streamId, pageSequence, buckets, page.getValueIds(), pagePk)
				);
				changed = true;
			}
		}
		if (changed && respelling.splitLastBucket()) {
			splitLastBucketOntoNewPage(catalogVersion, storage, indexPrimaryKey, rootPk, streamId, root);
			return " (paged, split)";
		}
		return changed ? " (paged)" : null;
	}

	/**
	 * Moves the last bucket of the last leaf page of a paged filter index onto a new leaf page of its own, so that the
	 * two buckets in front of the page boundary are the last of one page and the first of the next.
	 */
	private static void splitLastBucketOntoNewPage(
		long catalogVersion,
		@Nonnull OffsetIndexStoragePartPersistenceService storage,
		int indexPrimaryKey,
		long rootPk,
		int streamId,
		@Nonnull FilterIndexStoragePart root
	) {
		final int[] sequences = root.getLeafPageSequences();
		final int lastSequence = sequences[sequences.length - 1];
		final long lastPk = AbstractLeafPagePart.computeUniquePartId(streamId, lastSequence);
		final FilterIndexLeafPagePart last = Objects.requireNonNull(
			storage.getStoragePart(catalogVersion, lastPk, FilterIndexLeafPagePart.class)
		);
		final ValueToRecord[] buckets = last.getBuckets();
		assertTrue(buckets.length >= 2, "the last page must hold two buckets to be split");
		final int newSequence = root.getHighWaterPageSequence() + 1;
		final long newPk = AbstractLeafPagePart.computeUniquePartId(streamId, newSequence);
		storage.putStoragePart(
			catalogVersion,
			new FilterIndexLeafPagePart(
				streamId, lastSequence, Arrays.copyOf(buckets, buckets.length - 1), null, lastPk
			)
		);
		storage.putStoragePart(
			catalogVersion,
			new FilterIndexLeafPagePart(
				streamId, newSequence, new ValueToRecord[]{buckets[buckets.length - 1]}, null, newPk
			)
		);
		final int[] newSequences = Arrays.copyOf(sequences, sequences.length + 1);
		newSequences[sequences.length] = newSequence;
		storage.putStoragePart(
			catalogVersion,
			FilterIndexStoragePart.paged(
				root.getEntityIndexPrimaryKey(), root.getAttributeIndexKey(), root.getAttributeType(),
				root.getRangeIndex(), root.getIndexedDecimalPlaces(), newSequence, newSequences, rootPk
			)
		);
	}

	/**
	 * Rewrites one standalone owner unique index to hold the raw value where it holds the written one. A unique part
	 * folded onto a filter index carries no values and is left alone.
	 *
	 * @return the layout label when the index was rewritten, `null` otherwise
	 */
	@Nullable
	private static String stampOwnerUniqueIndex(
		long catalogVersion,
		@Nonnull OffsetIndexStoragePartPersistenceService storage,
		int indexPrimaryKey,
		long rootPk,
		@Nonnull Respelling respelling
	) {
		final UniqueIndexStoragePart root =
			storage.getStoragePart(catalogVersion, rootPk, UniqueIndexStoragePart.class);
		if (root == null || (!root.isPaged() && root.getValues() == null)) {
			return null;
		}
		assertFalse(root.isPaged(), "the case writes a handful of values - an inline owner unique index");
		final Serializable[] values = respellValues(Objects.requireNonNull(root.getValues()), respelling);
		if (values == null) {
			return null;
		}
		storage.putStoragePart(
			catalogVersion,
			new UniqueIndexStoragePart(
				indexPrimaryKey, root.getAttributeIndexKey(), root.getType(), values, root.getRecordIds(), rootPk
			)
		);
		return " (inline)";
	}

	/**
	 * Returns a copy of the persisted unique values with the written value replaced by the raw one, or `null` when
	 * they do not hold the written value. The raw value takes the written value's position in the natural order 2026.2
	 * kept its values in.
	 */
	@Nullable
	private static Serializable[] respellValues(@Nonnull Serializable[] persisted, @Nonnull Respelling respelling) {
		final Serializable[] values = persisted.clone();
		boolean changed = false;
		for (int i = 0; i < values.length; i++) {
			if (respelling.writtenUniqueValue().equals(values[i])) {
				values[i] = respelling.raw();
				changed = true;
			}
		}
		return changed ? values : null;
	}

	/**
	 * Rewrites one cardinality index to count the raw value where it counts the written one - 2026.2 keyed its
	 * counters by the value as written.
	 *
	 * @return an empty label when the index was rewritten, `null` otherwise
	 */
	@Nullable
	private static String stampCardinalityIndex(
		long catalogVersion,
		@Nonnull OffsetIndexStoragePartPersistenceService storage,
		long partPk,
		@Nonnull Respelling respelling
	) {
		final AttributeCardinalityIndexStoragePart part = Objects.requireNonNull(
			storage.getStoragePart(catalogVersion, partPk, AttributeCardinalityIndexStoragePart.class)
		);
		final AttributeCardinalityIndex index = part.getCardinalityIndex();
		final Map<AttributeCardinalityKey, Integer> counters = new HashMap<>(index.getCardinalities().size() * 2);
		boolean changed = false;
		for (final Entry<AttributeCardinalityKey, Integer> counter : index.getCardinalities().entrySet()) {
			if (respelling.writtenKey().equals(counter.getKey().value())) {
				counters.put(
					new AttributeCardinalityKey(counter.getKey().recordId(), respelling.raw()), counter.getValue()
				);
				changed = true;
			} else {
				counters.put(counter.getKey(), counter.getValue());
			}
		}
		if (!changed) {
			return null;
		}
		storage.putStoragePart(
			catalogVersion,
			new AttributeCardinalityIndexStoragePart(
				part.getEntityIndexPrimaryKey(), part.getAttributeIndexKey(),
				new AttributeCardinalityIndex(index.getValueType(), counters), partPk
			)
		);
		return "";
	}

	/**
	 * Rewrites the attribute bodies of the respelled owner - its global and its English attributes - and the reference
	 * bodies it holds to carry the raw value where they hold the written one. A value that is an array is respelled
	 * element by element.
	 *
	 * @return TRUE when any body was rewritten
	 */
	private static boolean stampBody(
		long catalogVersion,
		@Nonnull OffsetIndexStoragePartPersistenceService storage,
		@Nonnull Set<String> attributeNames,
		@Nonnull Respelling respelling,
		@Nonnull Set<String> stamped
	) {
		boolean changed = false;
		for (final Locale locale : new Locale[]{null, Locale.ENGLISH}) {
			final OptionalLong partPk = AttributesStoragePart.computeUniquePartId(
				storage.getReadOnlyKeyCompressor(), new EntityAttributesSetKey(respelling.owner(), locale)
			);
			if (partPk.isEmpty()) {
				continue;
			}
			final AttributesStoragePart body =
				storage.getStoragePart(catalogVersion, partPk.getAsLong(), AttributesStoragePart.class);
			if (body == null) {
				continue;
			}
			final AttributeValue[] attributes = body.getAttributes().clone();
			boolean bodyChanged = false;
			for (int i = 0; i < attributes.length; i++) {
				final AttributeValue attribute = attributes[i];
				final Serializable respelled = attributeNames.contains(attribute.key().attributeName()) ?
					respell(attribute.value(), respelling) : null;
				if (respelled != null) {
					attributes[i] = new AttributeValue(attribute, respelled);
					stamped.add("BODY " + attribute.key().attributeName());
					bodyChanged = true;
				}
			}
			if (bodyChanged) {
				storage.putStoragePart(
					catalogVersion,
					new AttributesStoragePart(partPk.getAsLong(), respelling.owner(), locale, attributes, -1)
				);
				changed = true;
			}
		}
		changed |= stampReferenceBody(catalogVersion, storage, attributeNames, respelling, stamped);
		return changed;
	}

	/**
	 * Returns `value` with the written value replaced by the raw one - element by element in an array - or `null` when
	 * it holds no written value.
	 */
	@Nullable
	private static Serializable respell(@Nullable Serializable value, @Nonnull Respelling respelling) {
		if (respelling.written().equals(value)) {
			return respelling.raw();
		} else if (value instanceof Object[] elements) {
			final Object[] respelled = elements.clone();
			boolean changed = false;
			for (int i = 0; i < respelled.length; i++) {
				if (respelling.written().equals(respelled[i])) {
					respelled[i] = respelling.raw();
					changed = true;
				}
			}
			return changed ? respelled : null;
		} else {
			return null;
		}
	}

	/**
	 * Rewrites the references body of the respelled owner to carry the raw value in the named reference attributes
	 * where it carries the written one.
	 *
	 * @return TRUE when the body was rewritten
	 */
	private static boolean stampReferenceBody(
		long catalogVersion,
		@Nonnull OffsetIndexStoragePartPersistenceService storage,
		@Nonnull Set<String> attributeNames,
		@Nonnull Respelling respelling,
		@Nonnull Set<String> stamped
	) {
		final EntitySchema schema = Objects.requireNonNull(
			storage.getStoragePart(catalogVersion, 1, EntitySchemaStoragePart.class)
		).entitySchema();
		return EntitySchemaContext.executeWithSchemaContext(
			schema,
			() -> {
				final ReferencesStoragePart body =
					storage.getStoragePart(catalogVersion, respelling.owner(), ReferencesStoragePart.class);
				if (body == null) {
					return false;
				}
				final Reference[] references = body.getReferences().clone();
				boolean bodyChanged = false;
				for (int i = 0; i < references.length; i++) {
					final Reference reference = references[i];
					final Map<AttributeKey, AttributeValue> attributes = new LinkedHashMap<>(4);
					boolean referenceChanged = false;
					for (final Object candidate : reference.getAttributeValues()) {
						final AttributeValue attribute = (AttributeValue) candidate;
						final Serializable respelled = attributeNames.contains(attribute.key().attributeName()) ?
							respell(attribute.value(), respelling) : null;
						attributes.put(
							attribute.key(), respelled == null ? attribute : new AttributeValue(attribute, respelled)
						);
						if (respelled != null) {
							stamped.add("REFERENCE_BODY " + attribute.key().attributeName());
							referenceChanged = true;
						}
					}
					if (referenceChanged) {
						references[i] = new Reference(
							schema, reference.getReferenceSchemaOrThrow(), reference.version(),
							reference.getReferenceKey(), reference.getGroup().orElse(null), attributes,
							reference.dropped()
						);
						bodyChanged = true;
					}
				}
				if (bodyChanged) {
					storage.putStoragePart(
						catalogVersion,
						new ReferencesStoragePart(
							respelling.owner(), body.getLastUsedPrimaryKey(), references, -1
						)
					);
				}
				return bodyChanged;
			}
		);
	}

	/**
	 * Returns the primary keys of every entity index a collection header lists, its global index included.
	 */
	@Nonnull
	private static List<Integer> listedIndexes(@Nonnull EntityCollectionFileHeader header) {
		final List<Integer> indexes = new ArrayList<>(header.usedEntityIndexPrimaryKeys());
		if (header.globalEntityIndexPrimaryKey() != null && !indexes.contains(header.globalEntityIndexPrimaryKey())) {
			indexes.add(header.globalEntityIndexPrimaryKey());
		}
		return indexes;
	}

	/**
	 * Defines the catalog in a fresh engine, writes `writer`'s schema and data in warm-up, takes the catalog live,
	 * runs `afterLive` when given and shuts the engine down.
	 */
	private void writeCatalog(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull Consumer<EvitaSessionContract> writer
	) {
		writeCatalog(configuration, writer, null);
	}

	/**
	 * Defines the catalog in a fresh engine, writes `writer`'s schema and data in warm-up, takes the catalog live,
	 * runs `afterLive` when given and shuts the engine down.
	 */
	private void writeCatalog(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull Consumer<EvitaSessionContract> writer,
		@Nullable Consumer<EvitaSessionContract> afterLive
	) {
		final Evita engine = start(configuration);
		engine.defineCatalog(CATALOG).updateViaNewSession(engine);
		engine.updateCatalog(CATALOG, writer);
		engine.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		if (afterLive != null) {
			engine.updateCatalog(CATALOG, afterLive);
		}
		engine.close();
	}

	/**
	 * Builds the configuration of an engine over the directories.
	 */
	@Nonnull
	private EvitaConfiguration configurationOf(@Nonnull TestPaths paths) {
		return newTestEvitaConfigurationBuilder(paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * Starts an engine and waits until it has loaded every catalog. The engine is closed after the test.
	 */
	@Nonnull
	private Evita start(@Nonnull EvitaConfiguration configuration) {
		if (this.evita != null && this.evita.isActive()) {
			fail("The engine of the test must be closed before another one is started.");
		}
		final Evita started = new Evita(configuration);
		this.evita = started;
		started.waitUntilFullyInitialized();
		return started;
	}

	/**
	 * Allocates fresh test paths, removed after the test.
	 */
	@Nonnull
	private TestPaths allocatePaths(@Nonnull String label) {
		final TestPaths paths = createTestPaths("Migration_2026_3_OutOfWindowTemporal_" + label);
		this.allocatedPaths.add(paths);
		return paths;
	}

}
