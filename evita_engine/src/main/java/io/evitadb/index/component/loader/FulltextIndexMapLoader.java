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

package io.evitadb.index.component.loader;

import io.evitadb.index.fulltext.FieldLengthTable;
import io.evitadb.index.fulltext.FieldLengthTable.LengthBlock;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.FulltextIndex.DictionaryPage;
import io.evitadb.index.fulltext.FulltextIndex.Field;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextDictionaryLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextFieldLengthBlockPart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexStoragePart.FieldEntry;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextLeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextLeafStreamKey.StreamKind;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * Reloads the per-locale fulltext indexes carried by `GlobalEntityIndex`: for every {@link FulltextIndexKey} the
 * manifest lists, the {@link FulltextIndexStoragePart} root, the dictionary pages it lists and the length blocks of
 * every field, assembled by {@link FulltextIndex#fromPersistedPages} and
 * {@link FieldLengthTable#fromPersistedBlocks}.
 *
 * The analyzer is the one the root names, resolved by name through the context's
 * {@link LoadContext#fulltextAnalyzerRegistry()} - an index is read back with the analyzer that produced its terms,
 * never with whatever the schema's assignment would resolve today, which could tokenize a query differently from the
 * stored terms without any error.
 */
public final class FulltextIndexMapLoader implements ComponentLoader {

	@Override
	@Nonnull
	public LoadedComponentBundle load(@Nonnull LoadContext context) {
		final Set<FulltextIndexKey> keys = context.entityIndexStoragePart().getFulltextIndexes();
		if (keys.isEmpty()) {
			return new LoadedComponentBundle.FulltextIndexes(Map.of());
		}
		final Map<Locale, FulltextIndex> fulltextIndexes = CollectionUtils.createHashMap(keys.size());
		for (final FulltextIndexKey key : keys) {
			fulltextIndexes.put(key.locale(), loadIndex(context, key.locale()));
		}
		return new LoadedComponentBundle.FulltextIndexes(fulltextIndexes);
	}

	/**
	 * Loads the fulltext index of one locale.
	 *
	 * @param context the reload context
	 * @param locale  the locale of the index
	 * @return the restored index, clean
	 */
	@Nonnull
	private static FulltextIndex loadIndex(@Nonnull LoadContext context, @Nonnull Locale locale) {
		final StoragePartPersistenceService<?> service = context.storagePartService();
		final KeyCompressor keyCompressor = service.getReadOnlyKeyCompressor();
		final int entityIndexId = context.entityIndexId();
		final FulltextIndexStoragePart root = service.getStoragePart(
			context.catalogVersion(),
			FulltextIndexStoragePart.computeUniquePartId(entityIndexId, locale, keyCompressor),
			FulltextIndexStoragePart.class
		);
		isPremiseValid(
			root != null,
			() -> "Fulltext index of entity index `" + entityIndexId + "` and locale `" + locale +
				"` was not found in persistent storage!"
		);

		final int[] pageSequences = root.getDictionaryPageSequences();
		final int dictionaryStreamId = keyCompressor.getId(
			new FulltextLeafStreamKey(entityIndexId, locale, StreamKind.DICTIONARY)
		);
		final DictionaryPage[] pages = new DictionaryPage[pageSequences.length];
		for (int i = 0; i < pageSequences.length; i++) {
			final int pageSequence = pageSequences[i];
			final FulltextDictionaryLeafPagePart page = service.getStoragePart(
				context.catalogVersion(),
				AbstractLeafPagePart.computeUniquePartId(dictionaryStreamId, pageSequence),
				FulltextDictionaryLeafPagePart.class
			);
			isPremiseValid(
				page != null,
				() -> "Dictionary page " + pageSequence + " of the fulltext index of entity index `" + entityIndexId +
					"` and locale `" + locale + "` was not found in persistent storage!"
			);
			pages[i] = new DictionaryPage(page.getPageSequence(), page.getBuckets(), page.getImpacts());
		}

		final FieldEntry[] fieldEntries = root.getFields();
		final List<Field> fields = new ArrayList<>(fieldEntries.length);
		// the length stream is registered by the first block written to it, so it is resolved only when one was
		int lengthStreamId = -1;
		for (int fieldId = 0; fieldId < fieldEntries.length; fieldId++) {
			final FieldEntry entry = fieldEntries[fieldId];
			final int[] blockKeys = entry.lengthBlocks();
			if (blockKeys.length > 0 && lengthStreamId == -1) {
				lengthStreamId = keyCompressor.getId(
					new FulltextLeafStreamKey(entityIndexId, locale, StreamKind.FIELD_LENGTHS)
				);
			}
			final LengthBlock[] blocks = new LengthBlock[blockKeys.length];
			for (int i = 0; i < blockKeys.length; i++) {
				final int blockKey = blockKeys[i];
				final int pageSequence = FulltextFieldLengthBlockPart.pageSequenceOf(fieldId, blockKey);
				final FulltextFieldLengthBlockPart block = service.getStoragePart(
					context.catalogVersion(),
					AbstractLeafPagePart.computeUniquePartId(lengthStreamId, pageSequence),
					FulltextFieldLengthBlockPart.class
				);
				isPremiseValid(
					block != null,
					() -> "Length block " + blockKey + " of field " + entry.key() + " of the fulltext index of " +
						"entity index `" + entityIndexId + "` and locale `" + locale + "` was not found in persistent " +
						"storage!"
				);
				blocks[i] = block.getBlock();
			}
			fields.add(
				new Field(
					entry.key(), entry.lengthPivot(), entry.retired(), FieldLengthTable.fromPersistedBlocks(blocks)
				)
			);
		}

		return FulltextIndex.fromPersistedPages(
			context.fulltextAnalyzerRegistry().getIndexAnalyzerByName(root.getAnalyzerName()),
			root.getDefaultLengthPivot(),
			fields,
			pageSequences,
			pages,
			root.getDictionaryHighWaterPageSequence()
		);
	}

}
