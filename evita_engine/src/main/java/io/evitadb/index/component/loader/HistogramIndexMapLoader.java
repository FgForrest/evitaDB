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
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStoragePart;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reloads the histogram index map carried by `ReferencedTypeEntityIndex` and
 * `ReducedGroupEntityIndex`, including the per-name grouping that decides whether to materialize
 * a `SimpleHistogramIndex` (single locale=null part) or a `LocalizedHistogramIndex` (any
 * non-null locale part).
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
		//noinspection resource
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
				final int indexedDecimalPlaces = part.getIndexedDecimalPlaces();
				result.put(histogramName, new SimpleHistogramIndex(
					histogramName, referenceName,
					(Class<? extends Serializable>) part.getValueType(),
					indexedDecimalPlaces,
					new OwnerFilterIndex(
						new AttributeIndexKey(referenceName, histogramName, null),
						part.getHistogramPoints(), part.getRangeIndex(), part.getValueType(),
						indexedDecimalPlaces
					),
					part.getCardinalityIndex()
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
					filterIndexes.put(locale, new OwnerFilterIndex(
						new AttributeIndexKey(referenceName, histogramName, locale),
						part.getHistogramPoints(), part.getRangeIndex(), part.getValueType(),
						part.getIndexedDecimalPlaces()
					));
					cardinalities.put(locale, part.getCardinalityIndex());
				}
				if (valueType != null) {
					result.put(histogramName, new LocalizedHistogramIndex(
						histogramName, referenceName, valueType, indexedDecimalPlaces,
						filterIndexes, cardinalities
					));
				}
			}
		}
		return new LoadedComponentBundle.Histograms(result);
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
