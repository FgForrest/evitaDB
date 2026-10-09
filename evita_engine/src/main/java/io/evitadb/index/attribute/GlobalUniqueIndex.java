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

package io.evitadb.index.attribute;

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.array.CompositeLongArray;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.EntityTypeClassifierResolver;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.IndexHeapSize;
import io.evitadb.index.bPlusTree.LongPayloadBucketTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.LeafPageHandle;
import io.evitadb.index.bPlusTree.ValueColumnFactory;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.page.PageStreamRegistry;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.collidingKeysError;
import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.findFirstNonAscendingKey;
import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.normalizerFor;
import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.plainTypeOf;
import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.toDeclaredValue;
import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.toDistinctKeys;
import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.toKey;
import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.toPersistedKeys;
import static io.evitadb.utils.Assert.isTrue;
import static java.util.Optional.ofNullable;

/**
 * Global (catalog-wide) unique index maintains information about a single unique attribute - its value to entity
 * tuple relation. It protects duplicate unique attribute insertion and allows to easily translate unique attribute
 * value to the entity that occupies it.
 *
 * The value to entity tuple relation is kept in a {@link TransactionalBucketBPlusTree} keyed by the unique value, where
 * each bucket holds exactly one packed `long` payload (uniqueness is enforced on insert, so the bucket's overflow bitmap
 * is never allocated). The logical {@link EntityWithTypeTuple} `(entityType, entityPrimaryKey, locale)` is packed into a
 * single `long` at the tree boundary with the layout `locale:16 | entityType:16 | pk:32` (see {@link #packTuple}), so the
 * whole value→entity map is stored as a compact key column plus an 8-byte payload column instead of a hash map of boxed
 * tuples. String keys are stored in a prefix-compressed front-coded leaf column (auto-selected by
 * {@link ValueColumnFactory#forFilterKey}), which is the memory win driving this backing: URL-slug unique attributes
 * share long common prefixes that a hash map cannot exploit.
 *
 * The index keys every value exactly as the attribute's filter index does - through
 * {@link FilterIndex#getNormalizer(Class, int)} at the attribute's `indexedDecimalPlaces` - so two values the filter
 * index treats as one are one unique value here too, across every collection of the catalog (see
 * {@link UniqueIndexBPlusTreeSupport}). Every entry point converts its value to the key, and the storage parts carry
 * the key as a value of the declared type.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class GlobalUniqueIndex implements
	VoidTransactionMemoryProducer<GlobalUniqueIndex>,
	IndexDataStructure
{
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Constant representing the attribute has no locale assigned.
	 */
	private static final int NO_LOCALE = -1;
	/**
	 * Locale id standing for a locale this index has never assigned an id to. It is only ever compared, never stored:
	 * no tuple carries it, so a lookup or ownership check resolving to it matches nothing.
	 */
	private static final int UNKNOWN_LOCALE = -2;
	/**
	 * Single page stream per global unique index — its value bucket tree (mirrors {@code OwnerUniqueIndex.UNIQUE_PAGE_STREAM}).
	 */
	private static final int UNIQUE_PAGE_STREAM = 0;

	/**
	 * Scope of the {@link CatalogIndex} this unique index belongs to.
	 */
	@Getter private final Scope scope;
	/**
	 * Contains name of the attribute.
	 */
	@Getter private final AttributeKey attributeKey;
	/**
	 * Contains type of the attribute.
	 */
	@Getter private final Class<? extends Serializable> type;
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	@Nonnull private final TransactionalBoolean dirty;
	/**
	 * The plain (array-unwrapped) attribute type — drives the normalizer and leaf-column choice for the value tree.
	 */
	@Nonnull private final Class<? extends Serializable> plainType;
	/**
	 * The scale `BigDecimal` and `BigDecimalNumberRange` values are keyed at, frozen when the index is created (or
	 * loaded) from the attribute schema's `indexedDecimalPlaces`; `0` for every other type. A schema that later
	 * declares another scale refuses further writes until the index is rebuilt - see
	 * {@link FilterIndex#assertIndexedDecimalPlacesUnchanged}.
	 */
	@Getter private final int indexedDecimalPlaces;
	/**
	 * Converts every value to its key in {@link #tree} - the filter index's normalizer for {@link #plainType} at
	 * {@link #indexedDecimalPlaces}, see {@link UniqueIndexBPlusTreeSupport#normalizerFor}.
	 */
	@Nonnull private final Function<Object, Serializable> normalizer;
	/**
	 * Keeps the unique value to entity tuple mappings. Each bucket holds exactly one packed `long` payload (an
	 * {@link EntityWithTypeTuple} folded by {@link #packTuple}); for String keys the leaf column is front-coded.
	 * Ordering by the unique value is irrelevant to look-ups.
	 */
	@Nonnull private final LongPayloadBucketTree tree;
	/**
	 * Catalog-resident page bookkeeping for the granular leaf-page storage layout (the advance-only page allocator, the
	 * high-water and the live-page set of {@link #tree}). It lives OUTSIDE transactional memory and is carried BY
	 * REFERENCE through {@link #createCopyWithMergedTransactionalMemory} (and the catalog-attachment copy, which shares
	 * the same {@link #tree}), exactly like {@code OwnerUniqueIndex}.
	 */
	@Nonnull private final PageStreamRegistry pageStreamRegistry;
	/**
	 * Keeps internal index where each locale has assigned its own unique integer primary key.
	 * These primary keys are assigned internally and don't leave this unique index, but are serialized and deserialized
	 * along with it.
	 */
	@Nonnull private final TransactionalMap<Locale, Integer> localeToIdIndex;
	/**
	 * Keeps reverted index of {@link #localeToIdIndex}.
	 */
	@Nonnull private final TransactionalMap<Integer, Locale> idToLocaleIndex;
	/**
	 * Keeps internal sequence of already assigned primary keys to locales.
	 * The sequence starts with the highest assigned id found in {@link #localeToIdIndex} in constructor.
	 */
	private final AtomicInteger localePkSequence = new AtomicInteger();

	/**
	 * Creates a fresh, empty value tree (long payload column holding the packed entity tuple) keyed like the filter
	 * index — see {@link UniqueIndexBPlusTreeSupport#newLongPayloadTree}.
	 *
	 * @param plainType            the plain (array-unwrapped) attribute type
	 * @param indexedDecimalPlaces the scale `BigDecimal` and `BigDecimalNumberRange` keys are encoded at
	 * @return the fresh empty long-payload bucket tree
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree createEmptyTree(@Nonnull Class<?> plainType, int indexedDecimalPlaces) {
		return UniqueIndexBPlusTreeSupport.newLongPayloadTree(plainType, indexedDecimalPlaces);
	}

	/**
	 * Packs the logical {@link EntityWithTypeTuple} into a single `long` with the layout
	 * `locale:16 | entityType:16 | pk:32`. The {@link #NO_LOCALE} sentinel (-1) is biased to 0 so the locale field stays
	 * within 16 unsigned bits. Both the (biased) locale id and the entity type id MUST fit into their 16-bit fields — a
	 * value that exceeds the field is a broken schema assumption and is rejected loudly rather than silently truncated.
	 *
	 * @param t the tuple to fold into the tree payload
	 * @return the packed `long` payload
	 */
	private static long packTuple(@Nonnull EntityWithTypeTuple t) {
		// bias NO_LOCALE(-1) to 0 so the locale id stays within the unsigned 16-bit high field; the generic packer
		// enforces the 16-bit bounds on both the (biased) locale and the entity type, throwing on a broken assumption
		final int storedLocale = t.locale() + 1;
		return NumberUtils.pack(storedLocale, t.entityType(), t.entityPrimaryKey());
	}

	/**
	 * Inverse of {@link #packTuple}: unfolds a packed `long` tree payload back into the logical {@link EntityWithTypeTuple}.
	 *
	 * @param p the packed `long` payload
	 * @return the reconstructed tuple
	 */
	@Nonnull
	private static EntityWithTypeTuple unpackTuple(long p) {
		final int locale = NumberUtils.unpackHigh16(p) - 1;   // 0 -> NO_LOCALE(-1)
		final int entityType = NumberUtils.unpackMid16(p);
		final int pk = NumberUtils.unpackLow32(p);            // full low 32 bits, sign-preserving
		return new EntityWithTypeTuple(entityType, pk, locale);
	}

	/**
	 * Rebuilds a `PAGED` global unique index from its persisted leaf pages, preserving the original leaf boundaries and
	 * page identities (mirrors {@code OwnerUniqueIndex.fromPersistedPages}). One leaf per persisted page is built from the
	 * positionally-aligned value + packed-`long`-payload columns, each stamped with its page sequence, and the
	 * page-stream bookkeeping (high-water + live set) is restored, so the first post-restart commit rewrites only
	 * genuinely-changed leaves rather than re-paginating the whole index. {@link #localeToIdIndex} +
	 * {@link #localePkSequence} are reconstructed from `idToLocaleIndex` exactly as the inline restore constructors do.
	 *
	 * Every persisted value is converted to its key first. A page whose keys do not come out strictly ascending is
	 * refused, and when the cause is two values naming one key the error names both owners (see
	 * {@link UniqueIndexBPlusTreeSupport#collidingKeysError}).
	 *
	 * @param scope                 scope of the owning {@link CatalogIndex}
	 * @param attributeKey          identifies the indexed attribute (name and optional locale)
	 * @param attributeType         runtime type of the indexed attribute value
	 * @param indexedDecimalPlaces  the attribute schema's `indexedDecimalPlaces`
	 * @param orderedPageSequences  the persisted leaf-page sequences in ascending key order
	 * @param perPageValues         the values of each leaf page, positionally aligned with `orderedPageSequences`
	 * @param perPagePayloads       the packed `long` payloads of each leaf page, positionally aligned with `perPageValues`
	 * @param highWaterPageSequence the persisted stream high-water (largest page sequence ever allocated)
	 * @param idToLocaleIndex       restored mapping of internal locale id to {@link Locale}
	 * @return the rebuilt, boundary-stable `PAGED` global unique index
	 */
	@Nonnull
	public static GlobalUniqueIndex fromPersistedPages(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> attributeType,
		int indexedDecimalPlaces,
		@Nonnull int[] orderedPageSequences,
		@Nonnull Serializable[][] perPageValues,
		@Nonnull long[][] perPagePayloads,
		int highWaterPageSequence,
		@Nonnull Map<Integer, Locale> idToLocaleIndex
	) {
		Assert.isPremiseValid(
			orderedPageSequences.length == perPageValues.length && perPageValues.length == perPagePayloads.length,
			"The number of page sequences must match the number of leaf-page arrays."
		);
		Assert.isPremiseValid(orderedPageSequences.length > 0, "A paged global unique index must have at least one leaf page.");
		final Class<?> plainType = plainTypeOf(attributeType);
		final Function<Object, Serializable> normalizer = normalizerFor(plainType, indexedDecimalPlaces);
		final List<TransactionalBucketBPlusTree> pageTrees = new ArrayList<>(orderedPageSequences.length);
		for (int i = 0; i < orderedPageSequences.length; i++) {
			final Object[] keys = toPersistedKeys(normalizer, perPageValues[i]);
			final long[] payloads = perPagePayloads[i];
			// a page never exceeds a leaf's capacity, so this single-leaf tree never splits; the leaf's key/record
			// columns are built in one bulk pass instead of `values.length` sequential addLongRecord calls, which
			// would otherwise re-decode/re-encode a front-coded String column's whole blob per call - see
			// bulkLoadSingleRecordPage's javadoc
			final TransactionalBucketBPlusTree pageTree = createEmptyTree(plainType, indexedDecimalPlaces);
			try {
				pageTree.bulkLoadSingleRecordPage(keys, payloads, keys.length);
			} catch (GenericEvitaInternalError ex) {
				// the load refused keys that are not strictly ascending - name the owners when two of them collide
				final int clash = findFirstNonAscendingKey(keys);
				if (clash > 0 && UniqueIndexBPlusTreeSupport.KEY_ORDER.compare(
					(Comparable<?>) keys[clash - 1], (Comparable<?>) keys[clash]) == 0) {
					throw collidingKeysError(
						describe(scope, attributeKey), keys[clash], plainType, indexedDecimalPlaces,
						describeOwner(payloads[clash - 1], idToLocaleIndex),
						describeOwner(payloads[clash], idToLocaleIndex)
					);
				}
				throw ex;
			}
			pageTrees.add(pageTree);
		}
		final TransactionalBucketBPlusTree tree =
			createEmptyTree(plainType, indexedDecimalPlaces).assembleFromSingleLeafTrees(
				pageTrees, orderedPageSequences,
				"global unique index for attribute " + attributeKey + " in scope " + scope
			);
		final PageStreamRegistry pageStreamRegistry = PageStreamRegistry.restoredFrom(
			UNIQUE_PAGE_STREAM, highWaterPageSequence, tree.leafPageHandles()
		);
		return new GlobalUniqueIndex(
			scope, attributeKey, attributeType, indexedDecimalPlaces, tree, pageStreamRegistry, idToLocaleIndex
		);
	}

	/**
	 * Creates an empty index for the given attribute. Used when a brand-new unique attribute starts being indexed
	 * and there is no previously persisted state to restore from.
	 *
	 * @param scope                scope of the owning {@link CatalogIndex}
	 * @param attributeKey         identifies the indexed attribute (name and optional locale)
	 * @param attributeType        runtime type of the indexed attribute value
	 * @param indexedDecimalPlaces the attribute schema's `indexedDecimalPlaces` (the scale `BigDecimal` values are
	 *                             keyed at; ignored for other types)
	 */
	public GlobalUniqueIndex(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> attributeType,
		int indexedDecimalPlaces
	) {
		this.dirty = new TransactionalBoolean();
		this.scope = scope;
		this.attributeKey = attributeKey;
		this.type = attributeType;
		this.plainType = plainTypeOf(attributeType);
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.normalizer = normalizerFor(this.plainType, indexedDecimalPlaces);
		this.tree = createEmptyTree(this.plainType, indexedDecimalPlaces);
		this.pageStreamRegistry = new PageStreamRegistry();
		this.localeToIdIndex = new TransactionalMap<>(new HashMap<>());
		this.idToLocaleIndex = new TransactionalMap<>(new HashMap<>());
	}

	/**
	 * Restores a `SINGLE`-shape index from its persisted inline value/payload columns. The value tree is rebuilt by
	 * replaying every `(value, packed-long payload)` pair, each value converted to its key - a pair of values naming
	 * one key refuses to load (see {@link #seedTree}) -, the reverse {@link #localeToIdIndex} is derived from
	 * `localeIndex`, and {@link #localePkSequence} is primed
	 * past the highest locale id already in use so new locales receive fresh ids.
	 *
	 * @param scope                scope of the owning {@link CatalogIndex}
	 * @param attributeKey         identifies the indexed attribute (name and optional locale)
	 * @param attributeType        runtime type of the indexed attribute value
	 * @param indexedDecimalPlaces the attribute schema's `indexedDecimalPlaces`
	 * @param values               restored unique values
	 * @param payloads             restored packed `long` payloads, positionally aligned with `values`
	 * @param localeIndex          restored mapping of internal locale id to {@link Locale}
	 */
	public GlobalUniqueIndex(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> attributeType,
		int indexedDecimalPlaces,
		@Nonnull Serializable[] values,
		@Nonnull long[] payloads,
		@Nonnull Map<Integer, Locale> localeIndex
	) {
		this.dirty = new TransactionalBoolean();
		this.scope = scope;
		this.attributeKey = attributeKey;
		this.type = attributeType;
		this.plainType = plainTypeOf(attributeType);
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.normalizer = normalizerFor(this.plainType, indexedDecimalPlaces);
		this.tree = createEmptyTree(this.plainType, indexedDecimalPlaces);
		this.pageStreamRegistry = new PageStreamRegistry();
		seedTree(values, payloads, localeIndex);
		this.idToLocaleIndex = new TransactionalMap<>(localeIndex);
		primeLocaleSequence(localeIndex.keySet());
		this.localeToIdIndex = new TransactionalMap<>(
			localeIndex.entrySet().stream()
				.collect(
					Collectors.toMap(
						Entry::getValue,
						Entry::getKey
					)
				)
		);
	}

	/**
	 * Adopts an already-built committed value tree (no re-seeding) and re-wraps the committed locale map,
	 * priming {@link #localePkSequence} past the highest locale id and rebuilding {@link #localeToIdIndex} from
	 * `localeIndex`. Used by {@link #createCopyWithMergedTransactionalMemory} where the committed tree already carries
	 * its column kind and contents.
	 *
	 * @param scope                scope of the owning {@link CatalogIndex}
	 * @param attributeKey         identifies the indexed attribute (name and optional locale)
	 * @param attributeType        runtime type of the indexed attribute value
	 * @param indexedDecimalPlaces the scale the keys of `committedTree` are encoded at
	 * @param committedTree        the already-committed value tree to adopt
	 * @param pageStreamRegistry   the catalog-resident page bookkeeping, carried BY REFERENCE
	 * @param localeIndex          committed mapping of internal locale id to {@link Locale}
	 */
	private GlobalUniqueIndex(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> attributeType,
		int indexedDecimalPlaces,
		@Nonnull TransactionalBucketBPlusTree committedTree,
		@Nonnull PageStreamRegistry pageStreamRegistry,
		@Nonnull Map<Integer, Locale> localeIndex
	) {
		this.dirty = new TransactionalBoolean();
		this.scope = scope;
		this.attributeKey = attributeKey;
		this.type = attributeType;
		this.plainType = plainTypeOf(attributeType);
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.normalizer = normalizerFor(this.plainType, indexedDecimalPlaces);
		this.tree = committedTree;
		this.pageStreamRegistry = pageStreamRegistry;
		this.idToLocaleIndex = new TransactionalMap<>(localeIndex);
		primeLocaleSequence(localeIndex.keySet());
		this.localeToIdIndex = new TransactionalMap<>(
			localeIndex.entrySet().stream()
				.collect(
					Collectors.toMap(
						Entry::getValue,
						Entry::getKey
					)
				)
		);
	}

	/**
	 * Primes {@link #localePkSequence} so the next locale registered through {@link #fromLocale} receives an id past
	 * every id already present in the adopted locale map. The sequence must start past the highest adopted locale id;
	 * otherwise a newly seen locale would be handed an id that already belongs to another locale, overwriting it in the
	 * shared reverse map and corrupting locale decoding of every tuple that carries the clobbered id.
	 *
	 * @param assignedLocaleIds the internal locale ids already in use (keys of the id to {@link Locale} map)
	 */
	private void primeLocaleSequence(@Nonnull Set<Integer> assignedLocaleIds) {
		int highestId = this.localePkSequence.get();
		for (final Integer localeId : assignedLocaleIds) {
			if (localeId > highestId) {
				highestId = localeId;
			}
		}
		this.localePkSequence.set(highestId);
	}

	/**
	 * Registers new record id to a single unique value.
	 *
	 * @param resolver translates the entity type name to its compact primary key (and back for violation messages)
	 * @throws UniqueValueViolationException when value is not unique
	 */
	public void registerUniqueKey(@Nonnull Object value, @Nonnull String entityType, @Nullable Locale locale, int recordId, @Nonnull EntityTypeClassifierResolver resolver) {
		final int classifierId = resolver.toEntityTypePrimaryKey(entityType);
		final int localeId = fromLocale(locale);
		registerUniqueKeyValue(value, new EntityWithTypeTuple(classifierId, recordId, localeId), resolver);
	}

	/**
	 * Unregisters new record id from a single unique value.
	 *
	 * @param resolver translates the entity type name to its compact primary key
	 * @return removed record id relation
	 */
	@Nullable
	public EntityReferenceWithLocale unregisterUniqueKey(@Nonnull Object value, @Nonnull String entityType, @Nullable Locale locale, int recordId, @Nonnull EntityTypeClassifierResolver resolver) {
		final int classifierId = resolver.toEntityTypePrimaryKey(entityType);
		// a locale never registered here cannot own the value - resolve it without assigning an id, so the refused
		// removal leaves the locale maps untouched
		final int localeId = lookupLocaleId(locale);
		return unregisterUniqueKeyValue(value, new EntityWithTypeTuple(classifierId, recordId, localeId)) == null ?
			null : new EntityReferenceWithLocale(entityType, recordId, locale);
	}

	/**
	 * Returns record id by its unique value.
	 *
	 * @param resolver translates the compact entity type primary key stored in the tuple back to its name
	 */
	@Nonnull
	public Optional<EntityReferenceWithLocale> getEntityReferenceByUniqueValue(@Nonnull Serializable value, @Nullable Locale locale, @Nonnull EntityTypeClassifierResolver resolver) {
		return ofNullable(lookupTuple(value))
			.filter(it -> locale == null || it.locale() == NO_LOCALE || lookupLocaleId(locale) == it.locale())
			.map(it -> new EntityReferenceWithLocale(resolver.toEntityTypeName(it.entityType()), it.entityPrimaryKey(), toLocale(it.locale())));
	}

	/**
	 * Returns number of unique keys in this index.
	 */
	public int size() {
		return this.tree.size();
	}

	/**
	 * Counts the records this index covers: the distinct `(entity type, primary key)` pairs owning its values.
	 *
	 * This is the global counterpart of {@link UniqueIndex#size()}, and it is what {@link #size()} is *not*: that
	 * counts distinct values. The two agree for an ordinary globally-unique attribute, where one value belongs to one
	 * record, and diverge for a localized one, whose single locale-less key holds a distinct value per locale of the
	 * same entity - and for an array attribute, whose record owns every element. A record id repeated under two entity
	 * types is counted twice, correctly - those are two different entities.
	 *
	 * Counted on demand by one cursor walk over the value tree, whose payload already packs the entity type and
	 * primary key, into a transient bitmap per entity type present: `O(values)`, which is why its only caller is the
	 * catalog index detail (`CatalogIndexProjection#describe`) and never a query path. That caller runs with no
	 * session and no snapshot, concurrently with a warm-up writer mutating the tree in place; the bucket cursors bound
	 * every leaf read by its observable live run for exactly this reader (see
	 * `documentation/adr/2026-09-03-content-sized-value-tree-columns.md`), so a racing walk reads a bounded, possibly
	 * slightly stale count rather than failing.
	 *
	 * @return number of records covered by this index across every entity type
	 */
	public int getRecordCount() {
		// the entity type changes rarely between neighbouring values, so the last type's bitmap is kept at hand and
		// the map is consulted only when the type changes
		final Map<Integer, BaseBitmap> ownersPerType = CollectionUtils.createHashMap(4);
		int lastEntityType = -1;
		BaseBitmap lastOwners = null;
		final BucketCursor cursor = this.tree.cursor();
		while (cursor.next()) {
			final long payload = cursor.longRecordId();
			final int entityType = NumberUtils.unpackMid16(payload);
			if (lastOwners == null || entityType != lastEntityType) {
				lastOwners = ownersPerType.computeIfAbsent(entityType, type -> new BaseBitmap());
				lastEntityType = entityType;
			}
			lastOwners.add(NumberUtils.unpackLow32(payload));
		}
		int total = 0;
		for (final BaseBitmap owners : ownersPerType.values()) {
			total += owners.size();
		}
		return total;
	}

	/**
	 * Returns true if index is empty.
	 */
	public boolean isEmpty() {
		return this.tree.size() == 0;
	}

	/**
	 * Returns whether this index's value tree spans more than one leaf and is therefore persisted in the granular
	 * `PAGED` shape (one entity tuple per value, paged) rather than the inline `SINGLE` shape.
	 *
	 * @return true when the tree has an internal root (≥ 2 leaves)
	 */
	public boolean isPaged() {
		return this.tree.isRootInternal();
	}

	/**
	 * Appends this index's modified storage parts to the flush sink. PAGED: one leaf page per CHANGED leaf, a removal per
	 * freed leaf, plus a PAGED root carrying the high-water, the ordered live leaf-page list and the INLINE locale map.
	 * SINGLE: if the index just collapsed from PAGED, remove every prior leaf page, forget the stream, then write the
	 * inline root. Mirrors {@code OwnerUniqueIndex.appendStorageParts}, but the catalog-level identity is the
	 * `(scope, attributeKey)` pair (no entity index pk) and the locale map always rides on the root.
	 *
	 * @param attribute the indexed attribute key (the catalog-level sub-index identity together with {@link #scope})
	 * @param sink      the flush sink receiving the changed parts
	 */
	public void appendStorageParts(@Nonnull AttributeKey attribute, @Nonnull TrappedChanges sink) {
		if (!this.dirty.isTrue()) {
			return;
		}
		if (this.tree.isRootInternal()) {
			// PAGED: one leaf page per CHANGED leaf + a removal per freed leaf + a PAGED root carrying the high-water,
			// the ordered live leaf-page list and the inline locale map
			final PageEmission<LeafPage> emission = collectChangedPages();
			for (final LeafPage page : emission.changedPages()) {
				sink.addChangeToStore(
					new GlobalUniqueIndexLeafPagePart(this.scope, attribute, page.pageSequence(), page.values(), page.payloads())
				);
			}
			for (final int freedPageSequence : emission.freedPageSequences()) {
				sink.addChangeToStore(new GlobalUniqueIndexLeafPageRemoval(this.scope, attribute, freedPageSequence));
			}
			// NOTE: unlike the pure page-list roots (Chain / OwnerUnique / OwnerSort / FilterIndex), this root also
			// carries the inline idToLocaleIndex, which moves in lockstep with the tree — so it is re-emitted every
			// dirty commit and CANNOT use the PageEmission.pageListChanged() skip. Making it O(1) would need the locale
			// map split into its own sibling storage part (follow-up).
			sink.addChangeToStore(
				GlobalUniqueIndexStoragePart.paged(
					this.scope, attribute, this.type,
					emission.highWaterPageSequence(), emission.orderedPageSequences(), this.idToLocaleIndex, null
				)
			);
		} else {
			// SINGLE shape: the index spans one leaf. If it just collapsed from PAGED, remove every prior leaf page (the
			// inline root no longer references them) BEFORE dropping the bookkeeping, then forget the stream so a later
			// regrow into PAGED starts from a clean baseline and re-emits every leaf.
			// Reclaim against what the previous flush left ON DISK: its staged set while still unpublished (a warm-up
			// flush never reaches the commit-merge that publishes), else the published set. The published set alone lags a
			// whole flush behind, so every page of the collapsed stream would leak — the append-only OffsetIndex never
			// reclaims a record that is neither superseded nor explicitly removed.
			for (final int freedPageSequence : this.pageStreamRegistry.pendingLivePageSequences(UNIQUE_PAGE_STREAM)) {
				sink.addChangeToStore(new GlobalUniqueIndexLeafPageRemoval(this.scope, attribute, freedPageSequence));
			}
			this.pageStreamRegistry.forget(UNIQUE_PAGE_STREAM);
			// the small index is a single embedded leaf: capture its value/payload columns directly off the tree (no map
			// materialization) and carry them inline on the root, exactly as a leaf page would
			final InlineSnapshot snapshot = inlineSnapshot();
			sink.addChangeToStore(
				new GlobalUniqueIndexStoragePart(
					this.scope, attribute, this.type, snapshot.values(), snapshot.payloads(), this.idToLocaleIndex
				)
			);
		}
	}

	/*
		TransactionalLayerCreator implementation
	 */

	/**
	 * Clears the dirty flag once the index contents have been persisted, so subsequent
	 * {@link #appendStorageParts} calls skip an unchanged index.
	 */
	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	/**
	 * Returns the heap this index occupies, in bytes — its own object, its dirty flag, the value tree and all three
	 * lookup maps.
	 *
	 * # What is charged, and what is not
	 *
	 * The value tree is charged in full, its **keys included** — they are attribute values this index owns, priced by
	 * {@link IndexHeapSize#OWNED_KEY_SIZER}.
	 *
	 * The two locale maps charge their **boxed ids but not their locales**. A {@link Locale} comes from the JVM's own
	 * per-language cache and is shared by every structure in the process that names the same language, so it belongs
	 * to none of them. The ids are the opposite case: each map holds its own box, and both are charged, because
	 * whether the JVM hands back a cached `Integer` moves with `-XX:AutoBoxCacheMax` and must not decide what a
	 * memory reading says.
	 *
	 * {@link #scope}, {@link #attributeKey}, {@link #type}, {@link #plainType} and {@link #normalizer} contribute
	 * their **slot alone**: an enum constant, the key the enclosing {@code CatalogIndex} filed this index under, two
	 * `Class` objects, and fixed scaffolding chosen by the attribute type. {@link #pageStreamRegistry} is excluded as
	 * single-writer flush bookkeeping.
	 *
	 * Walking the value tree is `O(values / blockSize)` rather than `O(1)`, so this belongs to the index detail call
	 * and must never be called from a query path.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		final long boxedInteger = layout.sizeOfObject(Integer.BYTES);
		// id, then the scope / attributeKey / type / dirty / plainType / normalizer / tree / pageStreamRegistry /
		// localeToIdIndex / idToLocaleIndex / localePkSequence slots, then the indexedDecimalPlaces int
		return layout.sizeOfObject(Long.BYTES + 11L * layout.referenceSize() + Integer.BYTES)
			+ this.dirty.getHeapSizeInBytes()
			+ this.tree.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER)
			// Locale is interned by the JVM's LocaleObjectCache - only its slot is here, on either side
			+ this.localeToIdIndex.getHeapSizeInBytes(locale -> 0L, value -> boxedInteger)
			+ this.idToLocaleIndex.getHeapSizeInBytes(key -> boxedInteger, locale -> 0L)
			// the sequence's own object holds a single int
			+ layout.sizeOfObject(Integer.BYTES);
	}

	/**
	 * Materializes a new index instance with all transactional changes committed into its backing structures. This is
	 * the commit-time merge step of the STM protocol: the value tree and each transactional child are collapsed to
	 * their committed snapshots and the committed tree is adopted directly (no re-seed).
	 *
	 * The {@link #localeToIdIndex} is not merged directly; the constructor reconstructs it from the committed
	 * {@link #idToLocaleIndex}, so its transactional layer is simply discarded here to avoid a stale orphaned diff.
	 */
	@Nonnull
	@Override
	public GlobalUniqueIndex createCopyWithMergedTransactionalMemory(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final TransactionalBucketBPlusTree committedTree =
			(TransactionalBucketBPlusTree) transactionalLayer.getStateCopyWithCommittedChanges(this.tree);
		// publish the page baseline staged by this commit's flush: the merge runs only AFTER the flush has durably
		// written the changed leaf pages + root, so the staged live set now reflects what is on disk. The registry is
		// then carried BY REFERENCE into the committed copy, so the surviving index keeps it (mirrors OwnerUniqueIndex).
		this.pageStreamRegistry.publishStaged();
		final GlobalUniqueIndex uniqueKeyIndex = new GlobalUniqueIndex(
			this.scope, this.attributeKey, this.type, this.indexedDecimalPlaces,
			committedTree,
			this.pageStreamRegistry,
			transactionalLayer.getStateCopyWithCommittedChanges(this.idToLocaleIndex)
		);
		transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this.localeToIdIndex);
		return uniqueKeyIndex;
	}

	/**
	 * Discards the transactional memory layer of this index and all its transactional children, rolling back any
	 * uncommitted changes. Invoked when a transaction is abandoned rather than committed.
	 */
	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.dirty.removeLayer(transactionalLayer);
		this.tree.removeLayer(transactionalLayer);
		this.localeToIdIndex.removeLayer(transactionalLayer);
		this.idToLocaleIndex.removeLayer(transactionalLayer);
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Returns index of locale ids.
	 */
	@Nonnull
	Map<Integer, Locale> getLocaleIndex() {
		return Collections.unmodifiableMap(this.idToLocaleIndex);
	}

	/**
	 * Returns the whole value tree as sorted, positionally-aligned `(value, packed-long payload)` columns, built by a
	 * single cursor walk — the same shape the `SINGLE` storage part and a leaf page carry. Allocation-lean (no map and no
	 * boxing of the primitive payloads): feeds the inline `SINGLE` write path and the test inspection accessors, so the
	 * expensive whole-tree `HashMap` materialization is never needed.
	 *
	 * @return the inline snapshot of every entry in ascending key order
	 */
	@Nonnull
	InlineSnapshot inlineSnapshot() {
		final CompositeObjectArray<Serializable> snapshotValues = new CompositeObjectArray<>(Serializable.class);
		final CompositeLongArray snapshotPayloads = new CompositeLongArray();
		final BucketCursor cursor = this.tree.cursor();
		while (cursor.next()) {
			snapshotValues.add(toDeclaredValue(this.plainType, this.indexedDecimalPlaces, cursor.value()));
			snapshotPayloads.add(cursor.longRecordId());
		}
		return new InlineSnapshot(snapshotValues.toArray(), snapshotPayloads.toArray());
	}

	/**
	 * Returns array of sorted references maintained by this index. Walks the value tree directly via a cursor (no map
	 * materialization). Still O(n) over the whole index, so it stays a test-only inspection helper.
	 *
	 * @param resolver translates the compact entity type primary key stored in each tuple back to its name
	 */
	@Nonnull
	EntityReference[] getEntityReferences(@Nonnull EntityTypeClassifierResolver resolver) {
		final CompositeObjectArray<EntityReference> references = new CompositeObjectArray<>(EntityReference.class);
		final BucketCursor cursor = this.tree.cursor();
		while (cursor.next()) {
			final EntityWithTypeTuple tuple = unpackTuple(cursor.longRecordId());
			references.add(new EntityReference(resolver.toEntityTypeName(tuple.entityType()), tuple.entityPrimaryKey()));
		}
		final EntityReference[] result = references.toArray();
		Arrays.sort(result);
		return result;
	}

	/**
	 * Looks up the entity tuple for a unique value, converting it to its key first, or `null` when absent.
	 *
	 * @param value the unique value to look up (may be `null` ⇒ `null`)
	 * @return the entity tuple owning the value, or `null` when the value is absent
	 */
	@Nullable
	private EntityWithTypeTuple lookupTuple(@Nullable Serializable value) {
		return value == null ? null : lookupTupleByKey(toKey(this.normalizer, value));
	}

	/**
	 * Looks up the entity tuple for an already converted key, unpacking the tree's `long` payload, or `null` when
	 * absent.
	 *
	 * @param key the tree key
	 * @return the entity tuple owning the key, or `null` when the key is absent
	 */
	@Nullable
	private EntityWithTypeTuple lookupTupleByKey(@Nonnull Comparable<?> key) {
		final OptionalLong packed = this.tree.getLongRecordEqualTo((Comparable) key);
		return packed.isPresent() ? unpackTuple(packed.getAsLong()) : null;
	}

	/**
	 * Packs every persisted entry (positionally-aligned value + packed-`long` payload columns) into the (fresh) value
	 * tree, converting each value to its key. Used by the restore constructor. The keys are distinct, so no overflow
	 * bitmap is ever allocated - and a pair that is not refuses to load: the tree rejects the second key as already
	 * present, and the error is then rethrown naming both owners (see
	 * {@link UniqueIndexBPlusTreeSupport#collidingKeysError}). Nothing is looked up on the way of a load that succeeds.
	 *
	 * @param values      the persisted values
	 * @param payloads    the persisted packed payloads, positionally aligned with `values`
	 * @param localeIndex the persisted locale ids, to name the owners of a collision
	 */
	private void seedTree(
		@Nonnull Serializable[] values,
		@Nonnull long[] payloads,
		@Nonnull Map<Integer, Locale> localeIndex
	) {
		final Object[] keys = toPersistedKeys(this.normalizer, values);
		for (int i = 0; i < keys.length; i++) {
			try {
				this.tree.addLongRecord((Comparable) keys[i], payloads[i]);
			} catch (GenericEvitaInternalError ex) {
				final OptionalLong present = this.tree.getLongRecordEqualTo((Comparable) keys[i]);
				if (present.isPresent()) {
					throw collidingKeysError(
						describe(this.scope, this.attributeKey), keys[i], this.plainType, this.indexedDecimalPlaces,
						describeOwner(present.getAsLong(), localeIndex), describeOwner(payloads[i], localeIndex)
					);
				}
				throw ex;
			}
		}
	}

	/**
	 * Describes the index for an operator: the attribute, its locale when it has one, and the scope.
	 *
	 * @param scope        the scope of the owning catalog index
	 * @param attributeKey the indexed attribute
	 * @return the description
	 */
	@Nonnull
	private static String describe(@Nonnull Scope scope, @Nonnull AttributeKey attributeKey) {
		return "global unique index of attribute `" + attributeKey.attributeName() + "`" +
			(attributeKey.locale() == null ? "" : " in locale `" + attributeKey.locale().toLanguageTag() + "`") +
			" in scope " + scope;
	}

	/**
	 * Describes the owner a persisted payload packs, for an operator. The entity type is named by the id the catalog
	 * assigned it, since the index keeps no names of its own.
	 *
	 * @param payload     the packed payload
	 * @param localeIndex the persisted locale ids
	 * @return the description
	 */
	@Nonnull
	private static String describeOwner(long payload, @Nonnull Map<Integer, Locale> localeIndex) {
		final EntityWithTypeTuple tuple = unpackTuple(payload);
		final Locale locale = tuple.locale() == NO_LOCALE ? null : localeIndex.get(tuple.locale());
		return "entity " + tuple.entityPrimaryKey() + " of the entity type with id " + tuple.entityType() +
			(locale == null ? "" : " in locale `" + locale.toLanguageTag() + "`");
	}

	/**
	 * The whole value tree captured as positionally-aligned value + packed-`long` payload columns — the inline `SINGLE`
	 * shape, the same representation a leaf page carries. Produced by {@link #inlineSnapshot()} via a single cursor walk,
	 * so it never builds an intermediate map.
	 *
	 * @param values   the values in ascending key order
	 * @param payloads the packed payloads, positionally aligned with `values`
	 */
	record InlineSnapshot(@Nonnull Serializable[] values, @Nonnull long[] payloads) {
	}

	/**
	 * Promotes the page set staged by the PREVIOUS flush to the live change-detection baseline, so this flush's freed
	 * -page diff is taken against what disk actually holds.
	 *
	 * {@link #pageStreamRegistry}'s live set answers "which leaf pages does this stream have on disk". {@link
	 * #collectChangedPages()} relies on it for exactly one thing: which pages a leaf merge dropped, so a {@link
	 * GlobalUniqueIndexLeafPageRemoval} is emitted and the page is actually removed from storage — the ordered
	 * leaf-page list carried by the `PAGED` root, by contrast, is read straight off the current tree leaves every time
	 * (see {@link #appendStorageParts}, which re-emits that root unconditionally because it also carries the inline
	 * locale map), so it is never stale regardless of this baseline. That live set only ever advances by {@link
	 * PageStreamRegistry#publishStaged()}, which {@link #createCopyWithMergedTransactionalMemory} calls at the
	 * transactional commit-merge.
	 *
	 * A WARM_UP (bulk) flush never reaches a commit-merge — it runs the same collect path but is never wrapped in a
	 * transaction, so nothing ever calls {@code createCopyWithMergedTransactionalMemory} for it. Left alone, the live
	 * set of a freshly re-indexed catalog would stay EMPTY for the whole warm-up while disk moved on underneath it. A
	 * leaf MERGE (unlike a split) drops a page without creating one: the surviving leaf absorbs its sibling IN PLACE,
	 * keeping its own page sequence and dirty flag, so nothing is freshly allocated. With an empty live baseline the
	 * freed-page diff for that merge is vacuously empty, so the dropped page is never removed from storage — an
	 * unreferenced leaf-page record that every future compaction copies forward forever even though the (always
	 * correctly re-emitted) root no longer points at it.
	 *
	 * Publishing HERE, before every flush rather than only at the commit-merge, is safe for every path because a failed
	 * flush is never followed by another flush of the same data. This call runs at COLLECT time, before this flush has
	 * written anything (the baseline-capture pass re-enters the collect path), so it cannot rest on the previous
	 * flush's bytes having landed — and it does not need to. A flush that fails during trunk incorporation SUSPENDS the
	 * catalog's transaction processing ({@code TransactionManager.suspend}); a flush that fails during warm-up POISONS
	 * the catalog unpublishable ({@code Catalog.markUnpublishable}), so every later flush of it refuses
	 * deterministically. The two are the same invariant in different dresses: after a failed flush nothing ever diffs
	 * against the baseline it left behind, because no later flush of that data runs at all. Whatever a SUCCEEDING flush
	 * leaves staged is exactly the page set it wrote, regardless of whether a commit-merge ever ran for it. (If the
	 * process crashes instead, the registry itself is gone and gets rebuilt from disk on restart, where a burnt page
	 * sequence is harmless since allocation is advance-only.) On the transactional path this call is simply a no-op
	 * (the merge already published, so nothing is left staged) — that is a side effect, not the reason it is safe.
	 */
	private void publishPreviousFlush() {
		this.pageStreamRegistry.publishStaged();
	}

	/**
	 * Walks the value tree leaf-by-leaf and returns the granular write-path emission for this commit: the leaf pages that
	 * changed since the last flush, the full ordered list of live leaf-page sequences (the `PAGED` root's leaf list), the
	 * stream high-water, and the freed page sequences a leaf merge dropped. Mirrors
	 * {@code OwnerUniqueIndex.collectChangedPages} with the slim value + packed-`long`-payload columns.
	 *
	 * Before staging, any set still staged by the PREVIOUS flush is promoted to live — see
	 * {@link #publishPreviousFlush()} for why that is both necessary and safe.
	 *
	 * @return the changed leaf pages, the ordered live page-sequence list, the high-water, and the freed pages
	 */
	@Nonnull
	private PageEmission<LeafPage> collectChangedPages() {
		publishPreviousFlush();
		// this.tree is a raw bucket tree, so the handle list and its cursors are raw too — bucket values are read as
		// Object and cast to Serializable exactly as the whole-tree snapshot does
		final List<LeafPageHandle> handles = this.tree.leafPageHandles();
		return this.pageStreamRegistry.collectChangedPages(
			UNIQUE_PAGE_STREAM, handles,
			(pageSequence, handle) -> {
				final BucketCursor cursor = handle.cursor();
				final CompositeObjectArray<Serializable> pageValues = new CompositeObjectArray<>(Serializable.class);
				final CompositeLongArray pagePayloads = new CompositeLongArray();
				while (cursor.next()) {
					pageValues.add(toDeclaredValue(this.plainType, this.indexedDecimalPlaces, cursor.value()));
					pagePayloads.add(cursor.longRecordId());
				}
				return new LeafPage(pageSequence, pageValues.toArray(), pagePayloads.toArray());
			}
		);
	}

	/**
	 * Registers a record under a unique key that may be either a single value or an array of values (array-typed
	 * attributes occupy every contained value). Every value is converted to its key first. For arrays, uniqueness of
	 * all elements is verified up front before any element is inserted, so a violation leaves the index unchanged
	 * (all-or-nothing); the array is folded onto its distinct keys first, so a value the array repeats - in any
	 * spelling - occupies its single tree entry once.
	 *
	 * @param key    the unique value, or array of unique values, to claim
	 * @param record the entity tuple claiming the value(s)
	 * @param resolver translates entity type primary keys to names for the violation message
	 * @throws UniqueValueViolationException when any value is already owned by any record
	 */
	@SuppressWarnings("unchecked")
	private <T extends Serializable & Comparable<T>> void registerUniqueKeyValue(@Nonnull Object key, @Nonnull EntityWithTypeTuple record, @Nonnull EntityTypeClassifierResolver resolver) {
		if (key instanceof @Nonnull final Object[] valueArray) {
			// one value is one tree entry however many times the array repeats it - see #foldOntoDistinctValues
			final Object[] distinctKeys = toDistinctKeys(this.normalizer, valueArray);
			// first verify removed data without modifications
			for (Object keyItem : distinctKeys) {
				final T theKeyItem = (T) keyItem;
				assertUniqueKeyIsFree(theKeyItem, record, lookupTupleByKey(theKeyItem), resolver);
			}
			// now perform alteration
			for (Object keyItem : distinctKeys) {
				registerUniqueKeyValue((T) keyItem, record, resolver);
			}
		} else {
			registerUniqueKeyValue((T) toKey(this.normalizer, key), record, resolver);
		}
		this.dirty.setToTrue();
	}

	/**
	 * Claims a single scalar unique key for the given record.
	 *
	 * Only an absent key can be claimed (see {@link #assertUniqueKeyIsFree}), so every key holds exactly one tuple.
	 *
	 * @param key    the scalar unique key to claim, already converted by {@link UniqueIndexBPlusTreeSupport#toKey}
	 * @param record the entity tuple claiming the value
	 * @param resolver translates entity type primary keys to names for the violation message
	 * @throws UniqueValueViolationException when the value is already owned by any record
	 */
	private <T extends Serializable & Comparable<T>> void registerUniqueKeyValue(
		@Nonnull T key,
		@Nonnull EntityWithTypeTuple record,
		@Nonnull EntityTypeClassifierResolver resolver
	) {
		assertUniqueKeyIsFree(key, record, lookupTupleByKey(key), resolver);
		this.tree.addLongRecord(key, packTuple(record));
	}

	/**
	 * Releases a unique key that may be either a single value or an array of values, the inverse of
	 * {@link #registerUniqueKeyValue(Object, EntityWithTypeTuple, EntityTypeClassifierResolver)}. Every value is
	 * converted to its key first. Ownership of every element is verified up front so a mismatch leaves the index
	 * unchanged (all-or-nothing), and the array is folded onto its distinct keys first, so a value the array repeats -
	 * in any spelling - is retired once rather than being sought a second time after its only entry is gone.
	 *
	 * @param key            the unique value, or array of unique values, to release
	 * @param expectedRecord the record expected to currently own the value(s)
	 * @return the released tuple for a scalar key, or `null` for an array key (per-element results are not aggregated)
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	private <T extends Serializable & Comparable<T>> EntityWithTypeTuple unregisterUniqueKeyValue(@Nonnull Object key, @Nonnull EntityWithTypeTuple expectedRecord) {
		if (key instanceof @Nonnull final Object[] valueArray) {
			// one value is one tree entry however many times the array repeats it - see #foldOntoDistinctValues
			final Object[] distinctKeys = toDistinctKeys(this.normalizer, valueArray);
			// first verify removed data without modifications
			for (Object keyItem : distinctKeys) {
				final T theKeyItem = (T) keyItem;
				assertUniqueKeyOwnership(theKeyItem, expectedRecord, lookupTupleByKey(theKeyItem));
			}
			// now perform alteration
			for (Object keyItem : distinctKeys) {
				unregisterUniqueKeyValue((T) keyItem, expectedRecord);
			}
			this.dirty.setToTrue();
			return null;
		} else {
			final EntityWithTypeTuple originalValue =
				unregisterUniqueKeyValue((T) toKey(this.normalizer, key), expectedRecord);
			this.dirty.setToTrue();
			return originalValue;
		}
	}

	/**
	 * Releases a single scalar unique key after asserting it is owned by the expected record. The assertion runs
	 * first, so a key owned by someone else - or not present at all - is left untouched rather than removed and
	 * then complained about.
	 *
	 * @param key             the scalar unique key to release, already converted by
	 *                        {@link UniqueIndexBPlusTreeSupport#toKey}
	 * @param expectedRecordId the record expected to currently own the value
	 * @return the tuple that previously owned the value - always equal to `expectedRecordId`
	 */
	@Nonnull
	private <T extends Serializable & Comparable<T>> EntityWithTypeTuple unregisterUniqueKeyValue(@Nonnull T key, EntityWithTypeTuple expectedRecordId) {
		final EntityWithTypeTuple existingRecordId = lookupTupleByKey(key);
		assertUniqueKeyOwnership(key, expectedRecordId, existingRecordId);
		this.tree.removeLongRecord(key);
		return expectedRecordId;
	}

	/**
	 * Verifies the value can be claimed by `record`: it must be unowned - **even by the very same tuple**.
	 *
	 * `uniqueGlobally` means once per catalog whatever the locale, so an owned value is a second occurrence no matter
	 * who holds it: another entity, the same entity in another locale, or the same entity in the same locale. The
	 * last one never arrives from the upsert path, which unregisters a record's prior value before registering the
	 * new one; were it tolerated, one tree entry would stand for two registrations and the first unregister would
	 * drop the value the second still holds. `uniqueGloballyWithinLocale` needs no exemption either, because it keys
	 * one index per locale.
	 *
	 * @param key            the unique key being claimed (shown as a value of the declared type in the violation)
	 * @param record         the record attempting to claim the value
	 * @param existingRecord the record currently owning the value, or `null` if unowned
	 * @param resolver       translates entity type primary keys to names for the violation message
	 * @throws UniqueValueViolationException when the value is already owned by any record
	 */
	private <T extends Serializable & Comparable<T>> void assertUniqueKeyIsFree(@Nonnull T key, EntityWithTypeTuple record, @Nullable EntityWithTypeTuple existingRecord, @Nonnull EntityTypeClassifierResolver resolver) {
		if (existingRecord != null) {
			throw new UniqueValueViolationException(
				this.attributeKey.attributeName(), this.attributeKey.locale(),
				toDeclaredValue(this.plainType, this.indexedDecimalPlaces, key),
				resolver.toEntityTypeName(existingRecord.entityType()), existingRecord.entityPrimaryKey(),
				resolver.toEntityTypeName(record.entityType()), record.entityPrimaryKey()
			);
		}
	}

	/**
	 * Resolves an internal locale id stored in tuples back to its {@link Locale}, returning `null` for the
	 * {@link #NO_LOCALE} sentinel (attribute value with no locale).
	 */
	@Nullable
	private Locale toLocale(int locale) {
		return locale == NO_LOCALE ? null : Objects.requireNonNull(this.idToLocaleIndex.get(locale));
	}

	/**
	 * Resolves a {@link Locale} to its internal locale id without assigning one - the read counterpart of
	 * {@link #fromLocale}. A locale never registered here resolves to {@link #UNKNOWN_LOCALE}, which no tuple carries.
	 *
	 * Reads and ownership checks must use this: assigning an id writes the locale maps (and bumps the sequence), which
	 * outside a transaction would mutate the committed maps from a query thread and inside one would dirty its layer,
	 * all for a locale that cannot match anything.
	 *
	 * @param locale the locale to resolve, `null` for a value with no locale
	 * @return the assigned locale id, {@link #NO_LOCALE} for `null`, or {@link #UNKNOWN_LOCALE} for an unseen locale
	 */
	private int lookupLocaleId(@Nullable Locale locale) {
		if (locale == null) {
			return NO_LOCALE;
		}
		final Integer localeId = this.localeToIdIndex.get(locale);
		return localeId == null ? UNKNOWN_LOCALE : localeId;
	}

	/**
	 * Resolves a {@link Locale} to its internal locale id, lazily assigning a fresh id (and registering it in both
	 * locale indexes) when the locale is seen for the first time. Returns {@link #NO_LOCALE} for a `null` locale.
	 */
	private int fromLocale(@Nullable Locale locale) {
		return locale == null ? NO_LOCALE : this.localeToIdIndex.computeIfAbsent(
			locale,
			theLocale -> {
				final int assignedId = this.localePkSequence.incrementAndGet();
				this.idToLocaleIndex.put(assignedId, theLocale);
				return assignedId;
			}
		);
	}

	/**
	 * Ensures that the unique key is owned by the expected record.
	 *
	 * @param theKey           the unique key to check (shown as a value of the declared type in the failure message)
	 * @param expectedRecordId the expected record that should own the key
	 * @param existingRecordId the existing record that currently owns the key, can be null
	 */
	private <T extends Serializable & Comparable<T>> void assertUniqueKeyOwnership(
		@Nonnull T theKey,
		@Nonnull EntityWithTypeTuple expectedRecordId,
		@Nullable EntityWithTypeTuple existingRecordId
	) {
		final Serializable key = toDeclaredValue(this.plainType, this.indexedDecimalPlaces, theKey);
		isTrue(
			Objects.equals(existingRecordId, expectedRecordId),
			() -> existingRecordId == null ?
				"No unique key exists for `" + this.attributeKey.attributeName() + "` key: `" + key + "`!" :
				"Unique key exists for `" + this.attributeKey.attributeName() + "` key: `" + key + "` belongs to record with id `" + existingRecordId + "` and not `" + expectedRecordId + "` as expected!"
		);
	}

	/**
	 * One changed leaf page of the granular write-path emission: its page sequence and its slim `(value, payload)`
	 * columns in ascending key order.
	 *
	 * @param pageSequence the leaf's page sequence within the stream
	 * @param values       the leaf's values in ascending key order
	 * @param payloads     the single packed `long` payload owning each value, aligned with `values`
	 */
	private record LeafPage(int pageSequence, @Nonnull Serializable[] values, @Nonnull long[] payloads) {
	}

	/**
	 * Internal representation of the entity reference optimized for low memory consumption.
	 *
	 * @param entityType       the entity type primary key
	 * @param entityPrimaryKey the primary key of the entity
	 * @param locale           the locale of associated key
	 */
	public record EntityWithTypeTuple(
		int entityType,
		int entityPrimaryKey,
		int locale
	) {
	}

}
