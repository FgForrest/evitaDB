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
import io.evitadb.index.fulltext.FulltextIndex.SurfaceFormPage;
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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextSurfaceFormLeafPagePart;
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
 * manifest lists, the {@link FulltextIndexStoragePart} root, the dictionary and surface dictionary pages it lists and
 * the length blocks of every field, assembled by {@link FulltextIndex#fromPersistedPages} and
 * {@link FieldLengthTable#fromPersistedBlocks}.
 *
 * Loading requires no analyzer. Every field restores its built-with identities; writes and queries compare them
 * with the current schema through the catalog's compiled analyzer cache.
 */
public final class FulltextIndexMapLoader implements ComponentLoader {

	/** Creates the loader for persisted per-locale fulltext indexes. */
	public FulltextIndexMapLoader() {
	}

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
	 * Loads the fulltext index of one locale. A persisted index lists every page it consists of and the loader trusts
	 * that list: a listed part missing from storage is a load failure, never an empty or partial index.
	 *
	 * @param context the reload context
	 * @param locale  the locale of the index
	 * @return the restored index, clean
	 * @throws io.evitadb.exception.GenericEvitaInternalError  when the root, a dictionary page or a length block it
	 *                                                         lists is not found in persistent storage
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
		final DictionaryPage[] pages = loadDictionaryPages(context, service, keyCompressor, pageSequences, locale);
		final List<Field> fields = loadFields(context, service, keyCompressor, root, locale);
		return FulltextIndex.fromPersistedPages(
			fields,
			pageSequences,
			pages,
			root.getDictionaryHighWaterPageSequence(), root.getWordPhaseIds(), root.getSurfaceFormPageSequences(),
			loadSurfacePages(context, service, keyCompressor, root.getSurfaceFormPageSequences(), locale),
			root.getSurfaceFormHighWaterPageSequence()
		);
	}

	/**
	 * Loads the dictionary pages the root lists, in the order it lists them.
	 *
	 * @param context       the reload context
	 * @param service       the storage to read the pages from
	 * @param keyCompressor the compressor the dictionary stream key is registered in
	 * @param pageSequences the page sequences the root lists, in key order
	 * @param locale        the locale of the index
	 * @return the pages, the i-th for the i-th sequence
	 * @throws io.evitadb.exception.GenericEvitaInternalError when a listed page is not found in persistent storage
	 */
	@Nonnull
	private static DictionaryPage[] loadDictionaryPages(
		@Nonnull LoadContext context,
		@Nonnull StoragePartPersistenceService<?> service,
		@Nonnull KeyCompressor keyCompressor,
		@Nonnull int[] pageSequences,
		@Nonnull Locale locale
	) {
		final int entityIndexId = context.entityIndexId();
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
		return pages;
	}

	/**
	 * Loads every surface leaf listed by the index root.
	 *
	 * @param context reload context
	 * @param service storage part reader
	 * @param keyCompressor registered stream identities
	 * @param sequences leaf sequences in key order
	 * @param locale index partition locale
	 * @return aligned surface pages
	 * @throws io.evitadb.exception.GenericEvitaInternalError when a listed page is not found in persistent storage
	 */
	@Nonnull
	private static SurfaceFormPage[] loadSurfacePages(
		@Nonnull LoadContext context, @Nonnull StoragePartPersistenceService<?> service,
		@Nonnull KeyCompressor keyCompressor, @Nonnull int[] sequences, @Nonnull Locale locale
	) {
		final int stream = keyCompressor.getId(new FulltextLeafStreamKey(
			context.entityIndexId(), locale, StreamKind.SURFACE_FORMS));
		final SurfaceFormPage[] pages = new SurfaceFormPage[sequences.length];
		for (int i = 0; i < sequences.length; i++) {
			final int sequence = sequences[i];
			final FulltextSurfaceFormLeafPagePart part = service.getStoragePart(context.catalogVersion(),
				AbstractLeafPagePart.computeUniquePartId(stream, sequence), FulltextSurfaceFormLeafPagePart.class);
			isPremiseValid(
				part != null,
				() -> "Surface dictionary page " + sequence + " of the fulltext index of entity index `" +
					context.entityIndexId() + "` and locale `" + locale + "` was not found in persistent storage!"
			);
			pages[i] = part.getPage();
		}
		return pages;
	}

	/**
	 * Restores the field registry the root carries, each field with the length table assembled from its blocks.
	 *
	 * @param context       the reload context
	 * @param service       the storage to read the length blocks from
	 * @param keyCompressor the compressor the length stream key is registered in, if any block was ever written
	 * @param root          the root of the index
	 * @param locale        the locale of the index
	 * @return the fields in field-id order, retired ones included
	 * @throws io.evitadb.exception.GenericEvitaInternalError when a listed length block is not found in persistent
	 *                                                        storage
	 */
	@Nonnull
	private static List<Field> loadFields(
		@Nonnull LoadContext context,
		@Nonnull StoragePartPersistenceService<?> service,
		@Nonnull KeyCompressor keyCompressor,
		@Nonnull FulltextIndexStoragePart root,
		@Nonnull Locale locale
	) {
		final int entityIndexId = context.entityIndexId();
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
					entry.key(), entry.analyzerName(), entry.indexFingerprint(), entry.wordPhaseId(),
						entry.lengthPivot(), entry.textExpression(), entry.retired(),
					FieldLengthTable.fromPersistedBlocks(blocks)
				)
			);
		}
		return fields;
	}

}
