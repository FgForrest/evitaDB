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
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.map.MapChanges;
import io.evitadb.index.map.PersistentTransactionalMap;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

import static io.evitadb.utils.Assert.isTrue;
import static io.evitadb.utils.StringUtils.unknownToString;

/**
 * Unique index maintains information about single unique attribute - its value to record id relation.
 * It protects duplicate unique attribute insertion and allows to easily translate unique attribute value to record id
 * that occupies it.
 *
 * This is the abstract, sealed base of the owner/view hierarchy. It carries the common identity (entity type, attribute
 * key, value type) and declares the read / persistence / transactional surface, but owns no data of its own. Two
 * concrete shapes exist:
 *
 * - {@link OwnerUniqueIndex} — a standalone index that owns its value→record-id map (a {@link PersistentTransactionalMap}
 *   backed by a persistent immutable {@link io.evitadb.dataType.champ.ChampMap}) and a record-id bitmap, fully
 *   participating in the commit cycle. Used for global-unique-localized attributes whose locale-less uniqueness cannot
 *   be folded into the per-locale shared filter tree.
 * - {@link UniqueIndexView} — a stateless view folded onto the shared `value→ValueToRecord` tree owned by
 *   {@link AttributeIndex}: it owns no data and answers every read from the shared {@link FilterIndex} view over that
 *   tree (uniqueness is enforced on the filter insert by {@link AttributeIndex}). Used for any non-localized attribute,
 *   or a localized one unique within locale.
 *
 * The base remains a {@link TransactionalLayerProducer} so the standalone owners can still live in
 * {@link AttributeIndex}'s producer {@code TransactionalMap}; the view simply overrides the producer hooks to be
 * inert (it lives in a plain non-producer map and is rebuilt fresh over the committed shared tree on each commit).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public abstract sealed class UniqueIndex implements
	TransactionalLayerProducer<TransactionalContainerChanges<MapChanges<Serializable, Integer>, Map<Serializable, Integer>, PersistentTransactionalMap<Serializable, Integer>>, UniqueIndex>,
	IndexDataStructure,
	Serializable
	permits OwnerUniqueIndex, UniqueIndexView {
	@Serial private static final long serialVersionUID = 2639205026498958516L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Contains type of the entity this index belongs to.
	 */
	@Getter private final String entityType;
	/**
	 * Contains key identifying the attribute.
	 */
	@Getter private final AttributeIndexKey attributeIndexKey;
	/**
	 * Contains type of the attribute.
	 */
	@Getter private final Class<? extends Serializable> type;

	/**
	 * Verifies that the component type of an array of unique values is both {@link Serializable} and
	 * {@link Comparable} - the contract every key stored in this index must satisfy.
	 *
	 * @param value array whose component type is checked
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when the component type is not {@link Serializable}
	 *         or not {@link Comparable}
	 */
	static void verifyValueArray(@Nonnull Object value) {
		isTrue(Serializable.class.isAssignableFrom(value.getClass().getComponentType()), "Value `" + unknownToString(value) + "` is expected to be Serializable but it is not!");
		isTrue(Comparable.class.isAssignableFrom(value.getClass().getComponentType()), "Value `" + unknownToString(value) + "` is expected to be Comparable but it is not!");
	}

	/**
	 * Verifies that a single unique value is both {@link Serializable} and {@link Comparable} - the contract every
	 * key stored in this index must satisfy.
	 *
	 * @param value value to check
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when the value is not {@link Serializable} or not
	 *         {@link Comparable}
	 */
	static void verifyValue(@Nonnull Object value) {
		isTrue(value instanceof Serializable, "Value `" + unknownToString(value) + "` is expected to be Serializable but it is not!");
		isTrue(value instanceof Comparable, "Value `" + unknownToString(value) + "` is expected to be Comparable but it is not!");
	}

	/**
	 * Creates a VIEW-mode index folded onto the shared `value→ValueToRecord` tree - the entry point used by
	 * {@link AttributeIndex} for a foldable unique attribute. The instance owns no value map / record-id bitmap of its
	 * own; every read is answered directly from the shared {@link FilterIndex} view it references.
	 *
	 * @param entityType        type of the entity this index belongs to
	 * @param attributeIndexKey key identifying the indexed attribute
	 * @param attributeType     declared type of the attribute value
	 * @param sharedFilterView  the shared filter view over the same key (may be `null` when the shared tree does not
	 *                          exist yet — a live presence marker rebound by the filter-write path)
	 * @return a fresh view-mode unique index
	 */
	@Nonnull
	public static UniqueIndex createView(
		@Nonnull String entityType,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> attributeType,
		@Nullable FilterIndex sharedFilterView
	) {
		return new UniqueIndexView(entityType, attributeIndexKey, attributeType, sharedFilterView);
	}

	/**
	 * Returns a copy of this index referencing the freshly-committed `committedFilterView`, so a folded view never reads
	 * through a stale filter view. The result is a NEW immutable instance when the filter view differs, or `this` when it
	 * is identity-unchanged (carry-forward) — never an in-place mutation, so the returned value is safe to share across
	 * snapshot versions. A no-op (`this`) for owner-mode indexes (they own their value map). Mirrors
	 * {@link SortIndex#bindSharedTree} so all three folded view families carry forward by identity symmetrically.
	 *
	 * @param committedFilterView the committed shared filter view for this key (ignored in owner mode; may be `null`)
	 * @return a view bound to the committed filter view, or `this` when nothing changed
	 */
	@Nonnull
	abstract UniqueIndex bindFilterView(@Nullable FilterIndex committedFilterView);

	/**
	 * Base constructor wiring the common identity fields. Concrete subclasses are responsible for sourcing their data
	 * (owned vs shared) and the transactional lifecycle.
	 *
	 * @param entityType        type of the entity this index belongs to
	 * @param attributeIndexKey key identifying the indexed attribute
	 * @param attributeType     declared type of the attribute value
	 */
	protected UniqueIndex(
		@Nonnull String entityType,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> attributeType
	) {
		this.entityType = entityType;
		this.attributeIndexKey = attributeIndexKey;
		this.type = attributeType;
	}

	/**
	 * Registers new record id to a single unique value (owner only - a folded view never registers values itself, the
	 * shared filter tree owns the data and uniqueness is enforced by {@link AttributeIndex} on the filter insert).
	 *
	 * @throws UniqueValueViolationException when value is not unique
	 */
	public abstract void registerUniqueKey(@Nonnull Object value, int recordId);

	/**
	 * Unregisters a record id from a single unique value (owner only).
	 *
	 * @return removed record id relation
	 */
	public abstract int unregisterUniqueKey(@Nonnull Object value, int recordId);

	/**
	 * Returns record id by its unique value.
	 */
	@Nullable
	public abstract Integer getRecordIdByUniqueValue(@Nonnull Serializable value);

	/**
	 * Returns formula that contains all records (and memoized result).
	 */
	public abstract Formula getRecordIdsFormula();

	/**
	 * Returns bitmap with all record ids registered in this unique index.
	 */
	@Nonnull
	public abstract Bitmap getRecordIds();

	/**
	 * Returns number of records in this index.
	 */
	public abstract int size();

	/**
	 * Returns true if index is empty.
	 */
	public abstract boolean isEmpty();

	/**
	 * Method creates container for storing unique index from memory to the persistent storage.
	 */
	@Nullable
	public abstract StoragePart createStoragePart(int entityIndexPrimaryKey);

	/**
	 * Returns an unmodifiable view of the unique value to record id mappings - exposed for persistence
	 * ({@link #createStoragePart}) and load-time reconstruction, not for mutation. A view returns an empty map (its
	 * data lives in the shared filter tree).
	 */
	@Nonnull
	abstract Map<Serializable, Integer> getUniqueValueToRecordId();

}
