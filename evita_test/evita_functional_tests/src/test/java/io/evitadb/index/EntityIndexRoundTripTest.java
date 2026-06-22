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

package io.evitadb.index;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.AttributeScope;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.EntityAttributeIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.ReferenceAttributeIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.price.PriceListAndCurrencyPriceRefIndex;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.PriceRefIndex;
import io.evitadb.index.price.PriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeCardinalityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FacetIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GroupCardinalityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HierarchyIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencyRefIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the storage-part round-trip contract of every concrete `EntityIndex` subclass —
 * `GlobalEntityIndex`, `ReducedEntityIndex`, `ReducedGroupEntityIndex` and
 * `ReferencedTypeEntityIndex`. Catches the regression class where data is persisted to disk
 * but the manifest (`EntityIndexStoragePart`) never references it, causing reload to
 * reconstruct an empty in-memory index.
 *
 * The test guarantees two complementary invariants in a single flow per subclass:
 *
 * 1. **Manifest cross-reference invariant.** Every emitted attribute / price / histogram /
 *    facet / hierarchy sub-storage-part is referenced by a matching key in the
 *    `EntityIndexStoragePart` produced by the same `getModifiedStorageParts` call. Conversely,
 *    every key present in the manifest is backed by an emitted sub-part — there are no
 *    orphan references and no silently-dropped sub-indexes.
 *
 * 2. **Round-trip stability.** Reconstructing the index from the captured storage parts (using
 *    the same deserialization constructors as the production `readEntityIndex` path in
 *    `DefaultEntityCollectionPersistenceService`) preserves PK count, language tracking and
 *    every sub-index's contents observable via public accessors. A subsequent
 *    `getModifiedStorageParts` call on the freshly-loaded copy (after `resetDirty`) must not
 *    re-emit any sub-storage-parts. If the manifest is re-emitted (which happens for
 *    `ReducedGroupEntityIndex` / `ReferencedTypeEntityIndex` because their
 *    `originalAttributeIndexes` includes CARDINALITY keys that the base-class dirty check
 *    ignores) its contents must equal the original manifest — proving that the reload didn't
 *    silently drop or invent a sub-index.
 *
 * Persistence fixture: this test does **not** instantiate a real
 * `DefaultEntityCollectionPersistenceService` (which would require an on-disk catalog directory
 * with header, offset-index, key compressor, observable output keeper, kryo factory, etc.).
 * Instead, captured storage parts are kept in an in-memory bag and the reload helpers reproduce
 * the per-sub-index constructor calls done by the production `fetch*` methods. This keeps the
 * test focused on the manifest/reload contract without coupling to the file-system layer.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("EntityIndex storage-part round-trip")
@Tag(INDEXING)
@Tag(MANAGEMENT)
@Tag(STORAGE)
class EntityIndexRoundTripTest {

	private static final String ENTITY_TYPE = "Product";
	private static final String REFERENCE_NAME = "CATEGORY";
	private static final int INDEX_PK = 7;
	private static final int GROUP_PK = 100;
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_PRIORITY = "priority";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String HISTOGRAM_NAME = "priceHistogram";

	/**
	 * Builds an entity schema mock that admits the given locales and disallows evolution. Used
	 * by language-tracking calls (`upsertLanguage`) which validate locale membership against
	 * the schema.
	 *
	 * @param allowedLocales the set of locales the schema accepts
	 * @return a mocked entity schema fixed to the supplied locales
	 */
	@Nonnull
	private static EntitySchemaContract createSchema(@Nonnull Set<Locale> allowedLocales) {
		final EntitySchemaContract schema = mock(EntitySchemaContract.class);
		when(schema.getLocales()).thenReturn(allowedLocales);
		when(schema.getEvolutionMode()).thenReturn(EnumSet.noneOf(EvolutionMode.class));
		return schema;
	}

	/**
	 * Builds a non-localized, filterable {@link AttributeSchemaContract} with the given name
	 * and value type. Centralizes the verbose `_internalBuild` call so individual tests stay
	 * focused on the round-trip assertions.
	 *
	 * @param name the attribute name
	 * @param type the attribute value type
	 * @return a fresh attribute schema
	 */
	@Nonnull
	private static AttributeSchemaContract createAttributeSchema(
		@Nonnull String name, @Nonnull Class<? extends Serializable> type
	) {
		return AttributeSchema._internalBuild(
			name,
			null,
			new Scope[]{Scope.LIVE},
			null,
			false, false, false,
			type, null
		);
	}

	/**
	 * Captures every storage part emitted by `index.getModifiedStorageParts` and slots them
	 * into a {@link CapturedStorage} bundle whose internal categorization mirrors the production
	 * reload path. The captured bundle is the test's stand-in for the on-disk offset index.
	 *
	 * @param index the entity index to flush
	 * @return a freshly populated bundle of the manifest plus every emitted sub-part
	 */
	@Nonnull
	private static CapturedStorage flush(@Nonnull EntityIndex index) {
		final TrappedChanges trappedChanges = new TrappedChanges();
		index.getModifiedStorageParts(trappedChanges);
		final CapturedStorage storage = new CapturedStorage();
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			storage.add(iterator.next());
		}
		return storage;
	}

	/**
	 * Asserts the manifest-vs-emitted-parts cross-reference invariant. For each kind of sub-index
	 * advertised by `EntityIndexStoragePart` the helper verifies bidirectional containment: every
	 * emitted sub-part is referenced by the manifest, and every manifest key has a matching emitted
	 * sub-part. A persisted sub-part that the manifest does not advertise is orphaned on reload.
	 *
	 * @param storage the captured storage parts plus the manifest
	 */
	private static void assertManifestReferencesAllSubParts(@Nonnull CapturedStorage storage) {
		final EntityIndexStoragePart manifest = storage.requireManifest();

		// reconstruct the set of attribute storage keys that the emitted attribute sub-parts
		// represent; these MUST equal the manifest's attributeIndexes set
		final Set<AttributeIndexStorageKey> emittedAttributeKeys = new HashSet<>(16);
		for (final AttributeIndexStoragePart attrPart : storage.attributeParts) {
			emittedAttributeKeys.add(
				new AttributeIndexStorageKey(
					manifest.getEntityIndexKey(),
					attrPart.getIndexType(),
					attrPart.getAttributeIndexKey()
				)
			);
		}
		// attribute cardinality parts also live in the manifest's attributeIndexes set with
		// AttributeIndexType.CARDINALITY — fold them in before comparing
		for (final AttributeCardinalityIndexStoragePart cardPart : storage.attributeCardinalityParts) {
			emittedAttributeKeys.add(
				new AttributeIndexStorageKey(
					manifest.getEntityIndexKey(),
					AttributeIndexStoragePart.AttributeIndexType.CARDINALITY,
					cardPart.getAttributeIndexKey()
				)
			);
		}
		assertEquals(
			emittedAttributeKeys, manifest.getAttributeIndexes(),
			"Manifest attributeIndexes must equal the set of keys for emitted attribute sub-parts"
		);

		// reconstruct expected histogram storage keys from emitted histogram parts and compare
		final Set<HistogramIndexStorageKey> emittedHistogramKeys = new HashSet<>(8);
		for (final HistogramIndexStoragePart histPart : storage.histogramParts) {
			emittedHistogramKeys.add(
				new HistogramIndexStorageKey(
					manifest.getEntityIndexKey(), histPart.getHistogramName(), histPart.getLocale()
				)
			);
		}
		assertEquals(
			emittedHistogramKeys, manifest.getHistogramIndexes(),
			"Manifest histogramIndexes must equal the set of keys for emitted histogram sub-parts"
		);

		// price index keys: manifest set must equal the set of priceIndexKey on emitted price parts
		final Set<PriceIndexKey> emittedPriceKeys = new HashSet<>(8);
		for (final StoragePart pricePart : storage.priceParts) {
			if (pricePart instanceof PriceListAndCurrencySuperIndexStoragePart superPart) {
				emittedPriceKeys.add(superPart.getPriceIndexKey());
			} else if (pricePart instanceof PriceListAndCurrencyRefIndexStoragePart refPart) {
				emittedPriceKeys.add(refPart.getPriceIndexKey());
			}
		}
		assertEquals(
			emittedPriceKeys, manifest.getPriceIndexes(),
			"Manifest priceIndexes must equal the set of keys for emitted price sub-parts"
		);

		// facet reference names: manifest set must equal the set of referenceName on emitted facet parts
		final Set<String> emittedFacetNames = new HashSet<>(4);
		for (final FacetIndexStoragePart facetPart : storage.facetParts) {
			emittedFacetNames.add(facetPart.getReferenceName());
		}
		assertEquals(
			emittedFacetNames, manifest.getFacetIndexes(),
			"Manifest facetIndexes must equal the set of referenceNames for emitted facet sub-parts"
		);

		// hierarchy: manifest flag must be true iff a hierarchy sub-part was emitted
		assertEquals(
			storage.hierarchyPart != null, manifest.isHierarchyIndex(),
			"Manifest hierarchyIndex flag must agree with presence of a HierarchyIndexStoragePart"
		);
	}

	/**
	 * Re-flushes a freshly-reloaded index after clearing its dirty flags and asserts that the
	 * second manifest (if any) is structurally equal to the first — same attribute index keys,
	 * same price index keys, same histogram keys, same facet reference names, same hierarchy
	 * flag, same primary key, same entity-id bitmap. Sub-storage-parts must not be re-emitted
	 * since their dirty flags start clean after reload.
	 *
	 * The assertion is intentionally relaxed from "zero parts" because some subclasses
	 * (`ReducedGroupEntityIndex`, `ReferencedTypeEntityIndex`) maintain `originalAttributeIndexes`
	 * with cardinality keys that the base-class dirty check doesn't account for, so a second flush
	 * legitimately re-emits the manifest with identical content. Re-emission must produce the same
	 * set of keys — a silent sub-index drop during reload would show up as a missing key here.
	 *
	 * @param reloaded         the index just loaded from storage parts
	 * @param originalManifest the manifest emitted by the original flush
	 */
	private static void assertManifestStableAfterReload(
		@Nonnull EntityIndex reloaded, @Nonnull EntityIndexStoragePart originalManifest
	) {
		reloaded.resetDirty();
		final TrappedChanges trappedChanges = new TrappedChanges();
		reloaded.getModifiedStorageParts(trappedChanges);
		// at most the manifest itself may be re-emitted; sub-parts must remain clean
		EntityIndexStoragePart secondManifest = null;
		int subPartCount = 0;
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			final StoragePart part = iterator.next();
			if (part instanceof EntityIndexStoragePart manifestPart) {
				secondManifest = manifestPart;
			} else {
				subPartCount++;
			}
		}
		assertEquals(
			0, subPartCount,
			"Reloaded index with cleared dirty flags must not re-emit any sub-storage-parts"
		);
		if (secondManifest != null) {
			// content stability: every set in the manifest must match the original — a silent
			// sub-index drop during reload would surface as a missing key here
			assertEquals(
				originalManifest.getAttributeIndexes(), secondManifest.getAttributeIndexes(),
				"Re-emitted manifest attributeIndexes must match the original"
			);
			assertEquals(
				originalManifest.getHistogramIndexes(), secondManifest.getHistogramIndexes(),
				"Re-emitted manifest histogramIndexes must match the original"
			);
			assertEquals(
				originalManifest.getPriceIndexes(), secondManifest.getPriceIndexes(),
				"Re-emitted manifest priceIndexes must match the original"
			);
			assertEquals(
				originalManifest.getFacetIndexes(), secondManifest.getFacetIndexes(),
				"Re-emitted manifest facetIndexes must match the original"
			);
			assertEquals(
				originalManifest.isHierarchyIndex(), secondManifest.isHierarchyIndex(),
				"Re-emitted manifest hierarchyIndex flag must match the original"
			);
			assertEquals(
				originalManifest.getPrimaryKey(), secondManifest.getPrimaryKey(),
				"Re-emitted manifest primary key must match the original"
			);
		}
	}

	/**
	 * Reconstructs an [AttributeIndex] from the captured storage parts using the same
	 * constructor wiring as the production `fetchUniqueIndex` / `fetchFilterIndex` /
	 * `fetchSortIndex` / `fetchChainIndex` helpers in `DefaultEntityCollectionPersistenceService`.
	 * The CARDINALITY parts are intentionally ignored here because they live outside [AttributeIndex]
	 * — reduced/referenced indexes consume them via a separate map.
	 *
	 * The `referenceScoped` flag pins the structural subclass explicitly. It is required because
	 * [io.evitadb.index.ReferencedTypeEntityIndex] passes a `null` representative key yet is
	 * reference-scoped — the scope cannot be inferred from the key alone.
	 *
	 * @param storage         the captured storage parts
	 * @param entityType      the owning entity type (used by [UniqueIndex])
	 * @param referenceKey    the representative reference key, or `null`
	 * @param referenceScoped `true` when the parent index is reference-scoped (any subclass of
	 *                        [io.evitadb.index.AbstractReducedEntityIndex] or
	 *                        [io.evitadb.index.ReferencedTypeEntityIndex])
	 * @return a populated [AttributeIndex] of the appropriate subclass
	 */
	@Nonnull
	private static AttributeIndex reloadAttributeIndex(
		@Nonnull CapturedStorage storage,
		@Nonnull String entityType,
		@Nullable RepresentativeReferenceKey referenceKey,
		boolean referenceScoped
	) {
		final Map<AttributeIndexKey, UniqueIndex> uniqueIndexes = new HashMap<>(8);
		final Map<AttributeIndexKey, FilterIndex> filterIndexes = new HashMap<>(8);
		final Map<AttributeIndexKey, SortIndex> sortIndexes = new HashMap<>(8);
		final Map<AttributeIndexKey, ChainIndex> chainIndexes = new HashMap<>(8);
		for (final AttributeIndexStoragePart part : storage.attributeParts) {
			final AttributeIndexKey attrKey = part.getAttributeIndexKey();
			if (part instanceof UniqueIndexStoragePart uniquePart) {
				uniqueIndexes.put(
					attrKey,
					new UniqueIndex(
						entityType, attrKey, uniquePart.getType(),
						uniquePart.getUniqueValueToRecordId(),
						uniquePart.getRecordIds()
					)
				);
			} else if (part instanceof FilterIndexStoragePart filterPart) {
				filterIndexes.put(
					attrKey,
					new FilterIndex(
						attrKey,
						filterPart.getHistogramPoints(),
						filterPart.getRangeIndex(),
						filterPart.getAttributeType()
					)
				);
			} else if (part instanceof SortIndexStoragePart sortPart) {
				sortIndexes.put(
					attrKey,
					new SortIndex(
						sortPart.getComparatorBase(),
						referenceKey,
						attrKey,
						sortPart.getSortedRecords(),
						sortPart.getSortedRecordsValues(),
						sortPart.getValueCardinalities()
					)
				);
			} else if (part instanceof ChainIndexStoragePart chainPart) {
				chainIndexes.put(
					attrKey,
					new ChainIndex(
						referenceKey,
						attrKey,
						chainPart.getChains(),
						chainPart.getElementStates()
					)
				);
			}
		}
		return referenceScoped
			? new ReferenceAttributeIndex(
				entityType, referenceKey, uniqueIndexes, filterIndexes, sortIndexes, chainIndexes
			)
			: new EntityAttributeIndex(
				entityType, uniqueIndexes, filterIndexes, sortIndexes, chainIndexes
			);
	}

	/**
	 * Reconstructs the entity-id-by-language map from the manifest. The map is bitmap-typed and
	 * needs to be wrapped in fresh transactional bitmaps to match the deserialization constructor's
	 * expected type.
	 *
	 * @param manifest the captured `EntityIndexStoragePart`
	 * @return a map suitable for passing into the deserialization constructor
	 */
	@Nonnull
	private static Map<Locale, TransactionalBitmap> reloadEntityIdsByLanguage(
		@Nonnull EntityIndexStoragePart manifest
	) {
		// LinkedHashMap preserves deterministic iteration order for downstream comparisons
		final Map<Locale, TransactionalBitmap> reloaded = new LinkedHashMap<>(4);
		for (final Map.Entry<Locale, TransactionalBitmap> entry
			: manifest.getEntityIdsByLanguage().entrySet()) {
			reloaded.put(entry.getKey(), new TransactionalBitmap(entry.getValue()));
		}
		return reloaded;
	}

	/**
	 * Extracts attribute cardinality indexes from the captured storage parts. These are emitted
	 * by both `ReducedGroupEntityIndex` and `ReferencedTypeEntityIndex` to track filter-attribute
	 * cardinality. Each cardinality index is re-built into a fresh `AttributeCardinalityIndex`
	 * instance — reusing the live instance from the original index would carry over its dirty
	 * flag, which in real persistence is reset by the kryo deserialization round-trip.
	 *
	 * @param storage the captured storage parts
	 * @return map keyed by attribute index key with freshly-constructed (clean) cardinality indexes
	 */
	@Nonnull
	private static Map<AttributeIndexKey, AttributeCardinalityIndex> reloadCardinalityIndexes(
		@Nonnull CapturedStorage storage
	) {
		final Map<AttributeIndexKey, AttributeCardinalityIndex> result = new HashMap<>(4);
		for (final AttributeCardinalityIndexStoragePart part : storage.attributeCardinalityParts) {
			final AttributeCardinalityIndex original = part.getCardinalityIndex();
			// reconstruct via the (Class, Map) constructor so the dirty flag starts clean —
			// mirrors what kryo deserialization produces on a real reload
			result.put(
				part.getAttributeIndexKey(),
				new AttributeCardinalityIndex(original.getValueType(), original.getCardinalities())
			);
		}
		return result;
	}

	/**
	 * Reconstructs a {@link HierarchyIndex} from the captured hierarchy part if present, or returns
	 * an empty hierarchy index otherwise.
	 *
	 * @param storage the captured storage parts
	 * @return a hierarchy index mirroring the original
	 */
	@Nonnull
	private static HierarchyIndex reloadHierarchyIndex(@Nonnull CapturedStorage storage) {
		final HierarchyIndexStoragePart part = storage.hierarchyPart;
		if (part == null) {
			return new HierarchyIndex();
		}
		return new HierarchyIndex(
			part.getRoots(), part.getLevelIndex(), part.getItemIndex(), part.getOrphans()
		);
	}

	/**
	 * Reconstructs a {@link FacetIndex} from the captured facet parts.
	 *
	 * @param storage the captured storage parts
	 * @return a facet index mirroring the original
	 */
	@Nonnull
	private static FacetIndex reloadFacetIndex(@Nonnull CapturedStorage storage) {
		if (storage.facetParts.isEmpty()) {
			return new FacetIndex();
		}
		return new FacetIndex(storage.facetParts);
	}

	/**
	 * Reconstructs histogram indexes from the captured histogram parts. Mirrors the production
	 * `fetchHistogramIndexes` logic in `DefaultEntityCollectionPersistenceService` for the
	 * non-localized case used by this test. The localized branch follows the same pattern but is
	 * not exercised here to keep fixture complexity in check.
	 *
	 * @param storage       the captured storage parts
	 * @param referenceName the reference name used by the owning index
	 * @return a map of histogram indexes keyed by histogram name
	 */
	@Nonnull
	private static Map<String, HistogramIndex> reloadHistogramIndexes(
		@Nonnull CapturedStorage storage, @Nonnull String referenceName
	) {
		if (storage.histogramParts.isEmpty()) {
			return new HashMap<>(0);
		}
		final Map<String, HistogramIndex> result = new HashMap<>(storage.histogramParts.size());
		for (final HistogramIndexStoragePart part : storage.histogramParts) {
			// the test only emits non-localized histograms; reconstruct a SimpleHistogramIndex using
			// the same constructor signature as production fetchHistogramIndexes. The embedded
			// cardinality index is re-built (rather than reused) so its dirty flag starts clean —
			// mirrors what kryo deserialization produces on a real reload.
			@SuppressWarnings("unchecked")
			final Class<? extends Serializable> valueType =
				(Class<? extends Serializable>) part.getValueType();
			final AttributeCardinalityIndex liveCardinality = part.getCardinalityIndex();
			final AttributeCardinalityIndex freshCardinality = new AttributeCardinalityIndex(
				liveCardinality.getValueType(), liveCardinality.getCardinalities()
			);
			result.put(
				part.getHistogramName(),
				new SimpleHistogramIndex(
					part.getHistogramName(),
					referenceName,
					valueType,
					new FilterIndex(
						new AttributeIndexKey(referenceName, part.getHistogramName(), null),
						part.getHistogramPoints(),
						part.getRangeIndex(),
						part.getValueType()
					),
					freshCardinality
				)
			);
		}
		return result;
	}

	@Nested
	@DisplayName("GlobalEntityIndex round-trip")
	class GlobalEntityIndexRoundTripTest {

		/**
		 * Pre-populates a `GlobalEntityIndex` with one of every sub-index type the class supports —
		 * PKs, languages, UNIQUE/FILTER/SORT/CHAIN attribute indexes, a price super-index, a
		 * hierarchy entry, and a facet entry. The returned index is fully dirty and ready for flush.
		 *
		 * @return a populated `GlobalEntityIndex`
		 */
		@Nonnull
		private static GlobalEntityIndex buildPopulatedIndex() {
			final EntityIndexKey key = new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE);
			final GlobalEntityIndex index = new GlobalEntityIndex(INDEX_PK, ENTITY_TYPE, key);
			final EntitySchemaContract schema = createSchema(Set.of(Locale.ENGLISH));
			final AttributeSchemaContract codeSchema = createAttributeSchema(ATTRIBUTE_CODE, String.class);
			final AttributeSchemaContract nameSchema = createAttributeSchema(ATTRIBUTE_NAME, String.class);
			final AttributeSchemaContract prioritySchema =
				createAttributeSchema(ATTRIBUTE_PRIORITY, Integer.class);
			final AttributeSchemaContract orderSchema =
				createAttributeSchema(ATTRIBUTE_ORDER, Predecessor.class);
			final Set<Locale> allowedLocales = Set.of(Locale.ENGLISH);

			// PKs and language tracking
			index.insertPrimaryKeyIfMissing(10);
			index.insertPrimaryKeyIfMissing(20);
			index.upsertLanguage(Locale.ENGLISH, 10, schema);

			// one of each attribute index type
			index.insertUniqueAttribute(null, codeSchema, allowedLocales, Scope.LIVE, null, "ABC", 10);
			index.insertFilterAttribute(null, nameSchema, allowedLocales, null, "Phone", 10);
			index.insertSortAttribute(null, prioritySchema, allowedLocales, null, 42, 10);
			index.insertSortAttribute(null, orderSchema, allowedLocales, null, Predecessor.HEAD, 10);

			// price super-index — GlobalEntityIndex @Delegates PriceIndexContract, so addPrice is on
			// the index itself
			index.addPrice(
				null, 10, 1,
				new PriceKey(1, "basic", Currency.getInstance("EUR")),
				PriceInnerRecordHandling.NONE, null, null,
				1000, 1210
			);

			// hierarchy
			index.addNode(10, null);
			index.addNode(20, 10);

			// facet
			index.addFacet(null, new ReferenceKey(REFERENCE_NAME, 5), GROUP_PK, 10);
			return index;
		}

		/**
		 * Reconstructs a `GlobalEntityIndex` from the captured storage parts, mirroring the
		 * production code path in `DefaultEntityCollectionPersistenceService#readEntityIndex`
		 * for the `EntityIndexType.GLOBAL` case.
		 *
		 * @param storage the captured storage parts
		 * @return a freshly-loaded `GlobalEntityIndex`
		 */
		@Nonnull
		private static GlobalEntityIndex reload(@Nonnull CapturedStorage storage) {
			final EntityIndexStoragePart manifest = storage.requireManifest();
			// GlobalEntityIndex is entity-scoped — uses EntityAttributeIndex
			final AttributeIndex attributeIndex = reloadAttributeIndex(storage, ENTITY_TYPE, null, false);
			final Map<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> priceIndexes = new HashMap<>(4);
			for (final StoragePart pricePart : storage.priceParts) {
				final PriceListAndCurrencySuperIndexStoragePart superPart =
					(PriceListAndCurrencySuperIndexStoragePart) pricePart;
				priceIndexes.put(
					superPart.getPriceIndexKey(),
					new PriceListAndCurrencyPriceSuperIndex(
						superPart.getPriceIndexKey(),
						superPart.getValidityIndex(),
						superPart.getPriceRecords()
					)
				);
			}
			return new GlobalEntityIndex(
				manifest.getPrimaryKey(),
				manifest.getEntityIndexKey(),
				manifest.getVersion(),
				manifest.getEntityIds(),
				reloadEntityIdsByLanguage(manifest),
				(EntityAttributeIndex) attributeIndex,
				new PriceSuperIndex(priceIndexes),
				reloadHierarchyIndex(storage),
				reloadFacetIndex(storage)
			);
		}

		@Test
		@DisplayName("should preserve manifest cross-references and reload all sub-indexes losslessly")
		void shouldRoundTripGlobalEntityIndex() {
			final GlobalEntityIndex original = buildPopulatedIndex();

			final CapturedStorage storage = flush(original);

			// invariant 1: the manifest references every emitted sub-part and nothing else
			assertManifestReferencesAllSubParts(storage);

			// sanity check: every sub-index category we wanted is actually present, otherwise the
			// test would silently assert nothing
			assertFalse(storage.attributeParts.isEmpty(), "Expected attribute parts to be emitted");
			assertFalse(storage.priceParts.isEmpty(), "Expected price parts to be emitted");
			assertFalse(storage.facetParts.isEmpty(), "Expected facet parts to be emitted");
			assertNotNull(storage.hierarchyPart, "Expected a hierarchy part to be emitted");
			assertTrue(storage.histogramParts.isEmpty(), "GlobalEntityIndex must not emit histogram parts");
			assertTrue(
				storage.attributeCardinalityParts.isEmpty(),
				"GlobalEntityIndex must not emit attribute cardinality parts"
			);
			assertNull(storage.groupCardinalityPart);
			assertNull(storage.referenceTypeCardinalityPart);

			// invariant 2: a reload produces an index with identical observable contents
			final GlobalEntityIndex reloaded = reload(storage);

			assertEquals(original.getAllPrimaryKeys().size(), reloaded.getAllPrimaryKeys().size());
			assertTrue(reloaded.getAllPrimaryKeys().contains(10));
			assertTrue(reloaded.getAllPrimaryKeys().contains(20));
			assertTrue(reloaded.getLanguages().contains(Locale.ENGLISH));
			// attribute sub-index counts must match
			assertEquals(original.getUniqueIndexes().size(), reloaded.getUniqueIndexes().size());
			assertEquals(original.getFilterIndexes().size(), reloaded.getFilterIndexes().size());
			assertEquals(original.getSortIndexes().size(), reloaded.getSortIndexes().size());
			assertEquals(original.getChainIndexes().size(), reloaded.getChainIndexes().size());
			// filter sentinel: filter index for "name" still has record 10 for value "Phone"
			final FilterIndex reloadedFilter = reloaded.getFilterIndex(
				new AttributeIndexKey(null, ATTRIBUTE_NAME, null)
			);
			assertNotNull(reloadedFilter, "Filter index for 'name' must survive the reload");
			assertTrue(reloadedFilter.getRecordsEqualTo("Phone").contains(10));
			// price sentinel
			assertFalse(reloaded.isPriceIndexEmpty(), "Reloaded price index must be non-empty");
			// hierarchy sentinel
			assertFalse(reloaded.isHierarchyIndexEmpty(), "Reloaded hierarchy must be non-empty");
			// facet sentinel
			assertTrue(reloaded.getFacetingEntities().containsKey(REFERENCE_NAME));

			// AttributeIndex subclass identity must survive reload: GlobalEntityIndex carries
			// an EntityAttributeIndex with ENTITY scope
			assertInstanceOf(
				EntityAttributeIndex.class, original.attributeIndex,
				"GlobalEntityIndex must construct an EntityAttributeIndex"
			);
			assertInstanceOf(
				EntityAttributeIndex.class, reloaded.attributeIndex,
				"Reloaded GlobalEntityIndex must reconstruct an EntityAttributeIndex"
			);
			assertEquals(AttributeScope.ENTITY, reloaded.attributeIndex.getScope());

			// invariant 3: re-flushing the reloaded copy produces zero parts
			assertManifestStableAfterReload(reloaded, storage.requireManifest());
		}
	}

	@Nested
	@DisplayName("ReducedEntityIndex round-trip")
	class ReducedEntityIndexRoundTripTest {

		/**
		 * Pre-populates a `ReducedEntityIndex` (REFERENCED_ENTITY type) with PKs, languages,
		 * UNIQUE/FILTER/SORT/CHAIN attributes, and a facet. Hierarchy population is intentionally
		 * skipped — `AbstractReducedEntityIndex.addNode` throws because reduced indexes never
		 * carry hierarchical state; hierarchy round-trip is covered by `GlobalEntityIndexRoundTripTest`.
		 *
		 * @return a populated `ReducedEntityIndex`
		 */
		@Nonnull
		private static ReducedEntityIndex buildPopulatedIndex() {
			final RepresentativeReferenceKey rrk =
				new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, 5));
			final EntityIndexKey key =
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, rrk);
			final ReducedEntityIndex index = new ReducedEntityIndex(INDEX_PK, ENTITY_TYPE, key);
			final EntitySchemaContract schema = createSchema(Set.of(Locale.ENGLISH));
			// AbstractReducedEntityIndex.assertPartitioningIndex requires a non-null reference
			// schema; configure FOR_FILTERING_AND_PARTITIONING so attribute/price/facet paths pass.
			// The schema name flows into AttributeIndex.createAttributeKey, so configure
			// getName() to return REFERENCE_NAME — otherwise filter keys collapse to (null, name, null)
			// and the post-reload lookup against (REFERENCE_NAME, name, null) returns null.
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			when(refSchema.getName()).thenReturn(REFERENCE_NAME);
			when(refSchema.getReferenceIndexType(ArgumentMatchers.any(Scope.class)))
				.thenReturn(ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING);
			final AttributeSchemaContract codeSchema = createAttributeSchema(ATTRIBUTE_CODE, String.class);
			final AttributeSchemaContract nameSchema = createAttributeSchema(ATTRIBUTE_NAME, String.class);
			final AttributeSchemaContract prioritySchema =
				createAttributeSchema(ATTRIBUTE_PRIORITY, Integer.class);
			final AttributeSchemaContract orderSchema =
				createAttributeSchema(ATTRIBUTE_ORDER, Predecessor.class);
			final Set<Locale> allowedLocales = Set.of(Locale.ENGLISH);

			index.insertPrimaryKeyIfMissing(11);
			index.insertPrimaryKeyIfMissing(22);
			index.upsertLanguage(Locale.ENGLISH, 11, schema);

			index.insertUniqueAttribute(
				refSchema, codeSchema, allowedLocales, Scope.LIVE, null, "ABC-R", 11
			);
			index.insertFilterAttribute(refSchema, nameSchema, allowedLocales, null, "Tablet", 11);
			index.insertSortAttribute(refSchema, prioritySchema, allowedLocales, null, 7, 11);
			index.insertSortAttribute(refSchema, orderSchema, allowedLocales, null, Predecessor.HEAD, 11);

			// price ref-index intentionally NOT populated here: PriceListAndCurrencyPriceRefIndex
			// requires a `superIndex` attachment via `attachToCatalog`, which in turn needs a real
			// `Catalog` instance with a sibling `GlobalEntityIndex` and a sibling
			// `PriceListAndCurrencyPriceSuperIndex` — too much fixture for this test. Price
			// round-trip is covered by `GlobalEntityIndexRoundTripTest`. The manifest assertions
			// here still verify that the price-index set in the manifest equals the (empty) set
			// of emitted price parts, so a regression that spuriously added price keys to the
			// manifest would still be caught.

			index.addFacet(refSchema, new ReferenceKey(REFERENCE_NAME, 9), GROUP_PK, 11);
			return index;
		}

		/**
		 * Reconstructs a `ReducedEntityIndex` from the captured storage parts. Mirrors the
		 * `EntityIndexType.REFERENCED_ENTITY` branch of `readEntityIndex`.
		 *
		 * @param storage the captured storage parts
		 * @return a freshly-loaded `ReducedEntityIndex`
		 */
		@Nonnull
		private static ReducedEntityIndex reload(@Nonnull CapturedStorage storage) {
			final EntityIndexStoragePart manifest = storage.requireManifest();
			final RepresentativeReferenceKey rrk =
				(RepresentativeReferenceKey) manifest.getEntityIndexKey().discriminator();
			// ReducedEntityIndex is reference-scoped — uses ReferenceAttributeIndex
			final AttributeIndex attributeIndex = reloadAttributeIndex(storage, ENTITY_TYPE, rrk, true);
			final Map<PriceIndexKey, PriceListAndCurrencyPriceRefIndex> priceIndexes = new HashMap<>(4);
			for (final StoragePart pricePart : storage.priceParts) {
				final PriceListAndCurrencyRefIndexStoragePart refPart =
					(PriceListAndCurrencyRefIndexStoragePart) pricePart;
				priceIndexes.put(
					refPart.getPriceIndexKey(),
					new PriceListAndCurrencyPriceRefIndex(
						manifest.getEntityIndexKey().scope(),
						refPart.getPriceIndexKey(),
						refPart.getValidityIndex(),
						refPart.getPriceIds()
					)
				);
			}
			return new ReducedEntityIndex(
				manifest.getPrimaryKey(),
				manifest.getEntityIndexKey(),
				manifest.getVersion(),
				manifest.getEntityIds(),
				reloadEntityIdsByLanguage(manifest),
				(ReferenceAttributeIndex) attributeIndex,
				new PriceRefIndex(manifest.getEntityIndexKey().scope(), priceIndexes),
				reloadHierarchyIndex(storage),
				reloadFacetIndex(storage)
			);
		}

		@Test
		@DisplayName("should preserve manifest cross-references and reload all sub-indexes losslessly")
		void shouldRoundTripReducedEntityIndex() {
			final ReducedEntityIndex original = buildPopulatedIndex();

			final CapturedStorage storage = flush(original);

			assertManifestReferencesAllSubParts(storage);

			assertFalse(storage.attributeParts.isEmpty(), "Expected attribute parts");
			assertTrue(storage.priceParts.isEmpty(), "Price parts intentionally not exercised here");
			assertFalse(storage.facetParts.isEmpty(), "Expected facet parts");
			// ReducedEntityIndex never emits hierarchy — addNode throws on the reduced subclass
			assertNull(storage.hierarchyPart, "ReducedEntityIndex must not emit a hierarchy part");
			assertTrue(storage.histogramParts.isEmpty(), "ReducedEntityIndex must not emit histogram parts");
			assertTrue(
				storage.attributeCardinalityParts.isEmpty(),
				"ReducedEntityIndex must not emit attribute cardinality parts"
			);

			final ReducedEntityIndex reloaded = reload(storage);

			assertEquals(original.getAllPrimaryKeys().size(), reloaded.getAllPrimaryKeys().size());
			assertTrue(reloaded.getAllPrimaryKeys().contains(11));
			assertTrue(reloaded.getAllPrimaryKeys().contains(22));
			assertTrue(reloaded.getLanguages().contains(Locale.ENGLISH));
			assertEquals(original.getFilterIndexes().size(), reloaded.getFilterIndexes().size());
			// filter is keyed by reference name because the reduced index's AttributeIndex has the
			// representative reference key wired in — attribute keys gain that reference name
			final FilterIndex reloadedFilter = reloaded.getFilterIndex(
				new AttributeIndexKey(REFERENCE_NAME, ATTRIBUTE_NAME, null)
			);
			assertNotNull(reloadedFilter, "Filter index for reference attribute must survive reload");
			assertTrue(reloadedFilter.getRecordsEqualTo("Tablet").contains(11));
			assertTrue(reloaded.isPriceIndexEmpty(), "Price was not populated in this fixture");
			assertTrue(reloaded.isHierarchyIndexEmpty(), "Hierarchy was not populated in this fixture");
			assertTrue(reloaded.getFacetingEntities().containsKey(REFERENCE_NAME));

			// AttributeIndex subclass identity must survive reload: ReducedEntityIndex carries
			// a ReferenceAttributeIndex with REFERENCE scope
			assertInstanceOf(
				ReferenceAttributeIndex.class, original.attributeIndex,
				"ReducedEntityIndex must construct a ReferenceAttributeIndex"
			);
			assertInstanceOf(
				ReferenceAttributeIndex.class, reloaded.attributeIndex,
				"Reloaded ReducedEntityIndex must reconstruct a ReferenceAttributeIndex"
			);
			assertEquals(AttributeScope.REFERENCE, reloaded.attributeIndex.getScope());

			assertManifestStableAfterReload(reloaded, storage.requireManifest());
		}
	}

	@Nested
	@DisplayName("ReducedGroupEntityIndex round-trip")
	class ReducedGroupEntityIndexRoundTripTest {

		/**
		 * Pre-populates a `ReducedGroupEntityIndex` with PKs (cardinality-aware), languages,
		 * filter attributes (cardinality-tracked), CARDINALITY sub-indexes, histogram values, a
		 * facet entry, and the group cardinality storage part. Hierarchy population is
		 * intentionally skipped — `AbstractReducedEntityIndex.addNode` throws because reduced
		 * indexes never carry hierarchical state; hierarchy round-trip is covered by
		 * `GlobalEntityIndexRoundTripTest`. Every kind of sub-index this subclass can hold must
		 * round-trip via the manifest.
		 *
		 * @return a populated `ReducedGroupEntityIndex`
		 */
		@Nonnull
		private static ReducedGroupEntityIndex buildPopulatedIndex() {
			final RepresentativeReferenceKey rrk =
				new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, GROUP_PK));
			final EntityIndexKey key =
				new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE, rrk);
			final ReducedGroupEntityIndex index =
				new ReducedGroupEntityIndex(INDEX_PK, ENTITY_TYPE, key);
			final EntitySchemaContract schema = createSchema(Set.of(Locale.ENGLISH));
			// the 1-arg assertPartitioningIndex (used by addFacet / addPrice) inspects
			// getReferenceIndexType — configure FOR_FILTERING_AND_PARTITIONING so the facet
			// path passes the precondition; getName() flows into AttributeIndex.createAttributeKey
			// so configure it to REFERENCE_NAME for consistency with the manifest discriminator
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			when(refSchema.getName()).thenReturn(REFERENCE_NAME);
			when(refSchema.getReferenceIndexType(ArgumentMatchers.any(Scope.class)))
				.thenReturn(ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING);
			final AttributeSchemaContract nameSchema = createAttributeSchema(ATTRIBUTE_NAME, String.class);
			final Set<Locale> allowedLocales = Set.of(Locale.ENGLISH);

			// cardinality-aware PK insertion: two refs from entity 13 to the same group, plus entity 14
			index.insertPrimaryKeyIfMissing(13, 1);
			index.insertPrimaryKeyIfMissing(13, 2);
			index.insertPrimaryKeyIfMissing(14, 3);
			index.upsertLanguage(Locale.ENGLISH, 13, schema);

			// filter attribute with cardinality tracking — drives the CARDINALITY storage part
			index.insertFilterAttribute(refSchema, nameSchema, allowedLocales, null, "Watch", 13);
			index.insertFilterAttribute(refSchema, nameSchema, allowedLocales, null, "Watch", 13);

			// histogram — must round-trip via the manifest
			index.insertHistogramValue(HISTOGRAM_NAME, null, 100, 13, Integer.class, 0);
			index.insertHistogramValue(HISTOGRAM_NAME, null, 200, 14, Integer.class, 0);

			// price ref-index intentionally NOT populated here for the same reason as in
			// ReducedEntityIndexRoundTripTest: PriceListAndCurrencyPriceRefIndex requires a
			// `superIndex` attachment via `attachToCatalog`. Price round-trip is covered by the
			// `GlobalEntityIndexRoundTripTest`.

			index.addFacet(refSchema, new ReferenceKey(REFERENCE_NAME, GROUP_PK), GROUP_PK, 13);
			return index;
		}

		/**
		 * Reconstructs a `ReducedGroupEntityIndex` from the captured storage parts, mirroring the
		 * `EntityIndexType.REFERENCED_GROUP_ENTITY` branch of `readEntityIndex`. Group cardinality
		 * data is loaded from the dedicated `GroupCardinalityIndexStoragePart`.
		 *
		 * @param storage the captured storage parts
		 * @return a freshly-loaded `ReducedGroupEntityIndex`
		 */
		@Nonnull
		private static ReducedGroupEntityIndex reload(@Nonnull CapturedStorage storage) {
			final EntityIndexStoragePart manifest = storage.requireManifest();
			final RepresentativeReferenceKey rrk =
				(RepresentativeReferenceKey) manifest.getEntityIndexKey().discriminator();
			// ReducedGroupEntityIndex is reference-scoped — uses ReferenceAttributeIndex
			final AttributeIndex attributeIndex = reloadAttributeIndex(storage, ENTITY_TYPE, rrk, true);
			final Map<PriceIndexKey, PriceListAndCurrencyPriceRefIndex> priceIndexes = new HashMap<>(4);
			for (final StoragePart pricePart : storage.priceParts) {
				final PriceListAndCurrencyRefIndexStoragePart refPart =
					(PriceListAndCurrencyRefIndexStoragePart) pricePart;
				priceIndexes.put(
					refPart.getPriceIndexKey(),
					new PriceListAndCurrencyPriceRefIndex(
						manifest.getEntityIndexKey().scope(),
						refPart.getPriceIndexKey(),
						refPart.getValidityIndex(),
						refPart.getPriceIds()
					)
				);
			}
			// the group cardinality storage part carries the cardinality bookkeeping that lets the
			// reloaded RGEI answer "how many references back this entity in this group?" queries
			final GroupCardinalityIndexStoragePart groupPart = storage.groupCardinalityPart;
			assertNotNull(groupPart, "RGEI must emit a GroupCardinalityIndexStoragePart");
			final Map<String, HistogramIndex> histogramIndexes =
				reloadHistogramIndexes(storage, rrk.referenceName());

			return new ReducedGroupEntityIndex(
				manifest.getPrimaryKey(),
				manifest.getEntityIndexKey(),
				manifest.getVersion(),
				manifest.getEntityIds(),
				reloadEntityIdsByLanguage(manifest),
				(ReferenceAttributeIndex) attributeIndex,
				new PriceRefIndex(manifest.getEntityIndexKey().scope(), priceIndexes),
				reloadHierarchyIndex(storage),
				reloadFacetIndex(storage),
				groupPart.getPkCardinalities(),
				groupPart.getReferencedPrimaryKeysIndex(),
				reloadCardinalityIndexes(storage),
				histogramIndexes
			);
		}

		@Test
		@DisplayName("should preserve manifest cross-refs and reload histogram + cardinality sub-indexes")
		void shouldRoundTripReducedGroupEntityIndex() {
			final ReducedGroupEntityIndex original = buildPopulatedIndex();

			final CapturedStorage storage = flush(original);

			assertManifestReferencesAllSubParts(storage);

			// pin the histogram + cardinality parts — without these checks the test would silently
			// pass when emission regressed to nothing
			assertFalse(storage.histogramParts.isEmpty(), "Expected histogram parts");
			assertFalse(storage.attributeCardinalityParts.isEmpty(), "Expected attribute cardinality parts");
			assertNotNull(storage.groupCardinalityPart, "Expected group cardinality part");
			assertTrue(storage.priceParts.isEmpty(), "Price parts intentionally not exercised here");
			assertFalse(storage.facetParts.isEmpty(), "Expected facet parts");
			// ReducedGroupEntityIndex never emits hierarchy — addNode throws on the reduced subclass
			assertNull(storage.hierarchyPart, "ReducedGroupEntityIndex must not emit a hierarchy part");

			final ReducedGroupEntityIndex reloaded = reload(storage);

			assertEquals(original.getAllPrimaryKeys().size(), reloaded.getAllPrimaryKeys().size());
			assertTrue(reloaded.getAllPrimaryKeys().contains(13));
			assertTrue(reloaded.getAllPrimaryKeys().contains(14));
			assertTrue(reloaded.getLanguages().contains(Locale.ENGLISH));
			// histogram sentinel — the sub-index that the manifest must round-trip
			final FilterIndex reloadedHistogram = reloaded.getHistogramFilterIndex(HISTOGRAM_NAME, null);
			assertNotNull(reloadedHistogram, "Histogram filter index must survive the reload");
			assertTrue(reloadedHistogram.getRecordsEqualTo(100).contains(13));
			assertTrue(reloadedHistogram.getRecordsEqualTo(200).contains(14));
			// group cardinality sentinel — referenced PKs 1, 2, 3 must all be tracked
			assertEquals(3, reloaded.getReferencedEntityPrimaryKeys().size());
			assertTrue(reloaded.isPriceIndexEmpty(), "Price was not populated in this fixture");
			assertTrue(reloaded.isHierarchyIndexEmpty(), "Hierarchy was not populated in this fixture");
			assertTrue(reloaded.getFacetingEntities().containsKey(REFERENCE_NAME));

			// AttributeIndex subclass identity must survive reload: ReducedGroupEntityIndex
			// carries a ReferenceAttributeIndex with REFERENCE scope
			assertInstanceOf(
				ReferenceAttributeIndex.class, original.attributeIndex,
				"ReducedGroupEntityIndex must construct a ReferenceAttributeIndex"
			);
			assertInstanceOf(
				ReferenceAttributeIndex.class, reloaded.attributeIndex,
				"Reloaded ReducedGroupEntityIndex must reconstruct a ReferenceAttributeIndex"
			);
			assertEquals(AttributeScope.REFERENCE, reloaded.attributeIndex.getScope());

			assertManifestStableAfterReload(reloaded, storage.requireManifest());
		}
	}

	@Nested
	@DisplayName("ReferencedTypeEntityIndex round-trip")
	class ReferencedTypeEntityIndexRoundTripTest {

		/**
		 * Pre-populates a `ReferencedTypeEntityIndex` (REFERENCED_ENTITY_TYPE) with cardinality-
		 * aware PKs, filter attributes (cardinality-tracked), histogram values, and a facet. This
		 * subclass uses `VoidPriceIndex`, so no price parts are emitted — and the assertion below
		 * locks that in.
		 *
		 * @return a populated `ReferencedTypeEntityIndex`
		 */
		@Nonnull
		private static ReferencedTypeEntityIndex buildPopulatedIndex() {
			final EntityIndexKey key = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME
			);
			final ReferencedTypeEntityIndex index =
				new ReferencedTypeEntityIndex(INDEX_PK, ENTITY_TYPE, key);
			final EntitySchemaContract schema = createSchema(Set.of(Locale.ENGLISH));
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			final AttributeSchemaContract nameSchema = createAttributeSchema(ATTRIBUTE_NAME, String.class);
			final Set<Locale> allowedLocales = Set.of(Locale.ENGLISH);

			// cardinality-aware PK insertion: entity 15 has two refs to referenced entity 50;
			// entity 16 has one ref to referenced entity 51
			index.insertPrimaryKeyIfMissing(15, 50);
			index.insertPrimaryKeyIfMissing(15, 50);
			index.insertPrimaryKeyIfMissing(16, 51);
			index.upsertLanguage(Locale.ENGLISH, 15, schema);

			index.insertFilterAttribute(refSchema, nameSchema, allowedLocales, null, "Lamp", 15);
			index.insertFilterAttribute(refSchema, nameSchema, allowedLocales, null, "Lamp", 15);

			index.insertHistogramValue(HISTOGRAM_NAME, null, 11, 15, Integer.class, 0);
			index.insertHistogramValue(HISTOGRAM_NAME, null, 22, 16, Integer.class, 0);

			index.addFacet(null, new ReferenceKey(REFERENCE_NAME, 50), GROUP_PK, 15);
			return index;
		}

		/**
		 * Reconstructs a `ReferencedTypeEntityIndex` from the captured storage parts, mirroring the
		 * `EntityIndexType.REFERENCED_ENTITY_TYPE` branch of `readEntityIndex`.
		 *
		 * @param storage the captured storage parts
		 * @return a freshly-loaded `ReferencedTypeEntityIndex`
		 */
		@Nonnull
		private static ReferencedTypeEntityIndex reload(@Nonnull CapturedStorage storage) {
			final EntityIndexStoragePart manifest = storage.requireManifest();
			// ReferencedTypeEntityIndex is reference-scoped even though the AttributeIndex receives a
			// null representative key — its discriminator is a String reference name
			final AttributeIndex attributeIndex = reloadAttributeIndex(storage, ENTITY_TYPE, null, true);
			final ReferenceTypeCardinalityIndexStoragePart refTypePart =
				storage.referenceTypeCardinalityPart;
			assertNotNull(refTypePart, "ReferencedTypeEntityIndex must emit a ref-type cardinality part");
			final Map<String, HistogramIndex> histogramIndexes =
				reloadHistogramIndexes(storage, REFERENCE_NAME);
			// reconstruct ReferenceTypeCardinalityIndex via the (Map, Map) constructor so its dirty
			// flag starts clean — production kryo deserialization has the same effect
			final ReferenceTypeCardinalityIndex liveRefType = refTypePart.getCardinalityIndex();
			final ReferenceTypeCardinalityIndex freshRefType = new ReferenceTypeCardinalityIndex(
				liveRefType.getCardinalities(), liveRefType.getReferencedPrimaryKeysIndex()
			);
			return new ReferencedTypeEntityIndex(
				manifest.getPrimaryKey(),
				manifest.getEntityIndexKey(),
				manifest.getVersion(),
				manifest.getEntityIds(),
				reloadEntityIdsByLanguage(manifest),
				(ReferenceAttributeIndex) attributeIndex,
				reloadHierarchyIndex(storage),
				reloadFacetIndex(storage),
				freshRefType,
				reloadCardinalityIndexes(storage),
				histogramIndexes
			);
		}

		@Test
		@DisplayName("should preserve manifest cross-refs and reload histogram + cardinality sub-indexes")
		void shouldRoundTripReferencedTypeEntityIndex() {
			final ReferencedTypeEntityIndex original = buildPopulatedIndex();

			final CapturedStorage storage = flush(original);

			assertManifestReferencesAllSubParts(storage);

			assertFalse(storage.histogramParts.isEmpty(), "Expected histogram parts");
			assertFalse(storage.attributeCardinalityParts.isEmpty(), "Expected attribute cardinality parts");
			assertNotNull(storage.referenceTypeCardinalityPart, "Expected reference-type cardinality part");
			assertFalse(storage.facetParts.isEmpty(), "Expected facet parts");
			// VoidPriceIndex: no price parts allowed
			assertTrue(storage.priceParts.isEmpty(), "ReferencedTypeEntityIndex must not emit price parts");

			final ReferencedTypeEntityIndex reloaded = reload(storage);

			assertEquals(original.getAllPrimaryKeys().size(), reloaded.getAllPrimaryKeys().size());
			assertTrue(reloaded.getAllPrimaryKeys().contains(15));
			assertTrue(reloaded.getAllPrimaryKeys().contains(16));
			assertTrue(reloaded.getLanguages().contains(Locale.ENGLISH));
			final FilterIndex reloadedHistogram = reloaded.getHistogramFilterIndex(HISTOGRAM_NAME, null);
			assertNotNull(reloadedHistogram, "Histogram filter index must survive reload");
			assertTrue(reloadedHistogram.getRecordsEqualTo(11).contains(15));
			assertTrue(reloadedHistogram.getRecordsEqualTo(22).contains(16));
			// reference-type cardinality sentinel: facets 50 and 51 must both be tracked
			final Set<Integer> trackedReferencedPks = reloaded.getAllTrackedReferencedEntityPrimaryKeys();
			assertTrue(trackedReferencedPks.contains(50));
			assertTrue(trackedReferencedPks.contains(51));
			assertTrue(reloaded.getFacetingEntities().containsKey(REFERENCE_NAME));

			// AttributeIndex subclass identity must survive reload: ReferencedTypeEntityIndex
			// carries a ReferenceAttributeIndex with REFERENCE scope even though it has no
			// RepresentativeReferenceKey
			assertInstanceOf(
				ReferenceAttributeIndex.class, original.attributeIndex,
				"ReferencedTypeEntityIndex must construct a ReferenceAttributeIndex"
			);
			assertInstanceOf(
				ReferenceAttributeIndex.class, reloaded.attributeIndex,
				"Reloaded ReferencedTypeEntityIndex must reconstruct a ReferenceAttributeIndex"
			);
			assertEquals(AttributeScope.REFERENCE, reloaded.attributeIndex.getScope());

			assertManifestStableAfterReload(reloaded, storage.requireManifest());
		}
	}

	/**
	 * In-memory bag of storage parts emitted by a single `getModifiedStorageParts` call,
	 * categorized by structural role for test access.
	 */
	private static final class CapturedStorage {
		/** The manifest emitted by the entity index. */
		@Nullable private EntityIndexStoragePart manifest;
		/** UNIQUE / FILTER / SORT / CHAIN parts (CARDINALITY parts are stored separately). */
		@Nonnull private final List<AttributeIndexStoragePart> attributeParts = new ArrayList<>(8);
		/** Attribute cardinality parts — reduced/referenced indexes consume them via a separate map. */
		@Nonnull private final List<AttributeCardinalityIndexStoragePart> attributeCardinalityParts =
			new ArrayList<>(4);
		/** PriceListAndCurrency* parts — either super or ref variants. */
		@Nonnull private final List<StoragePart> priceParts = new ArrayList<>(4);
		/** Histogram parts, one per (histogramName, locale) pair. */
		@Nonnull private final List<HistogramIndexStoragePart> histogramParts = new ArrayList<>(4);
		/** Facet parts, one per reference name with non-empty facet data. */
		@Nonnull private final List<FacetIndexStoragePart> facetParts = new ArrayList<>(4);
		/** The single hierarchy part if hierarchy was populated, else null. */
		@Nullable private HierarchyIndexStoragePart hierarchyPart;
		/** Group cardinality part emitted only by `ReducedGroupEntityIndex`. */
		@Nullable private GroupCardinalityIndexStoragePart groupCardinalityPart;
		/** Reference-type cardinality part emitted only by `ReferencedTypeEntityIndex`. */
		@Nullable private ReferenceTypeCardinalityIndexStoragePart referenceTypeCardinalityPart;

		/**
		 * Dispatches an emitted storage part to the appropriate bucket. The dispatch is intentionally
		 * exhaustive — an unrecognized part type triggers an `AssertionError` so a new sub-index type
		 * added to the codebase forces this test to be updated rather than silently passing.
		 *
		 * @param part the storage part emitted by `getModifiedStorageParts`
		 */
		void add(@Nonnull StoragePart part) {
			if (part instanceof EntityIndexStoragePart manifestPart) {
				assertNull(this.manifest, "Multiple manifests emitted in one flush");
				this.manifest = manifestPart;
			} else if (part instanceof AttributeCardinalityIndexStoragePart cardPart) {
				this.attributeCardinalityParts.add(cardPart);
			} else if (part instanceof AttributeIndexStoragePart attrPart) {
				this.attributeParts.add(attrPart);
			} else if (part instanceof HistogramIndexStoragePart histPart) {
				this.histogramParts.add(histPart);
			} else if (part instanceof FacetIndexStoragePart facetPart) {
				this.facetParts.add(facetPart);
			} else if (part instanceof HierarchyIndexStoragePart hierPart) {
				assertNull(this.hierarchyPart, "Multiple hierarchy parts emitted in one flush");
				this.hierarchyPart = hierPart;
			} else if (part instanceof GroupCardinalityIndexStoragePart groupPart) {
				assertNull(this.groupCardinalityPart, "Multiple group cardinality parts in one flush");
				this.groupCardinalityPart = groupPart;
			} else if (part instanceof ReferenceTypeCardinalityIndexStoragePart refTypePart) {
				assertNull(
					this.referenceTypeCardinalityPart,
					"Multiple reference-type cardinality parts in one flush"
				);
				this.referenceTypeCardinalityPart = refTypePart;
			} else if (part instanceof PriceListAndCurrencySuperIndexStoragePart
				|| part instanceof PriceListAndCurrencyRefIndexStoragePart) {
				this.priceParts.add(part);
			} else {
				throw new AssertionError(
					"Unexpected storage part type emitted by EntityIndex: " + part.getClass()
				);
			}
		}

		/**
		 * Returns the manifest, failing fast if no manifest was emitted. Every flush of a dirty
		 * `EntityIndex` MUST emit exactly one `EntityIndexStoragePart`.
		 *
		 * @return the captured manifest
		 */
		@Nonnull
		EntityIndexStoragePart requireManifest() {
			assertNotNull(this.manifest, "No EntityIndexStoragePart manifest was emitted by the flush");
			return this.manifest;
		}
	}
}
