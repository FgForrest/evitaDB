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

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityTypeClassifierResolver;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that {@link OwnerUniqueIndex} and {@link GlobalUniqueIndex} key every value exactly as the filter index does -
 * through `FilterIndex#getNormalizer` at the attribute's indexed decimal places - on every entry point: registration,
 * removal, lookup and the restore of both persisted shapes. It also pins what the storage parts carry (a value of the
 * declared type naming the key, so the inline serializers can read it back) and that a persisted pair of values naming
 * one key refuses to load, naming both owners.
 *
 * Every {@link KeyCase} offers two spellings of each of its values that the filter index treats as one value: the one
 * written ({@link KeyCase#value(int)}) and the other one ({@link KeyCase#spelling(int)}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Standalone unique index keyed like the filter index")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class UniqueIndexKeySpaceTest {
	private static final String PRODUCT = "product";
	private static final String CATEGORY = "category";
	private static final AttributeIndexKey OWNER_KEY = new AttributeIndexKey(null, "code", null);
	private static final AttributeKey GLOBAL_KEY = new AttributeKey("code");
	/**
	 * More values than one 256-entry leaf holds, so the tree persists in the `PAGED` shape.
	 */
	private static final int PAGED_COUNT = 300;
	/**
	 * Translates the two entity types the global index tests use.
	 */
	private static final EntityTypeClassifierResolver RESOLVER = new EntityTypeClassifierResolver() {
		@Override
		public int toEntityTypePrimaryKey(@Nonnull String entityType) {
			return PRODUCT.equals(entityType) ? 1 : 2;
		}

		@Nonnull
		@Override
		public String toEntityTypeName(int entityTypePrimaryKey) {
			return entityTypePrimaryKey == 1 ? PRODUCT : CATEGORY;
		}
	};

	/**
	 * Returns the single storage part of type `partType` among `parts`.
	 */
	@Nonnull
	private static <T extends StoragePart> T single(@Nonnull List<StoragePart> parts, @Nonnull Class<T> partType) {
		final List<T> matching = parts.stream().filter(partType::isInstance).map(partType::cast).toList();
		assertEquals(1, matching.size(), "exactly one " + partType.getSimpleName());
		return matching.get(0);
	}

	/**
	 * Drains the parts an index emitted into `sink`.
	 */
	@Nonnull
	private static List<StoragePart> drain(@Nonnull TrappedChanges sink) {
		final List<StoragePart> parts = new ArrayList<>();
		final Iterator<StoragePart> it = sink.getTrappedChangesIterator();
		while (it.hasNext()) {
			parts.add(it.next());
		}
		return parts;
	}

	/**
	 * Asserts that every persisted value is a value of the declared type, which the inline serializers read every
	 * value back as.
	 */
	private static void assertDeclaredType(@Nonnull KeyCase keyCase, @Nonnull Serializable[] values) {
		for (Serializable value : values) {
			assertInstanceOf(keyCase.type(), value, "persisted " + value);
		}
	}

	@Nested
	@DisplayName("Owner unique index")
	class OwnerUniqueIndexKeySpaceTest {

		/**
		 * Creates an empty owner unique index over `type` keyed at the case's indexed decimal places.
		 */
		@Nonnull
		private OwnerUniqueIndex createIndex(@Nonnull KeyCase keyCase, @Nonnull Class<? extends Serializable> type) {
			return new OwnerUniqueIndex(PRODUCT, OWNER_KEY, type, keyCase.indexedDecimalPlaces());
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(KeyCase.class)
		@DisplayName("Should find, refuse and release a value by the other spelling")
		void shouldFindRefuseAndReleaseValueByOtherSpelling(@Nonnull KeyCase keyCase) {
			final OwnerUniqueIndex index = createIndex(keyCase, keyCase.type());
			index.registerUniqueKey(keyCase.value(1), 1);
			index.registerUniqueKey(keyCase.value(2), 2);

			assertEquals(1, index.getRecordIdByUniqueValue(keyCase.spelling(1)));
			assertEquals(2, index.getRecordIdByUniqueValue(keyCase.spelling(2)));
			final UniqueValueViolationException violation = assertThrows(
				UniqueValueViolationException.class, () -> index.registerUniqueKey(keyCase.spelling(1), 3)
			);
			assertInstanceOf(keyCase.type(), violation.getValue(), "the violation names a value of the attribute type");
			assertEquals(1, index.unregisterUniqueKey(keyCase.spelling(1), 1));
			assertNull(index.getRecordIdByUniqueValue(keyCase.value(1)));
			index.registerUniqueKey(keyCase.spelling(1), 3);
			assertEquals(3, index.getRecordIdByUniqueValue(keyCase.value(1)));
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(KeyCase.class)
		@DisplayName("Should fold two spellings of one value in one array onto one key")
		void shouldFoldTwoSpellingsInOneArray(@Nonnull KeyCase keyCase) {
			final OwnerUniqueIndex index = createIndex(keyCase, keyCase.arrayType());
			index.registerUniqueKey(keyCase.array(keyCase.value(1), keyCase.spelling(1), keyCase.value(2)), 1);

			assertEquals(2, index.getDistinctValueCount());
			assertEquals(1, index.getRecordIdByUniqueValue(keyCase.spelling(2)));
			assertThrows(
				UniqueValueViolationException.class,
				() -> index.registerUniqueKey(keyCase.array(keyCase.spelling(2)), 2)
			);
			index.unregisterUniqueKey(keyCase.array(keyCase.spelling(1), keyCase.spelling(2), keyCase.value(1)), 1);
			assertTrue(index.isEmpty());
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(KeyCase.class)
		@DisplayName("Should persist values of the declared type and find them by the other spelling after a reload")
		@Tag(STORAGE)
		void shouldPersistDeclaredTypeAndReloadSingle(@Nonnull KeyCase keyCase) {
			final OwnerUniqueIndex index = createIndex(keyCase, keyCase.type());
			index.registerUniqueKey(keyCase.spelling(1), 1);
			index.registerUniqueKey(keyCase.value(2), 2);

			final TrappedChanges sink = new TrappedChanges();
			index.appendStorageParts(1, sink);
			final UniqueIndexStoragePart part = single(drain(sink), UniqueIndexStoragePart.class);
			final Serializable[] values = Objects.requireNonNull(part.getValues());
			assertDeclaredType(keyCase, values);

			final OwnerUniqueIndex reloaded = new OwnerUniqueIndex(
				PRODUCT, OWNER_KEY, part.getType(), keyCase.indexedDecimalPlaces(), values,
				Objects.requireNonNull(part.getRecordIds())
			);
			assertEquals(1, reloaded.getRecordIdByUniqueValue(keyCase.value(1)));
			assertEquals(2, reloaded.getRecordIdByUniqueValue(keyCase.spelling(2)));
			assertThrows(UniqueValueViolationException.class, () -> reloaded.registerUniqueKey(keyCase.spelling(2), 3));
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(KeyCase.class)
		@DisplayName("Should find every value by the other spelling after a paged reload")
		@Tag(STORAGE)
		void shouldReloadPagedIndex(@Nonnull KeyCase keyCase) {
			Assumptions.assumeTrue(keyCase.distinctValueCount() > PAGED_COUNT, "The type has too few values to page.");
			final OwnerUniqueIndex index = createIndex(keyCase, keyCase.type());
			for (int i = 1; i <= PAGED_COUNT; i++) {
				index.registerUniqueKey(keyCase.value(i), i);
			}
			assertTrue(index.isPaged());

			final TrappedChanges sink = new TrappedChanges();
			index.appendStorageParts(1, sink);
			final List<StoragePart> parts = drain(sink);
			final UniqueIndexStoragePart root = single(parts, UniqueIndexStoragePart.class);
			final Map<Integer, UniqueIndexLeafPagePart> leaves = new HashMap<>();
			parts.stream()
				.filter(UniqueIndexLeafPagePart.class::isInstance)
				.map(UniqueIndexLeafPagePart.class::cast)
				.forEach(leaf -> leaves.put(leaf.getPageSequence(), leaf));
			final int[] pageSequences = root.getLeafPageSequences();
			final Serializable[][] values = new Serializable[pageSequences.length][];
			final int[][] recordIds = new int[pageSequences.length][];
			for (int i = 0; i < pageSequences.length; i++) {
				values[i] = leaves.get(pageSequences[i]).getValues();
				recordIds[i] = leaves.get(pageSequences[i]).getRecordIds();
				assertDeclaredType(keyCase, values[i]);
			}

			final OwnerUniqueIndex reloaded = OwnerUniqueIndex.fromPersistedPages(
				PRODUCT, OWNER_KEY, keyCase.type(), keyCase.indexedDecimalPlaces(), pageSequences, values, recordIds,
				root.getHighWaterPageSequence()
			);
			assertTrue(reloaded.isPaged());
			for (int i = 1; i <= PAGED_COUNT; i++) {
				assertEquals(i, reloaded.getRecordIdByUniqueValue(keyCase.spelling(i)), "value " + i);
			}
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(KeyCase.class)
		@DisplayName("Should refuse to load an inline part holding two spellings of one value, naming both records")
		@Tag(STORAGE)
		void shouldRefuseLoadingTwoSpellingsOfOneValue(@Nonnull KeyCase keyCase) {
			Assumptions.assumeTrue(keyCase.hasDistinctSpelling(), "The type has a single spelling per value.");
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> new OwnerUniqueIndex(
					PRODUCT, OWNER_KEY, keyCase.type(), keyCase.indexedDecimalPlaces(),
					new Serializable[]{keyCase.value(2), keyCase.value(1), keyCase.spelling(1)}, new int[]{8, 5, 7}
				)
			);
			assertTrue(ex.getMessage().contains("`code`"), ex.getMessage());
			assertTrue(ex.getMessage().contains("record `5`") && ex.getMessage().contains("record `7`"), ex.getMessage());
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(KeyCase.class)
		@DisplayName("Should refuse to load a leaf page holding two spellings of one value, naming both records")
		@Tag(STORAGE)
		void shouldRefuseLoadingPageWithTwoSpellingsOfOneValue(@Nonnull KeyCase keyCase) {
			Assumptions.assumeTrue(keyCase.hasDistinctSpelling(), "The type has a single spelling per value.");
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> OwnerUniqueIndex.fromPersistedPages(
					PRODUCT, OWNER_KEY, keyCase.type(), keyCase.indexedDecimalPlaces(), new int[]{1},
					new Serializable[][]{{keyCase.value(1), keyCase.spelling(1), keyCase.value(2)}},
					new int[][]{{5, 7, 8}}, 1
				)
			);
			assertTrue(ex.getMessage().contains("`code`"), ex.getMessage());
			assertTrue(ex.getMessage().contains("record `5`") && ex.getMessage().contains("record `7`"), ex.getMessage());
		}
	}

	@Nested
	@DisplayName("Global unique index")
	class GlobalUniqueIndexKeySpaceTest {

		/**
		 * Creates an empty global unique index over `type` keyed at the case's indexed decimal places.
		 */
		@Nonnull
		private GlobalUniqueIndex createIndex(@Nonnull KeyCase keyCase, @Nonnull Class<? extends Serializable> type) {
			return new GlobalUniqueIndex(Scope.LIVE, GLOBAL_KEY, type, keyCase.indexedDecimalPlaces());
		}

		/**
		 * Returns `type:pk` of the owner of `value`, or `null`.
		 */
		private static String ownerOf(@Nonnull GlobalUniqueIndex index, @Nonnull Serializable value) {
			final Optional<EntityReferenceWithLocale> owner = index.getEntityReferenceByUniqueValue(value, null, RESOLVER);
			return owner.map(it -> it.getType() + ":" + it.getPrimaryKey()).orElse(null);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(KeyCase.class)
		@DisplayName("Should find, refuse across entity types and release a value by the other spelling")
		void shouldFindRefuseAndReleaseValueByOtherSpelling(@Nonnull KeyCase keyCase) {
			final GlobalUniqueIndex index = createIndex(keyCase, keyCase.type());
			index.registerUniqueKey(keyCase.value(1), PRODUCT, null, 1, RESOLVER);
			index.registerUniqueKey(keyCase.value(2), PRODUCT, null, 2, RESOLVER);

			assertEquals(PRODUCT + ":1", ownerOf(index, keyCase.spelling(1)));
			assertEquals(PRODUCT + ":2", ownerOf(index, keyCase.spelling(2)));
			final UniqueValueViolationException violation = assertThrows(
				UniqueValueViolationException.class,
				() -> index.registerUniqueKey(keyCase.spelling(1), CATEGORY, null, 1, RESOLVER)
			);
			assertInstanceOf(keyCase.type(), violation.getValue(), "the violation names a value of the attribute type");
			index.unregisterUniqueKey(keyCase.spelling(1), PRODUCT, null, 1, RESOLVER);
			assertNull(ownerOf(index, keyCase.value(1)));
			index.registerUniqueKey(keyCase.spelling(1), CATEGORY, null, 1, RESOLVER);
			assertEquals(CATEGORY + ":1", ownerOf(index, keyCase.value(1)));
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(KeyCase.class)
		@DisplayName("Should fold two spellings of one value in one array onto one key")
		void shouldFoldTwoSpellingsInOneArray(@Nonnull KeyCase keyCase) {
			final GlobalUniqueIndex index = createIndex(keyCase, keyCase.arrayType());
			index.registerUniqueKey(
				keyCase.array(keyCase.value(1), keyCase.spelling(1), keyCase.value(2)), PRODUCT, null, 1, RESOLVER
			);

			assertEquals(2, index.size());
			assertEquals(PRODUCT + ":1", ownerOf(index, keyCase.spelling(2)));
			assertThrows(
				UniqueValueViolationException.class,
				() -> index.registerUniqueKey(keyCase.array(keyCase.spelling(2)), CATEGORY, null, 2, RESOLVER)
			);
			index.unregisterUniqueKey(
				keyCase.array(keyCase.spelling(1), keyCase.spelling(2), keyCase.value(1)), PRODUCT, null, 1, RESOLVER
			);
			assertTrue(index.isEmpty());
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(KeyCase.class)
		@DisplayName("Should persist values of the declared type and find them by the other spelling after a reload")
		@Tag(STORAGE)
		void shouldPersistDeclaredTypeAndReloadSingle(@Nonnull KeyCase keyCase) {
			final GlobalUniqueIndex index = createIndex(keyCase, keyCase.type());
			index.registerUniqueKey(keyCase.spelling(1), PRODUCT, null, 1, RESOLVER);
			index.registerUniqueKey(keyCase.value(2), CATEGORY, null, 2, RESOLVER);

			final TrappedChanges sink = new TrappedChanges();
			index.appendStorageParts(GLOBAL_KEY, sink);
			final GlobalUniqueIndexStoragePart part = single(drain(sink), GlobalUniqueIndexStoragePart.class);
			final Serializable[] values = Objects.requireNonNull(part.getValues());
			assertDeclaredType(keyCase, values);

			final GlobalUniqueIndex reloaded = new GlobalUniqueIndex(
				Scope.LIVE, GLOBAL_KEY, part.getType(), keyCase.indexedDecimalPlaces(), values,
				Objects.requireNonNull(part.getPayloads()), part.getLocaleIndex()
			);
			assertEquals(PRODUCT + ":1", ownerOf(reloaded, keyCase.value(1)));
			assertEquals(CATEGORY + ":2", ownerOf(reloaded, keyCase.spelling(2)));
			assertThrows(
				UniqueValueViolationException.class,
				() -> reloaded.registerUniqueKey(keyCase.spelling(2), PRODUCT, null, 3, RESOLVER)
			);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(KeyCase.class)
		@DisplayName("Should find every value by the other spelling after a paged reload")
		@Tag(STORAGE)
		void shouldReloadPagedIndex(@Nonnull KeyCase keyCase) {
			Assumptions.assumeTrue(keyCase.distinctValueCount() > PAGED_COUNT, "The type has too few values to page.");
			final GlobalUniqueIndex index = createIndex(keyCase, keyCase.type());
			for (int i = 1; i <= PAGED_COUNT; i++) {
				index.registerUniqueKey(keyCase.value(i), PRODUCT, null, i, RESOLVER);
			}
			assertTrue(index.isPaged());

			final TrappedChanges sink = new TrappedChanges();
			index.appendStorageParts(GLOBAL_KEY, sink);
			final List<StoragePart> parts = drain(sink);
			final GlobalUniqueIndexStoragePart root = single(parts, GlobalUniqueIndexStoragePart.class);
			final Map<Integer, GlobalUniqueIndexLeafPagePart> leaves = new HashMap<>();
			parts.stream()
				.filter(GlobalUniqueIndexLeafPagePart.class::isInstance)
				.map(GlobalUniqueIndexLeafPagePart.class::cast)
				.forEach(leaf -> leaves.put(leaf.getPageSequence(), leaf));
			final int[] pageSequences = root.getLeafPageSequences();
			final Serializable[][] values = new Serializable[pageSequences.length][];
			final long[][] payloads = new long[pageSequences.length][];
			for (int i = 0; i < pageSequences.length; i++) {
				values[i] = leaves.get(pageSequences[i]).getValues();
				payloads[i] = leaves.get(pageSequences[i]).getPayloads();
				assertDeclaredType(keyCase, values[i]);
			}

			final GlobalUniqueIndex reloaded = GlobalUniqueIndex.fromPersistedPages(
				Scope.LIVE, GLOBAL_KEY, keyCase.type(), keyCase.indexedDecimalPlaces(), pageSequences, values, payloads,
				root.getHighWaterPageSequence(), root.getLocaleIndex()
			);
			assertTrue(reloaded.isPaged());
			for (int i = 1; i <= PAGED_COUNT; i++) {
				assertEquals(PRODUCT + ":" + i, ownerOf(reloaded, keyCase.spelling(i)), "value " + i);
			}
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(KeyCase.class)
		@DisplayName("Should refuse to load an inline part holding two spellings of one value, naming both owners")
		@Tag(STORAGE)
		void shouldRefuseLoadingTwoSpellingsOfOneValue(@Nonnull KeyCase keyCase) {
			Assumptions.assumeTrue(keyCase.hasDistinctSpelling(), "The type has a single spelling per value.");
			final GlobalUniqueIndex source = createIndex(keyCase, keyCase.type());
			source.registerUniqueKey(keyCase.value(1), PRODUCT, null, 5, RESOLVER);
			source.registerUniqueKey(keyCase.value(2), CATEGORY, null, 7, RESOLVER);
			final long productPayload = payloadOf(keyCase, source, keyCase.value(1));
			final long categoryPayload = payloadOf(keyCase, source, keyCase.value(2));

			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> new GlobalUniqueIndex(
					Scope.LIVE, GLOBAL_KEY, keyCase.type(), keyCase.indexedDecimalPlaces(),
					new Serializable[]{keyCase.value(1), keyCase.spelling(1)}, new long[]{productPayload, categoryPayload},
					Map.of()
				)
			);
			assertTrue(ex.getMessage().contains("`code`"), ex.getMessage());
			assertTrue(ex.getMessage().contains("entity 5 ") && ex.getMessage().contains("entity 7 "), ex.getMessage());
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(KeyCase.class)
		@DisplayName("Should refuse to load a leaf page holding two spellings of one value, naming both owners")
		@Tag(STORAGE)
		void shouldRefuseLoadingPageWithTwoSpellingsOfOneValue(@Nonnull KeyCase keyCase) {
			Assumptions.assumeTrue(keyCase.hasDistinctSpelling(), "The type has a single spelling per value.");
			final GlobalUniqueIndex source = createIndex(keyCase, keyCase.type());
			source.registerUniqueKey(keyCase.value(1), PRODUCT, null, 5, RESOLVER);
			source.registerUniqueKey(keyCase.value(2), CATEGORY, null, 7, RESOLVER);
			final long productPayload = payloadOf(keyCase, source, keyCase.value(1));
			final long categoryPayload = payloadOf(keyCase, source, keyCase.value(2));

			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> GlobalUniqueIndex.fromPersistedPages(
					Scope.LIVE, GLOBAL_KEY, keyCase.type(), keyCase.indexedDecimalPlaces(), new int[]{1},
					new Serializable[][]{{keyCase.value(1), keyCase.spelling(1)}},
					new long[][]{{productPayload, categoryPayload}}, 1, Map.of()
				)
			);
			assertTrue(ex.getMessage().contains("`code`"), ex.getMessage());
			assertTrue(ex.getMessage().contains("entity 5 ") && ex.getMessage().contains("entity 7 "), ex.getMessage());
		}

		/**
		 * Returns the packed payload the index stores for `value`, read off its inline snapshot.
		 */
		private static long payloadOf(
			@Nonnull KeyCase keyCase,
			@Nonnull GlobalUniqueIndex index,
			@Nonnull Serializable value
		) {
			final GlobalUniqueIndex.InlineSnapshot snapshot = index.inlineSnapshot();
			final Function<Object, Serializable> normalizer =
				UniqueIndexBPlusTreeSupport.normalizerFor(keyCase.type(), keyCase.indexedDecimalPlaces());
			final Comparable<?> key = UniqueIndexBPlusTreeSupport.toKey(normalizer, value);
			for (int i = 0; i < snapshot.values().length; i++) {
				final Comparable<?> persisted = UniqueIndexBPlusTreeSupport.toKey(normalizer, snapshot.values()[i]);
				if (UniqueIndexBPlusTreeSupport.KEY_ORDER.compare(key, persisted) == 0) {
					return snapshot.payloads()[i];
				}
			}
			throw new AssertionError("No payload for " + value);
		}
	}

	/**
	 * One attribute type whose filter normalizer is not the identity, with two spellings of each of its values.
	 */
	enum KeyCase {
		OFFSET_DATE_TIME(OffsetDateTime.class, OffsetDateTime[].class, 0, Integer.MAX_VALUE) {
			@Nonnull
			@Override
			Serializable value(int index) {
				return OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.ofHours(1)).plusMinutes(index);
			}

			@Nonnull
			@Override
			Serializable spelling(int index) {
				return ((OffsetDateTime) value(index)).withOffsetSameInstant(ZoneOffset.ofHoursMinutes(-5, -30));
			}
		},
		LOCAL_DATE_TIME(LocalDateTime.class, LocalDateTime[].class, 0, Integer.MAX_VALUE) {
			@Nonnull
			@Override
			Serializable value(int index) {
				return LocalDateTime.of(2026, 1, 1, 10, 0, 0, 123_000_000).plusMinutes(index);
			}

			@Nonnull
			@Override
			Serializable spelling(int index) {
				return ((LocalDateTime) value(index)).plusNanos(456_789);
			}
		},
		LOCAL_TIME(LocalTime.class, LocalTime[].class, 0, Integer.MAX_VALUE) {
			@Nonnull
			@Override
			Serializable value(int index) {
				return LocalTime.of(10, 0, 0, 123_000_000).plusSeconds(index);
			}

			@Nonnull
			@Override
			Serializable spelling(int index) {
				return ((LocalTime) value(index)).plusNanos(456_789);
			}
		},
		STRING(String.class, String[].class, 0, Integer.MAX_VALUE) {
			@Nonnull
			@Override
			Serializable value(int index) {
				return Normalizer.normalize("čaj-é-" + index, Normalizer.Form.NFC);
			}

			@Nonnull
			@Override
			Serializable spelling(int index) {
				return Normalizer.normalize((String) value(index), Normalizer.Form.NFD);
			}
		},
		BIG_DECIMAL(BigDecimal.class, BigDecimal[].class, 2, Integer.MAX_VALUE) {
			@Nonnull
			@Override
			Serializable value(int index) {
				return new BigDecimal(index + ".235");
			}

			@Nonnull
			@Override
			Serializable spelling(int index) {
				return new BigDecimal(index + ".2440");
			}
		},
		BIG_DECIMAL_RANGE(BigDecimalNumberRange.class, BigDecimalNumberRange[].class, 2, Integer.MAX_VALUE) {
			@Nonnull
			@Override
			Serializable value(int index) {
				return BigDecimalNumberRange.between(new BigDecimal(index + ".234"), new BigDecimal(index + ".678"));
			}

			@Nonnull
			@Override
			Serializable spelling(int index) {
				return BigDecimalNumberRange.between(new BigDecimal(index + ".2341"), new BigDecimal(index + ".6779"));
			}
		},
		CURRENCY(Currency.class, Currency[].class, 0, Currency.getAvailableCurrencies().size()) {
			private static final List<Currency> CURRENCIES = Currency.getAvailableCurrencies()
				.stream()
				.sorted(Comparator.comparing(Currency::getCurrencyCode))
				.toList();

			@Nonnull
			@Override
			Serializable value(int index) {
				return CURRENCIES.get(index);
			}

			@Nonnull
			@Override
			Serializable spelling(int index) {
				return Currency.getInstance(CURRENCIES.get(index).getCurrencyCode());
			}
		},
		LOCALE(Locale.class, Locale[].class, 0, PlainLocales.LOCALES.size()) {
			@Nonnull
			@Override
			Serializable value(int index) {
				return PlainLocales.LOCALES.get(index);
			}

			@Nonnull
			@Override
			Serializable spelling(int index) {
				return Locale.forLanguageTag(PlainLocales.LOCALES.get(index).toLanguageTag());
			}
		};

		private final Class<? extends Serializable> type;
		private final Class<? extends Serializable> arrayType;
		private final int indexedDecimalPlaces;
		private final int distinctValueCount;

		KeyCase(
			@Nonnull Class<? extends Serializable> type,
			@Nonnull Class<? extends Serializable> arrayType,
			int indexedDecimalPlaces,
			int distinctValueCount
		) {
			this.type = type;
			this.arrayType = arrayType;
			this.indexedDecimalPlaces = indexedDecimalPlaces;
			this.distinctValueCount = distinctValueCount;
		}

		/**
		 * Returns value `index` in the written spelling.
		 */
		@Nonnull
		abstract Serializable value(int index);

		/**
		 * Returns value `index` in the other spelling the filter index treats as the same value.
		 */
		@Nonnull
		abstract Serializable spelling(int index);

		@Nonnull
		Class<? extends Serializable> type() {
			return this.type;
		}

		@Nonnull
		Class<? extends Serializable> arrayType() {
			return this.arrayType;
		}

		int indexedDecimalPlaces() {
			return this.indexedDecimalPlaces;
		}

		int distinctValueCount() {
			return this.distinctValueCount;
		}

		/**
		 * Returns whether the other spelling differs from the written one at all.
		 */
		boolean hasDistinctSpelling() {
			return !value(1).equals(spelling(1));
		}

		/**
		 * Builds an array of the attribute type out of `values`.
		 */
		@Nonnull
		Serializable array(@Nonnull Serializable... values) {
			final Object array = Array.newInstance(this.type, values.length);
			for (int i = 0; i < values.length; i++) {
				Array.set(array, i, values[i]);
			}
			return (Serializable) array;
		}
	}

	/**
	 * Available locales made of a language and an optional region, in a stable order. Kryo persists a `Locale` as its
	 * language, country and variant, so a script or an extension would not survive a reload and two locales differing
	 * only there would come back as one.
	 */
	private static final class PlainLocales {
		private static final List<Locale> LOCALES = Arrays.stream(Locale.getAvailableLocales())
			.filter(locale -> !locale.getLanguage().isEmpty())
			.filter(locale -> locale.getScript().isEmpty() && locale.getVariant().isEmpty() && !locale.hasExtensions())
			.map(Locale::toLanguageTag)
			.distinct()
			.sorted()
			.map(Locale::forLanguageTag)
			.toList();
	}

}
