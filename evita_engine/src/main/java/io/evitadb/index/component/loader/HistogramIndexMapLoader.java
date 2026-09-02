/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.component.loader;

import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.HistogramIndex;
import io.evitadb.index.LocalizedHistogramIndex;
import io.evitadb.index.SimpleHistogramIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractHistogramStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramCardinalityStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramLeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramLeafStreamKey.StreamKind;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramRangeIndexLeafPagePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Reloads the histogram index map carried by `ReferencedTypeEntityIndex` and
 * `ReducedGroupEntityIndex`, including the per-name grouping that decides whether to materialize
 * a `SimpleHistogramIndex` (single locale=null part) or a `LocalizedHistogramIndex` (any
 * non-null locale part).
 *
 * Each histogram sub-index is reconstructed boundary-stable from its root part: a `SINGLE`-shaped axis carries its
 * data inline, while a `PAGED`-shaped bucket / range axis is reassembled from individual
 * {@link HistogramIndexLeafPagePart} / {@link HistogramRangeIndexLeafPagePart} leaf pages. The cardinality index is
 * fetched from the sibling {@link HistogramCardinalityStoragePart} (it is no longer carried inline on the root).
 */
public final class HistogramIndexMapLoader implements ComponentLoader {

	@Override
	@Nonnull
	@SuppressWarnings("unchecked")
	public LoadedComponentBundle load(@Nonnull LoadContext context) {
		final EntityIndexStoragePart manifest = context.entityIndexStoragePart();
		final Set<HistogramIndexStorageKey> histogramKeys = manifest.getHistogramIndexes();
		if (histogramKeys.isEmpty()) {
			return new LoadedComponentBundle.Histograms(CollectionUtils.createHashMap(0));
		}
		final String referenceName = getReferenceName(context);
		final StoragePartPersistenceService<?> service = context.storagePartService();
		final int entityIndexId = context.entityIndexId();
		final long catalogVersion = context.catalogVersion();

		// group fetched parts by histogram name first
		final Map<String, Map<Locale, HistogramIndexStoragePart>> partsByName =
			CollectionUtils.createHashMap(histogramKeys.size());
		for (final HistogramIndexStorageKey histogramKey : histogramKeys) {
			final long primaryKey = histogramKey.computeUniquePartId(
				entityIndexId, service.getReadOnlyKeyCompressor()
			);
			final HistogramIndexStoragePart part = service.getStoragePart(
				catalogVersion, primaryKey, HistogramIndexStoragePart.class
			);
			if (part != null) {
				partsByName
					.computeIfAbsent(histogramKey.histogramName(), k -> CollectionUtils.createHashMap(4))
					.put(histogramKey.locale(), part);
			}
		}

		final Map<String, HistogramIndex> result = CollectionUtils.createHashMap(partsByName.size());
		for (final Map.Entry<String, Map<Locale, HistogramIndexStoragePart>> entry : partsByName.entrySet()) {
			final String histogramName = entry.getKey();
			final Map<Locale, HistogramIndexStoragePart> parts = entry.getValue();
			// the indexedDecimalPlaces scale is frozen into each histogram part at write time and read back from THAT part
			// verbatim (0 for non-BigDecimal source types). It must NOT be re-derived from the schema by histogram name: the
			// histogram name is a free-form definition name, not the source attribute name — its value expression may read a
			// differently-named attribute, possibly on another entity's schema — so a by-name owner-schema lookup would be
			// wrong. See the freeze rationale on HistogramIndexStoragePart#indexedDecimalPlaces.
			if (parts.containsKey(null) && parts.size() == 1) {
				// non-localized histogram
				final HistogramIndexStoragePart part = parts.get(null);
				result.put(histogramName, new SimpleHistogramIndex(
					histogramName, referenceName,
					(Class<? extends Serializable>) part.getValueType(),
					part.getIndexedDecimalPlaces(),
					reloadOwnerFilterIndex(
						part, referenceName, histogramName, null, service, catalogVersion, entityIndexId
					),
					reloadCardinality(
						histogramName, null, (Class<? extends Serializable>) part.getValueType(),
						service, catalogVersion, entityIndexId
					)
				));
			} else {
				// localized histogram — collect per-locale filter and cardinality children. The value type and the frozen
				// scale are histogram-wide invariants (the same source attribute backs every locale); they are captured
				// once from the first locale part for the LocalizedHistogramIndex, while each OwnerFilterIndex still reads
				// its own part's scale directly.
				final Map<Locale, OwnerFilterIndex> filterIndexes = CollectionUtils.createHashMap(parts.size());
				final Map<Locale, AttributeCardinalityIndex> cardinalities =
					CollectionUtils.createHashMap(parts.size());
				Class<? extends Serializable> valueType = null;
				int indexedDecimalPlaces = 0;
				for (final Map.Entry<Locale, HistogramIndexStoragePart> partEntry : parts.entrySet()) {
					final Locale locale = partEntry.getKey();
					if (locale == null) {
						continue;
					}
					final HistogramIndexStoragePart part = partEntry.getValue();
					if (valueType == null) {
						valueType = (Class<? extends Serializable>) part.getValueType();
						indexedDecimalPlaces = part.getIndexedDecimalPlaces();
					}
					filterIndexes.put(
						locale,
						reloadOwnerFilterIndex(
							part, referenceName, histogramName, locale, service, catalogVersion, entityIndexId
						)
					);
					cardinalities.put(
						locale,
						reloadCardinality(
							histogramName, locale, (Class<? extends Serializable>) part.getValueType(),
							service, catalogVersion, entityIndexId
						)
					);
				}
				if (valueType != null) {
					result.put(
						histogramName,
						new LocalizedHistogramIndex(
							histogramName, referenceName, valueType, indexedDecimalPlaces,
							filterIndexes, cardinalities
						)
					);
				}
			}
		}
		return new LoadedComponentBundle.Histograms(result);
	}

	/**
	 * Reconstructs the histogram's embedded {@link OwnerFilterIndex} from its root part, reassembling any `PAGED` bucket
	 * / range axis from its leaf pages (boundary-stable) and using the inline data for a `SINGLE` axis.
	 *
	 * @param part           the already-fetched histogram root part
	 * @param referenceName  the reference name (part of the filter index identity)
	 * @param histogramName  the histogram name (part of the page-stream identity)
	 * @param locale         the locale of this sub-index, or `null`
	 * @param service        the storage-part persistence service to read leaf pages from
	 * @param catalogVersion the catalog version to read pages at
	 * @param entityIndexId  the owning entity index pk (part of the page-stream key)
	 * @return the reconstructed owner filter index
	 */
	@Nonnull
	private static OwnerFilterIndex reloadOwnerFilterIndex(
		@Nonnull HistogramIndexStoragePart part,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull StoragePartPersistenceService<?> service,
		long catalogVersion,
		int entityIndexId
	) {
		final Class<?> attributeType = part.getValueType();
		// FilterIndex.plainTypeOf is package-private, so derive the non-array plain type locally (mirrors
		// AttributeIndexLoader.fetchFilter)
		final Class<?> plainType = attributeType.isArray() ? attributeType.getComponentType() : attributeType;
		final int indexedDecimalPlaces = part.getIndexedDecimalPlaces();
		final AttributeIndexKey attributeIndexKey = new AttributeIndexKey(referenceName, histogramName, locale);
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(plainType, indexedDecimalPlaces);
		final Comparator<?> comparator = FilterIndex.getComparator(attributeIndexKey, plainType);

		// BUCKET axis
		final InvertedIndex invertedIndex;
		if (!part.isPaged()) {
			invertedIndex = new InvertedIndex(
				plainType, part.getHistogramPoints(), normalizer, comparator, indexedDecimalPlaces
			);
		} else {
			final int streamId = service.getReadOnlyKeyCompressor().getId(
				new HistogramLeafStreamKey(entityIndexId, histogramName, locale, StreamKind.BUCKET)
			);
			final int[] orderedPageSequences = part.getLeafPageSequences();
			final ValueToRecord[][] perPageBuckets = new ValueToRecord[orderedPageSequences.length][];
			for (int i = 0; i < orderedPageSequences.length; i++) {
				final int pageSequence = orderedPageSequences[i];
				final HistogramIndexLeafPagePart leafPage = service.getStoragePart(
					catalogVersion, HistogramIndexLeafPagePart.computeUniquePartId(streamId, pageSequence),
					HistogramIndexLeafPagePart.class
				);
				Assert.isPremiseValid(
					leafPage != null,
					"Histogram bucket leaf page " + pageSequence + " (stream " + streamId + ") for histogram '" +
						histogramName + "' was not found in persistent storage!"
				);
				perPageBuckets[i] = leafPage.getBuckets();
			}
			invertedIndex = InvertedIndex.fromPersistedPages(
				// the histogram's own inverted index is private to it and carries no value ids
				plainType, orderedPageSequences, perPageBuckets, null, part.getHighWaterPageSequence(),
				normalizer, comparator, indexedDecimalPlaces
			);
		}

		// RANGE axis
		final RangeIndex rangeIndex;
		if (!part.isRangePaged()) {
			rangeIndex = part.getRangeIndex();
		} else {
			final int rangeStreamId = service.getReadOnlyKeyCompressor().getId(
				new HistogramLeafStreamKey(entityIndexId, histogramName, locale, StreamKind.RANGE)
			);
			final int[] rangePageSequences = part.getRangeLeafPageSequences();
			final TransactionalRangePoint[][] perPagePoints = new TransactionalRangePoint[rangePageSequences.length][];
			for (int i = 0; i < rangePageSequences.length; i++) {
				final int pageSequence = rangePageSequences[i];
				final HistogramRangeIndexLeafPagePart leafPage = service.getStoragePart(
					catalogVersion, HistogramRangeIndexLeafPagePart.computeUniquePartId(rangeStreamId, pageSequence),
					HistogramRangeIndexLeafPagePart.class
				);
				Assert.isPremiseValid(
					leafPage != null,
					"Histogram range leaf page " + pageSequence + " (stream " + rangeStreamId + ") for histogram '" +
						histogramName + "' was not found in persistent storage!"
				);
				perPagePoints[i] = leafPage.getPoints();
			}
			rangeIndex = RangeIndex.fromPersistedPages(
				"histogram '" + histogramName + "'", rangePageSequences, perPagePoints,
				part.getRangeHighWaterPageSequence()
			);
		}

		return OwnerFilterIndex.fromPersistedPages(
			attributeIndexKey, invertedIndex, rangeIndex, attributeType, indexedDecimalPlaces
		);
	}

	/**
	 * Fetches the histogram's cardinality index from its sibling {@link HistogramCardinalityStoragePart}. A histogram
	 * with data always has a sibling (cardinality is dirty on the commit that first creates the histogram); the empty
	 * fallback is purely defensive.
	 *
	 * @param histogramName  the histogram name (part of the sibling identity)
	 * @param locale         the locale of this sub-index, or `null`
	 * @param valueType      the value type (used only for the defensive empty fallback)
	 * @param service        the storage-part persistence service to read from
	 * @param catalogVersion the catalog version to read at
	 * @param entityIndexId  the owning entity index pk (part of the sibling identity)
	 * @return the reloaded cardinality index
	 */
	@Nonnull
	private static AttributeCardinalityIndex reloadCardinality(
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Class<? extends Serializable> valueType,
		@Nonnull StoragePartPersistenceService<?> service,
		long catalogVersion,
		int entityIndexId
	) {
		final long primaryKey = AbstractHistogramStoragePart.computeUniquePartId(
			entityIndexId, histogramName, locale, service.getReadOnlyKeyCompressor()
		);
		final HistogramCardinalityStoragePart part = service.getStoragePart(
			catalogVersion, primaryKey, HistogramCardinalityStoragePart.class
		);
		return part != null ? part.getCardinalityIndex() : new AttributeCardinalityIndex(valueType);
	}

	/**
	 * Retrieves the reference name from the provided {@code LoadContext}. The method uses the discriminator
	 * from the {@link EntityIndexKey} associated with the context to determine the reference name.
	 * In the case of an unexpected discriminator type, a runtime exception is thrown.
	 *
	 * @param context the load context containing the {@link EntityIndexKey} from which the reference name is extracted.
	 * @return the reference name as a {@link String}.
	 * @throws GenericEvitaInternalError if the discriminator type is unexpected or unsupported.
	 */
	@Nonnull
	private static String getReferenceName(@Nonnull LoadContext context) {
		final EntityIndexKey entityIndexKey = context.entityIndexKey();
		final String referenceName;
		final Serializable discriminator = entityIndexKey.discriminator();
		if (discriminator instanceof String strDiscriminator) {
			referenceName = strDiscriminator;
		} else if (discriminator instanceof RepresentativeReferenceKey rrk) {
			referenceName = rrk.referenceName();
		} else {
			throw new GenericEvitaInternalError(
				"Unexpected discriminator type for histogram-bearing entity index: " +
					(discriminator == null ? "null" : discriminator.getClass().getName())
			);
		}
		return referenceName;
	}

}
