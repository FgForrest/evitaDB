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
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.IndexActivity;
import io.evitadb.index.analysis.AttributeNormalizationResolver;
import io.evitadb.index.analysis.BuiltWithAttributeNormalizationResolver;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIdsStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Immutable per-call bundle of everything a {@link ComponentLoader} needs to reload one sub-index
 * from persistent storage. A fresh `LoadContext` is built once by the dispatcher in
 * `DefaultEntityCollectionPersistenceService` per `readEntityIndex` invocation and shared across
 * every loader that participates in the matching {@link IndexReloadPlan}.
 *
 * The context is intentionally a record — the reload path is **not** on the hot query path
 * (catalog boot / restart only), so allocation discipline can be traded for clarity here.
 *
 * @param catalogVersion         the catalog version to read storage parts from
 * @param entityIndexId          the primary key of the entity index being reloaded
 * @param entitySchema           the resolved entity schema for the owning collection
 * @param entityIndexKey         the key carried by the previously-persisted
 *                               {@link EntityIndexStoragePart}
 * @param entityIndexStoragePart the previously-persisted manifest; loaders consult it for the
 *                               set of sub-index keys they should fetch
 * @param version                the effective index version, reconciled from the manifest and the
 *                               sibling {@link EntityIdsStoragePart} (whichever is newer) — see
 *                               `DefaultEntityCollectionPersistenceService#readEntityIndex`
 * @param entityIds              the all-entities superset bitmap, resolved from the sibling
 *                               {@link EntityIdsStoragePart} when present, else from the manifest's
 *                               legacy inline carrier
 * @param entityIdsByLanguage    the per-locale entity-id bitmaps, resolved by the same
 *                               sibling-or-legacy-fallback rule as {@code entityIds}
 * @param storagePartService     the storage-part persistence service used to fetch raw parts
 * @param referenceKey           the discriminator for `REFERENCED_ENTITY` /
 *                               `REFERENCED_GROUP_ENTITY` indexes; `null` for `GLOBAL` and
 *                               `REFERENCED_*_TYPE` indexes
 * @param usageStatisticsTracking whether the reloaded index allocates an
 *                               {@link IndexActivity} holder — carried through the reload
 *                               because an index restored from disk starts a fresh observation window, and
 *                               a server running with `server.usageStatisticsTracking` off must not open one
 * @param fulltextAnalyzerRegistry the catalog's analyzer registry, through which the fulltext indexes resolve the
 *                               analyzer they were built with by its persisted name
 * @param normalizationResolver owner resolver used to bind String functions before any attribute tree is constructed
 */
public record LoadContext(
	long catalogVersion,
	int entityIndexId,
	@Nonnull EntitySchema entitySchema,
	@Nonnull EntityIndexKey entityIndexKey,
	@Nonnull EntityIndexStoragePart entityIndexStoragePart,
	int version,
	@Nonnull Bitmap entityIds,
	@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage,
	@Nonnull StoragePartPersistenceService<?> storagePartService,
	@Nullable RepresentativeReferenceKey referenceKey,
	boolean usageStatisticsTracking,
	@Nonnull FulltextAnalyzerRegistry fulltextAnalyzerRegistry,
	@Nonnull AttributeNormalizationResolver normalizationResolver
) {

	/**
	 * Resolves the current declaration, which may have been removed while its persisted index still exists.
	 *
	 * @param key identity of the persisted attribute index
	 * @return the declared attribute schema, or `null` when the declaration no longer exists
	 */
	@Nullable
	public AttributeSchemaContract resolveAttribute(@Nonnull AttributeIndexKey key) {
		return key.referenceName() == null
			? this.entitySchema.getAttribute(key.attributeName()).orElse(null)
			: this.entitySchema.getReference(key.referenceName())
				.flatMap(reference -> reference.getAttribute(key.attributeName())).orElse(null);
	}

	/**
	 * Binds the raw-input function from owner metadata before constructing a tree, including orphaned String indexes.
	 * Re-canonicalizing persisted keys heals keys written by older versions and must use the legacy function only.
	 *
	 * @param key                  identity of the persisted attribute index
	 * @param plainType            plain (non-array) type of the attribute values
	 * @param indexedDecimalPlaces the attribute schema's `indexedDecimalPlaces`
	 * @return raw-input key function; the legacy NFD-only one for a String attribute whose declaration is gone
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	public Function<Object, Serializable> resolveKeyNormalizer(
		@Nonnull AttributeIndexKey key, @Nonnull Class<?> plainType, int indexedDecimalPlaces
	) {
		if (plainType == String.class) {
			if (this.normalizationResolver instanceof BuiltWithAttributeNormalizationResolver builtWith) {
				return (Function) builtWith.resolve(key.referenceName(), key.attributeName(), key.locale());
			}
			final AttributeSchemaContract attribute = resolveAttribute(key);
			return attribute == null || this.normalizationResolver == AttributeNormalizationResolver.LEGACY_NFD
				? FilterIndex.getLegacyStringNormalizer()
				: this.normalizationResolver.resolveKeyNormalizer(
					key.referenceName(), attribute, key.locale());
		}
		return FilterIndex.getNormalizer(plainType, indexedDecimalPlaces);
	}

	/**
	 * Reconstructs scalar or per-element compound policy before constructing an owner sort index.
	 *
	 * @param key                  identity of the persisted sort index
	 * @param comparatorBase       frozen comparator descriptors of the stored tree, one per element
	 * @param indexedDecimalPlaces frozen decimal scale of the stored tree
	 * @return sort key function matching the stored keys
	 */
	@Nonnull
	public UnaryOperator<Serializable> resolveSortNormalizer(
		@Nonnull AttributeIndexKey key, @Nonnull SortIndex.ComparatorSource[] comparatorBase, int indexedDecimalPlaces
	) {
		if (this.normalizationResolver instanceof BuiltWithAttributeNormalizationResolver builtWith) {
			return builtWith.resolveStoredSortNormalizer(key, comparatorBase, indexedDecimalPlaces);
		}
		if (this.normalizationResolver == AttributeNormalizationResolver.LEGACY_NFD) {
			return SortIndex.createLegacyNormalizerFor(comparatorBase, indexedDecimalPlaces);
		}
		if (comparatorBase.length == 1) {
			if (comparatorBase[0].type() == String.class) {
				final AttributeSchemaContract attribute = resolveAttribute(key);
				return attribute == null
					? SortIndex.createLegacyNormalizerFor(comparatorBase[0].type(), indexedDecimalPlaces)
					: this.normalizationResolver.resolveSortNormalizer(key.referenceName(), attribute, key.locale());
			}
			return SortIndex.createNormalizerFor(comparatorBase[0], indexedDecimalPlaces)
				.orElseGet(UnaryOperator::identity);
		}
		final SortableAttributeCompoundSchemaContract compound =
			key.referenceName() == null
				? this.entitySchema.getSortableAttributeCompound(key.attributeName()).orElse(null)
				: this.entitySchema.getReference(key.referenceName())
					.flatMap(reference -> reference.getSortableAttributeCompound(key.attributeName())).orElse(null);
		if (compound == null) {
			return SortIndex.createLegacyNormalizerFor(comparatorBase, indexedDecimalPlaces);
		}
		for (final SortableAttributeCompoundSchemaContract.AttributeElement element : compound.getAttributeElements()) {
			if (resolveAttribute(new AttributeIndexKey(key.referenceName(), element.attributeName(), null)) == null) {
				return SortIndex.createLegacyNormalizerFor(comparatorBase, indexedDecimalPlaces);
			}
		}
		return this.normalizationResolver.resolveCompoundNormalizer(key.referenceName(), compound,
			name -> resolveAttribute(new AttributeIndexKey(key.referenceName(), name, null)), key.locale());
	}

	/**
	 * Hands back the activity holder a reloaded index should carry: a fresh one when the server tracks usage
	 * statistics — the counters start over, which is what *"since catalog load"* means — and none at all when it does
	 * not.
	 *
	 * Resolved here rather than at each of the four reload plans so that all of them cannot drift apart on it.
	 *
	 * @return the holder to hand to the index constructor, or null when usage statistics are not tracked
	 */
	@Nullable
	public IndexActivity createActivity() {
		return this.usageStatisticsTracking ? new IndexActivity() : null;
	}
}
