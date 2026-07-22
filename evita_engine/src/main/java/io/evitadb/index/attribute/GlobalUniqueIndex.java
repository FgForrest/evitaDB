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

import io.evitadb.api.CatalogState;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.CatalogRelatedDataStructure;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.array.CompositeLongArray;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bPlusTree.LongPayloadBucketTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.LeafPageHandle;
import io.evitadb.index.bPlusTree.ValueColumnFactory;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
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
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static io.evitadb.index.attribute.UniqueIndex.verifyValue;
import static io.evitadb.index.attribute.UniqueIndex.verifyValueArray;
import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.comparatorFor;
import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.plainTypeOf;
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
 * {@link ValueColumnFactory#forKey}), which is the memory win driving this backing: URL-slug unique attributes share
 * long common prefixes that a hash map cannot exploit.
 *
 * The index keeps RAW values (no normalization) ordered by a comparator consistent with value equality — natural order
 * for every type except {@link BigDecimal}, which uses an exact value+scale order so {@code 1.0} and {@code 1.00} stay
 * distinct unique keys (matching the {@code HashMap} semantics this backing replaces).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class GlobalUniqueIndex implements
	VoidTransactionMemoryProducer<GlobalUniqueIndex>,
	IndexDataStructure,
	CatalogRelatedDataStructure<GlobalUniqueIndex>
{
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Constant representing the attribute has no locale assigned.
	 */
	private static final int NO_LOCALE = -1;
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
	 * The plain (array-unwrapped) attribute type — drives the comparator and leaf-column choice for the value tree.
	 */
	@Nonnull private final Class<? extends Serializable> plainType;
	/**
	 * The value order used by {@link #tree} — see {@link UniqueIndexBPlusTreeSupport#comparatorFor} (natural order, or
	 * the scale-preserving exact order for `BigDecimal`).
	 */
	@Nonnull private final Comparator<Comparable<?>> comparator;
	/**
	 * Keeps the unique value to entity tuple mappings. Each bucket holds exactly one packed `long` payload (an
	 * {@link EntityWithTypeTuple} folded by {@link #packTuple}); for String keys the leaf column is front-coded.
	 * Ordering by the unique value is irrelevant to look-ups — per-type record ordering is carried by
	 * {@link #entitiesPerType}.
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
	 * Keeps the lists of primary keys per entity type.
	 */
	@Nonnull private final TransactionalMap<Integer, TransactionalBitmap> entitiesPerType;
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
	 * Contains reference to the current catalog instance.
	 * Beware this reference changes with each entity collection exchange during transactional commit.
	 * The reference is used to translate {@link EntityCollection#getEntityType()} to {@link EntityCollection#getEntityTypePrimaryKey()}
	 * and vice versa. We want to use short int ids in {@link EntityWithTypeTuple} so that we save a few bytes for object
	 * pointer.
	 */
	private Catalog catalog;
	/**
	 * Maps entity type primary key to entity type name.
	 */
	private final Map<Integer, String> primaryKeyToEntityType = new ConcurrentHashMap<>();
	/**
	 * Maps entity type name to entity type primary key.
	 */
	private final Map<String, Integer> entityTypeToPk = new ConcurrentHashMap<>();

	/**
	 * Creates a fresh, empty value tree (long payload column holding the packed entity tuple) ordered by the given
	 * comparator — see {@link UniqueIndexBPlusTreeSupport#newLongPayloadTree}.
	 *
	 * @param plainType  the plain (array-unwrapped) attribute type
	 * @param comparator the value order
	 * @return the fresh empty long-payload bucket tree
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree createEmptyTree(
		@Nonnull Class<?> plainType,
		@Nonnull Comparator<Comparable<?>> comparator
	) {
		return UniqueIndexBPlusTreeSupport.newLongPayloadTree(plainType, comparator);
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
	 * genuinely-changed leaves rather than re-paginating the whole index. The {@link #entitiesPerType} index is rebuilt by
	 * UNPACKING every payload, and {@link #localeToIdIndex} + {@link #localePkSequence} are reconstructed from
	 * `idToLocaleIndex` exactly as the inline restore constructors do.
	 *
	 * @param scope                 scope of the owning {@link CatalogIndex}
	 * @param attributeKey          identifies the indexed attribute (name and optional locale)
	 * @param attributeType         runtime type of the indexed attribute value
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
		final Comparator<Comparable<?>> comparator = comparatorFor(plainType);
		final List<TransactionalBucketBPlusTree> pageTrees = new ArrayList<>(orderedPageSequences.length);
		final Map<Integer, TransactionalBitmap> entitiesPerTypeBase = CollectionUtils.createHashMap(8);
		for (int i = 0; i < orderedPageSequences.length; i++) {
			final Serializable[] values = perPageValues[i];
			final long[] payloads = perPagePayloads[i];
			// a page never exceeds a leaf's capacity, so this single-leaf tree never splits; the leaf's key/record
			// columns are built in one bulk pass instead of `values.length` sequential addLongRecord calls, which
			// would otherwise re-decode/re-encode a front-coded String column's whole blob per call - see
			// bulkLoadSingleRecordPage's javadoc
			final TransactionalBucketBPlusTree pageTree = createEmptyTree(plainType, comparator);
			pageTree.bulkLoadSingleRecordPage(values, payloads, values.length);
			for (int j = 0; j < values.length; j++) {
				final EntityWithTypeTuple tuple = unpackTuple(payloads[j]);
				entitiesPerTypeBase.computeIfAbsent(tuple.entityType(), entityType -> new TransactionalBitmap())
					.add(tuple.entityPrimaryKey());
			}
			pageTrees.add(pageTree);
		}
		final TransactionalBucketBPlusTree tree =
			createEmptyTree(plainType, comparator).assembleFromSingleLeafTrees(
				pageTrees, orderedPageSequences,
				"global unique index for attribute " + attributeKey + " in scope " + scope
			);
		final PageStreamRegistry pageStreamRegistry = PageStreamRegistry.restoredFrom(
			UNIQUE_PAGE_STREAM, highWaterPageSequence, tree.leafPageHandles()
		);
		return new GlobalUniqueIndex(
			scope, attributeKey, attributeType, tree, pageStreamRegistry, entitiesPerTypeBase, idToLocaleIndex
		);
	}

	/**
	 * Creates an empty index for the given attribute. Used when a brand-new unique attribute starts being indexed
	 * and there is no previously persisted state to restore from.
	 *
	 * @param scope         scope of the owning {@link CatalogIndex}
	 * @param attributeKey  identifies the indexed attribute (name and optional locale)
	 * @param attributeType runtime type of the indexed attribute value
	 */
	public GlobalUniqueIndex(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> attributeType
	) {
		this.dirty = new TransactionalBoolean();
		this.scope = scope;
		this.attributeKey = attributeKey;
		this.type = attributeType;
		this.plainType = plainTypeOf(attributeType);
		this.comparator = comparatorFor(this.plainType);
		this.tree = createEmptyTree(this.plainType, this.comparator);
		this.pageStreamRegistry = new PageStreamRegistry();
		this.entitiesPerType = new TransactionalMap<>(new HashMap<>(), TransactionalBitmap.class, TransactionalBitmap::new);
		this.localeToIdIndex = new TransactionalMap<>(new HashMap<>());
		this.idToLocaleIndex = new TransactionalMap<>(new HashMap<>());
	}

	/**
	 * Restores a `SINGLE`-shape index from its persisted inline value/payload columns. The value tree is rebuilt by
	 * replaying every `(value, packed-long payload)` pair, the {@link #entitiesPerType} index is rebuilt by unpacking each
	 * payload, the reverse {@link #localeToIdIndex} is derived from `localeIndex`, and {@link #localePkSequence} is primed
	 * past the highest locale id already in use so new locales receive fresh ids.
	 *
	 * @param scope         scope of the owning {@link CatalogIndex}
	 * @param attributeKey  identifies the indexed attribute (name and optional locale)
	 * @param attributeType runtime type of the indexed attribute value
	 * @param values        restored unique values in ascending key order
	 * @param payloads      restored packed `long` payloads, positionally aligned with `values`
	 * @param localeIndex   restored mapping of internal locale id to {@link Locale}
	 */
	public GlobalUniqueIndex(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> attributeType,
		@Nonnull Serializable[] values,
		@Nonnull long[] payloads,
		@Nonnull Map<Integer, Locale> localeIndex
	) {
		this.dirty = new TransactionalBoolean();
		this.scope = scope;
		this.attributeKey = attributeKey;
		this.type = attributeType;
		this.plainType = plainTypeOf(attributeType);
		this.comparator = comparatorFor(this.plainType);
		this.tree = createEmptyTree(this.plainType, this.comparator);
		this.pageStreamRegistry = new PageStreamRegistry();
		seedTree(values, payloads);
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
		// rebuild the per-entity-type record bitmaps by unpacking each persisted payload
		final Map<Integer, TransactionalBitmap> entitiesPerTypeBase = CollectionUtils.createHashMap(8);
		for (final long payload : payloads) {
			final EntityWithTypeTuple tuple = unpackTuple(payload);
			entitiesPerTypeBase.computeIfAbsent(tuple.entityType(), entityType -> new TransactionalBitmap())
				.add(tuple.entityPrimaryKey());
		}
		this.entitiesPerType = new TransactionalMap<>(entitiesPerTypeBase, TransactionalBitmap.class, TransactionalBitmap::new);
	}

	/**
	 * Adopts an already-built committed value tree (no re-seeding) and re-wraps the committed per-type / locale maps,
	 * priming {@link #localePkSequence} past the highest locale id and rebuilding {@link #localeToIdIndex} from
	 * `localeIndex`. Used by {@link #createCopyWithMergedTransactionalMemory} where the committed tree already carries
	 * its column kind and contents.
	 *
	 * @param scope         scope of the owning {@link CatalogIndex}
	 * @param attributeKey  identifies the indexed attribute (name and optional locale)
	 * @param attributeType runtime type of the indexed attribute value
	 * @param committedTree the already-committed value tree to adopt
	 * @param pageStreamRegistry the catalog-resident page bookkeeping, carried BY REFERENCE
	 * @param entitiesPerType committed per-entity-type record id bitmaps to re-wrap
	 * @param localeIndex   committed mapping of internal locale id to {@link Locale}
	 */
	private GlobalUniqueIndex(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> attributeType,
		@Nonnull TransactionalBucketBPlusTree committedTree,
		@Nonnull PageStreamRegistry pageStreamRegistry,
		@Nonnull Map<Integer, TransactionalBitmap> entitiesPerType,
		@Nonnull Map<Integer, Locale> localeIndex
	) {
		this.dirty = new TransactionalBoolean();
		this.scope = scope;
		this.attributeKey = attributeKey;
		this.type = attributeType;
		this.plainType = plainTypeOf(attributeType);
		this.comparator = comparatorFor(this.plainType);
		this.tree = committedTree;
		this.pageStreamRegistry = pageStreamRegistry;
		this.entitiesPerType = new TransactionalMap<>(entitiesPerType, TransactionalBitmap.class, TransactionalBitmap::new);
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
	 * Adopts already-wrapped transactional structures directly instead of re-wrapping plain maps. Used by
	 * {@link #createCopyForNewCatalogAttachment(CatalogState)} to produce a detached copy that shares the same
	 * transactional backing structures while resetting the catalog reference.
	 *
	 * @param scope           scope of the owning {@link CatalogIndex}
	 * @param attributeKey    identifies the indexed attribute (name and optional locale)
	 * @param attributeType   runtime type of the indexed attribute value
	 * @param tree            value to entity tuple tree to adopt
	 * @param pageStreamRegistry the catalog-resident page bookkeeping, carried BY REFERENCE (shares the same `tree`)
	 * @param entitiesPerType per-entity-type record id bitmaps to adopt
	 * @param localeToIdIndex {@link Locale} to internal locale id mapping to adopt
	 * @param idToLocaleIndex reverse internal locale id to {@link Locale} mapping to adopt
	 * @param localePkSequenceSeed the source index's current locale-id high-water used to seed this copy's sequence
	 */
	private GlobalUniqueIndex(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> attributeType,
		@Nonnull LongPayloadBucketTree tree,
		@Nonnull PageStreamRegistry pageStreamRegistry,
		@Nonnull TransactionalMap<Integer, TransactionalBitmap> entitiesPerType,
		@Nonnull TransactionalMap<Locale, Integer> localeToIdIndex,
		@Nonnull TransactionalMap<Integer, Locale> idToLocaleIndex,
		int localePkSequenceSeed
	) {
		this.attributeKey = attributeKey;
		this.scope = scope;
		this.type = attributeType;
		this.dirty = new TransactionalBoolean();
		this.plainType = plainTypeOf(attributeType);
		this.comparator = comparatorFor(this.plainType);
		this.tree = tree;
		this.pageStreamRegistry = pageStreamRegistry;
		this.entitiesPerType = entitiesPerType;
		this.localeToIdIndex = localeToIdIndex;
		this.idToLocaleIndex = idToLocaleIndex;
		// the locale maps are adopted BY REFERENCE and already carry assigned ids; the sequence must start past the
		// highest id ever assigned by the source (which the source's high-water reflects even for ids added but not yet
		// committed to the shared map), otherwise a first-time locale registered through this copy would be handed an
		// id that already belongs to another locale and overwrite it in the shared reverse map. The source high-water is
		// read from a plain (non-transactional) sequence, so seeding never touches the transactional layer of the shared
		// maps — a layer that may be mid-commit when this shell is created.
		this.localePkSequence.set(localePkSequenceSeed);
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
	 * Captures the catalog reference needed to translate between entity type names and their short int primary keys
	 * (see {@link #catalog}). Enforces single attachment: the index must not already be bound to a catalog.
	 */
	@Override
	public void attachToCatalog(@Nullable String entityType, @Nonnull Catalog catalog) {
		Assert.isPremiseValid(this.catalog == null, "Catalog was already attached to this index!");
		this.catalog = catalog;
	}

	/**
	 * Produces a detached copy that shares the same transactional backing structures but carries no catalog reference,
	 * so it can be reattached to a new catalog version while the original stays bound to the previous one. The cached
	 * entity-type-to-pk lookups ({@link #primaryKeyToEntityType}, {@link #entityTypeToPk}) are intentionally not
	 * carried over — they are rebuilt lazily against the freshly attached catalog.
	 */
	@Nonnull
	@Override
	public GlobalUniqueIndex createCopyForNewCatalogAttachment(@Nonnull CatalogState catalogState) {
		return new GlobalUniqueIndex(
			this.scope,
			this.attributeKey,
			this.type,
			this.tree,
			this.pageStreamRegistry,
			this.entitiesPerType,
			this.localeToIdIndex,
			this.idToLocaleIndex,
			this.localePkSequence.get()
		);
	}

	/**
	 * Registers new record id to a single unique value.
	 *
	 * @throws UniqueValueViolationException when value is not unique
	 */
	public void registerUniqueKey(@Nonnull Object value, @Nonnull String entityType, @Nullable Locale locale, int recordId) {
		final int classifierId = fromClassifier(entityType);
		final int localeId = fromLocale(locale);
		registerUniqueKeyValue(value, new EntityWithTypeTuple(classifierId, recordId, localeId));
	}

	/**
	 * Unregisters new record id from a single unique value.
	 *
	 * @return removed record id relation
	 */
	@Nullable
	public EntityReferenceWithLocale unregisterUniqueKey(@Nonnull Object value, @Nonnull String entityType, @Nullable Locale locale, int recordId) {
		final int classifierId = fromClassifier(entityType);
		final int localeId = fromLocale(locale);
		return unregisterUniqueKeyValue(value, new EntityWithTypeTuple(classifierId, recordId, localeId)) == null ?
			null : new EntityReferenceWithLocale(entityType, recordId, locale);
	}

	/**
	 * Returns record id by its unique value.
	 */
	@Nonnull
	public Optional<EntityReferenceWithLocale> getEntityReferenceByUniqueValue(@Nonnull Serializable value, @Nullable Locale locale) {
		return ofNullable(lookupTuple(value))
			.filter(it -> locale == null || it.locale() == NO_LOCALE || fromLocale(locale) == it.locale())
			.map(it -> new EntityReferenceWithLocale(toClassifier(it.entityType()), it.entityPrimaryKey(), toLocale(it.locale())));
	}

	/**
	 * Generates a {@link Formula} instance that provides the record IDs associated with the specified entity type.
	 *
	 * @param entityType the type of the entity for which to generate the record IDs formula
	 * @return a {@link Formula} instance that computes the record IDs for the given entity type
	 */
	@Nonnull
	public Formula getRecordIdsFormula(@Nonnull String entityType) {
		final Bitmap recordIds = getRecordIds(entityType);
		return recordIds instanceof EmptyBitmap ? EmptyFormula.INSTANCE : new ConstantFormula(recordIds);
	}

	/**
	 * Retrieves the record IDs associated with a specific entity type.
	 *
	 * @param entityType the type of the entity for which record IDs are being retrieved
	 * @return a Bitmap containing the record IDs for the specified entity type
	 */
	@Nonnull
	public Bitmap getRecordIds(@Nonnull String entityType) {
		final int entityTypePk = fromClassifier(entityType);
		return ofNullable(this.entitiesPerType.get(entityTypePk))
			.map(Bitmap.class::cast)
			.orElse(EmptyBitmap.INSTANCE);
	}

	/**
	 * Returns number of unique keys in this index.
	 */
	public int size() {
		return this.tree.size();
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
			this.scope, this.attributeKey, this.type,
			committedTree,
			this.pageStreamRegistry,
			transactionalLayer.getStateCopyWithCommittedChanges(this.entitiesPerType),
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
		this.entitiesPerType.removeLayer(transactionalLayer);
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
			snapshotValues.add((Serializable) cursor.value());
			snapshotPayloads.add(cursor.longRecordId());
		}
		return new InlineSnapshot(snapshotValues.toArray(), snapshotPayloads.toArray());
	}

	/**
	 * Returns array of sorted references maintained by this index. Walks the value tree directly via a cursor (no map
	 * materialization). Still O(n) over the whole index, so it stays a test-only inspection helper.
	 */
	@Nonnull
	EntityReference[] getEntityReferences() {
		final CompositeObjectArray<EntityReference> references = new CompositeObjectArray<>(EntityReference.class);
		final BucketCursor cursor = this.tree.cursor();
		while (cursor.next()) {
			final EntityWithTypeTuple tuple = unpackTuple(cursor.longRecordId());
			references.add(new EntityReference(toClassifier(tuple.entityType()), tuple.entityPrimaryKey()));
		}
		final EntityReference[] result = references.toArray();
		Arrays.sort(result);
		return result;
	}

	/**
	 * Looks up the entity tuple for a unique value, unpacking the tree's `long` payload, or `null` when absent.
	 *
	 * @param value the unique value to look up (may be `null` ⇒ `null`)
	 * @return the entity tuple owning the value, or `null` when the value is absent
	 */
	@Nullable
	private EntityWithTypeTuple lookupTuple(@Nullable Serializable value) {
		final OptionalLong packed = this.tree.getLongRecordEqualTo((Comparable) value);
		return packed.isPresent() ? unpackTuple(packed.getAsLong()) : null;
	}

	/**
	 * Packs every persisted entry (positionally-aligned value + packed-`long` payload columns) into the (fresh) value
	 * tree. Used by the restore constructor; the values are distinct unique keys, so no overflow bitmap is ever allocated.
	 *
	 * @param values   the persisted values in ascending key order
	 * @param payloads the persisted packed payloads, positionally aligned with `values`
	 */
	private void seedTree(@Nonnull Serializable[] values, @Nonnull long[] payloads) {
		for (int i = 0; i < values.length; i++) {
			this.tree.addLongRecord((Comparable) values[i], payloads[i]);
		}
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
	 * the collection's buffer ({@code WarmUpDataStoreMemoryBuffer.poison}), so every later collect of it refuses
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
					pageValues.add((Serializable) cursor.value());
					pagePayloads.add(cursor.longRecordId());
				}
				return new LeafPage(pageSequence, pageValues.toArray(), pagePayloads.toArray());
			}
		);
	}

	/**
	 * Registers a record under a unique key that may be either a single value or an array of values (array-typed
	 * attributes occupy every contained value). For arrays, uniqueness of all elements is verified up front before
	 * any element is inserted, so a violation leaves the index unchanged (all-or-nothing).
	 *
	 * @param key    the unique value, or array of unique values, to claim
	 * @param record the entity tuple claiming the value(s)
	 * @throws UniqueValueViolationException when any value is already owned by a different record
	 */
	@SuppressWarnings("unchecked")
	private <T extends Serializable & Comparable<T>> void registerUniqueKeyValue(@Nonnull Object key, @Nonnull EntityWithTypeTuple record) {
		if (key instanceof @Nonnull final Object[] valueArray) {
			verifyValueArray(key);
			// first verify removed data without modifications
			for (Object valueItem : valueArray) {
				final T theValueItem = (T) valueItem;
				final EntityWithTypeTuple existingRecordId = lookupTuple(theValueItem);
				assertUniqueKeyIsFree(theValueItem, record, existingRecordId);
			}
			// now perform alteration
			for (Object valueItem : valueArray) {
				registerUniqueKeyValue(valueItem, record);
			}
		} else {
			verifyValue(key);
			registerUniqueKeyValue((T) key, record);
		}
		this.dirty.setToTrue();
	}

	/**
	 * Claims a single scalar unique value for the given record and adds the record's primary key to the matching
	 * per-entity-type bitmap, keeping the value tree and {@link #entitiesPerType} in lockstep.
	 *
	 * The value→tuple insert reproduces the overwrite semantics of the {@code HashMap.put} it replaces: an absent value
	 * is added; an already-present value owned by a *different* tuple (the cross-locale coexistence allowed for a
	 * localized attribute) is replaced (the tree is UNIQUE so the bucket is removed then re-added); an idempotent
	 * re-registration by the very same tuple is a no-op on the tree (the payload is already identical).
	 *
	 * @param key    the scalar unique value to claim
	 * @param record the entity tuple claiming the value
	 * @throws UniqueValueViolationException when the value is already owned by a different record in the same locale
	 */
	private <T extends Serializable & Comparable<T>> void registerUniqueKeyValue(@Nonnull T key, @Nonnull EntityWithTypeTuple record) {
		final EntityWithTypeTuple existingRecordId = lookupTuple(key);
		assertUniqueKeyIsFree(key, record, existingRecordId);
		if (existingRecordId == null) {
			this.tree.addLongRecord(key, packTuple(record));
		} else if (!existingRecordId.equals(record)) {
			// cross-locale coexistence for a localized attribute: overwrite the value→tuple mapping exactly like the
			// HashMap.put this backing replaces (entitiesPerType keeps every pk, see below)
			this.tree.removeLongRecord(key);
			this.tree.addLongRecord(key, packTuple(record));
		}
		this.entitiesPerType
			.computeIfAbsent(record.entityType(), entityType -> new TransactionalBitmap())
			.add(record.entityPrimaryKey());
	}

	/**
	 * Releases a unique key that may be either a single value or an array of values, the inverse of
	 * {@link #registerUniqueKeyValue(Object, EntityWithTypeTuple)}. Ownership of every element is verified up front
	 * so a mismatch leaves the index unchanged (all-or-nothing).
	 *
	 * @param key            the unique value, or array of unique values, to release
	 * @param expectedRecord the record expected to currently own the value(s)
	 * @return the released tuple for a scalar key, or `null` for an array key (per-element results are not aggregated)
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	private <T extends Serializable & Comparable<T>> EntityWithTypeTuple unregisterUniqueKeyValue(@Nonnull Object key, @Nonnull EntityWithTypeTuple expectedRecord) {
		if (key instanceof @Nonnull final Object[] valueArray) {
			verifyValueArray(key);
			// first verify removed data without modifications
			for (Object valueItem : valueArray) {
				final T theValueItem = (T) valueItem;
				final EntityWithTypeTuple existingRecord = lookupTuple(theValueItem);
				assertUniqueKeyOwnership(theValueItem, expectedRecord, existingRecord);
			}
			// now perform alteration
			for (Object valueItem : valueArray) {
				unregisterUniqueKeyValue((T) valueItem, expectedRecord);
			}
			this.dirty.setToTrue();
			return null;
		} else {
			verifyValue(key);
			final EntityWithTypeTuple originalValue = unregisterUniqueKeyValue((T) key, expectedRecord);
			this.dirty.setToTrue();
			return originalValue;
		}
	}

	/**
	 * Releases a single scalar unique value and removes the record's primary key from the matching per-entity-type
	 * bitmap, then asserts the value was actually owned by the expected record.
	 *
	 * @param key             the scalar unique value to release
	 * @param expectedRecordId the record expected to currently own the value
	 * @return the tuple that previously owned the value, or `null` if the value was not present
	 */
	@Nullable
	private <T extends Serializable & Comparable<T>> EntityWithTypeTuple unregisterUniqueKeyValue(@Nonnull T key, EntityWithTypeTuple expectedRecordId) {
		final EntityWithTypeTuple existingRecordId = lookupTuple(key);
		if (existingRecordId != null) {
			this.tree.removeLongRecord(key);
			// the per-type bitmap is maintained in lockstep with the value tree in registerUniqueKeyValue, so a present
			// value tuple guarantees a present bitmap here
			final TransactionalBitmap entityTypeRecords = this.entitiesPerType.get(existingRecordId.entityType());
			Assert.isPremiseValid(
				entityTypeRecords != null,
				() -> "Entity type `" + existingRecordId.entityType() + "` unexpectedly missing from the per-type index!"
			);
			entityTypeRecords.remove(existingRecordId.entityPrimaryKey());
		}
		assertUniqueKeyOwnership(key, expectedRecordId, existingRecordId);
		return existingRecordId;
	}

	/**
	 * Verifies the value can be claimed by `record`: it must be unowned, or already owned by the very same record.
	 * For a localized attribute the same value is allowed to coexist across different locales, so a clash only
	 * counts as a violation when the two records share the locale.
	 *
	 * @param key            the unique value being claimed (for error reporting)
	 * @param record         the record attempting to claim the value
	 * @param existingRecord the record currently owning the value, or `null` if unowned
	 * @throws UniqueValueViolationException when the value is already owned by a different record in the same locale
	 */
	private <T extends Serializable & Comparable<T>> void assertUniqueKeyIsFree(@Nonnull T key, EntityWithTypeTuple record, @Nullable EntityWithTypeTuple existingRecord) {
		if (!(existingRecord == null || existingRecord.equals(record))) {
			if (!this.attributeKey.localized() || existingRecord.locale() == record.locale()) {
				throw new UniqueValueViolationException(
					this.attributeKey.attributeName(), this.attributeKey.locale(), key,
					toClassifier(existingRecord.entityType()), existingRecord.entityPrimaryKey(),
					toClassifier(record.entityType()), record.entityPrimaryKey()
				);
			}
		}
	}

	/**
	 * Resolves the compact entity-type primary key stored in tuples back to the entity type name, caching the
	 * result. Requires the index to be attached to a catalog.
	 */
	@Nonnull
	private String toClassifier(int entityType) {
		return this.primaryKeyToEntityType.computeIfAbsent(
			entityType,
			epk -> {
				final EntityCollection entityCollection = this.catalog.getCollectionForEntityPrimaryKeyOrThrowException(epk);
				return entityCollection.getEntityType();
			}
		);
	}

	/**
	 * Resolves an entity type name to the compact primary key stored in tuples, caching the result. The compact id
	 * keeps {@link EntityWithTypeTuple} small. Requires the index to be attached to a catalog.
	 */
	private int fromClassifier(@Nonnull String entityType) {
		return this.entityTypeToPk.computeIfAbsent(
			entityType,
			et -> this.catalog.getCollectionForEntityOrThrowException(et).getEntityTypePrimaryKey()
		);
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
	 * @param key             the unique key to check
	 * @param expectedRecordId the expected record that should own the key
	 * @param existingRecordId the existing record that currently owns the key, can be null
	 */
	private <T extends Serializable & Comparable<T>> void assertUniqueKeyOwnership(
		@Nonnull T key,
		@Nonnull EntityWithTypeTuple expectedRecordId,
		@Nullable EntityWithTypeTuple existingRecordId
	) {
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
