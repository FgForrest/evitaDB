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

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * Stateless VIEW variant of {@link UniqueIndex}, folded onto the shared `value→ValueToRecord` tree owned by
 * {@link AttributeIndex}. It owns no value map / record-id bitmap of its own and answers every read directly from the
 * shared {@link FilterIndex} view it references ({@link #sharedFilterView}); uniqueness is enforced by
 * {@link AttributeIndex} on the filter insert.
 *
 * Used for any foldable unique attribute (a non-localized attribute, or a localized one unique within locale), whose
 * unique key equals its filter key. The view participates in no commit: its {@link #createLayer()} yields no layer,
 * {@link #createCopyWithMergedTransactionalMemory} is an identity, and {@link #registerUniqueKey} /
 * {@link #unregisterUniqueKey} throw (the shared filter tree owns the data). On every commit {@link AttributeIndex}
 * carries the view forward by reference when the referenced filter view is identity-unchanged, or rebuilds it over the
 * freshly-committed filter view otherwise ({@link #bindFilterView}) — exactly like the {@link FilterIndexView} and
 * {@link SortIndexView}. Because the reference is never reassigned on a published instance, a committed view shared with
 * an older live snapshot can never be made to observe a newer filter view (no snapshot-isolation hazard).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class UniqueIndexView extends UniqueIndex {
	@Serial private static final long serialVersionUID = 2639205026498958518L;
	/**
	 * Direct reference to the shared {@link FilterIndex} view over the same attribute key — the source of truth this
	 * folded view reads from (reusing its normalizer and memoized all-records bitmap). May be `null` for a transient
	 * live presence marker created before the shared tree exists; the filter-write path rebinds it to the live filter
	 * view via {@link #bindFilterView}. Never reassigned on a published instance — a new view is built instead, so the
	 * field is safe to share across snapshot versions. Transient because a reloaded view is reconstructed from the
	 * shared trees, not deserialized.
	 */
	@Nullable private final FilterIndex sharedFilterView;

	/**
	 * Creates a view folded onto the shared `sharedFilterView`.
	 *
	 * @param entityType        type of the entity this index belongs to
	 * @param attributeIndexKey key identifying the indexed attribute
	 * @param attributeType     declared type of the attribute value
	 * @param sharedFilterView  the shared filter view over the same key (may be `null` for a not-yet-bound live marker)
	 */
	UniqueIndexView(
		@Nonnull String entityType,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> attributeType,
		@Nullable FilterIndex sharedFilterView
	) {
		super(entityType, attributeIndexKey, attributeType);
		this.sharedFilterView = sharedFilterView;
	}

	@Nonnull
	@Override
	UniqueIndex bindFilterView(@Nullable FilterIndex committedFilterView) {
		// O(Δ) carry-forward: when the committed filter view is the very instance this view already references (an
		// untouched key — the filter views carry unchanged trees forward by identity), the view is already exactly
		// correct, so return it unchanged and share it across snapshot versions (safe: never mutated in place). Only a
		// replaced (or first-bound) filter view needs a fresh view. Never reassign the field on a published instance.
		return committedFilterView == this.sharedFilterView
			? this
			: new UniqueIndexView(getEntityType(), getAttributeIndexKey(), getType(), committedFilterView);
	}

	@Override
	public void registerUniqueKey(@Nonnull Object value, int recordId) {
		// a folded (view-mode) unique index never registers values itself - the shared filter tree owns the data and
		// uniqueness is enforced by AttributeIndex on the filter insert
		throw new GenericEvitaInternalError("registerUniqueKey must not be called on a folded (view-mode) unique index!");
	}

	@Override
	public int unregisterUniqueKey(@Nonnull Object value, int recordId) {
		throw new GenericEvitaInternalError("unregisterUniqueKey must not be called on a folded (view-mode) unique index!");
	}

	@Nullable
	@Override
	public Integer getRecordIdByUniqueValue(@Nonnull Serializable value) {
		final FilterIndex filterView = this.sharedFilterView;
		if (filterView == null) {
			return null;
		}
		// the bucket of a unique value holds at most one record (uniqueness is enforced on insert)
		final Bitmap records = filterView.getRecordsEqualTo(value);
		return records.isEmpty() ? null : records.getFirst();
	}

	@Override
	public Formula getRecordIdsFormula() {
		// wrap the filter view's already-memoized all-records bitmap over the same shared tree - the formula itself
		// is built fresh per call, because an index-lifetime one would pin the calling query's execution context
		final FilterIndex filterView = this.sharedFilterView;
		return filterView == null ? EmptyFormula.INSTANCE : filterView.getAllRecordsFormula();
	}

	@Nonnull
	@Override
	public Bitmap getRecordIds() {
		final FilterIndex filterView = this.sharedFilterView;
		return filterView == null ? EmptyBitmap.INSTANCE : filterView.getAllRecords();
	}

	@Override
	public int size() {
		final FilterIndex filterView = this.sharedFilterView;
		return filterView == null ? 0 : filterView.size();
	}

	/**
	 * {@inheritDoc}
	 *
	 * A view that is not yet bound to a shared tree reports `0`, exactly as {@link #size()} does - it is a live
	 * presence marker created before the tree exists, so it genuinely holds no values yet.
	 */
	@Override
	public int getDistinctValueCount() {
		final FilterIndex filterView = this.sharedFilterView;
		return filterView == null ? 0 : filterView.getDistinctValueCount();
	}

	@Override
	public boolean isEmpty() {
		final FilterIndex filterView = this.sharedFilterView;
		return filterView == null || filterView.isEmpty();
	}

	/**
	 * {@inheritDoc}
	 *
	 * {@link #sharedFilterView} contributes its **slot alone**. The filter view — and the shared tree beneath it —
	 * belongs to the enclosing {@code AttributeIndex}, which charges it once; a folded view holds no transactional
	 * state of its own and is rebuilt fresh against the committed tree on every commit. Charging it here would report
	 * the same values twice for every attribute that is both unique and filterable, which is all of them.
	 */
	@Override
	public long getHeapSizeInBytes() {
		// the sharedFilterView slot, on top of the base's own fields - and nothing beyond it
		return getSharedHeapSizeInBytes(VMLayout.current().referenceSize());
	}

	@Override
	public void appendStorageParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges sink) {
		// a folded view never pages: it emits a single SLIM part (no value map / record-id bitmap) - just the manifest
		// signal that this attribute key is unique, so the view and its enforcement are reconstructed on reload. Emit
		// only when the shared tree is dirty (its data lives in the shared FilterIndexStoragePart, persisted separately).
		final FilterIndex filterView = this.sharedFilterView;
		if (filterView != null && filterView.isDirty()) {
			sink.addChangeToStore(
				new UniqueIndexStoragePart(entityIndexPrimaryKey, getAttributeIndexKey(), getType())
			);
		}
	}

	@Override
	public void resetDirty() {
		// a view owns no dirty flag - the shared tree's dirtiness is reset through the FilterIndex view
	}

	@Nonnull
	@Override
	public UniqueIndex createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// view instances are non-transactional and rebuilt fresh over the committed shared tree by AttributeIndex;
		// the commit machinery never reaches them, but stay defensive and identity-return if it ever does
		return this;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// a view never registered its placeholder structures with the layer - nothing else to discard
	}

	@Nonnull
	@Override
	InlineSnapshot inlineSnapshot() {
		// a folded view owns no inline columns - its data lives in the shared filter tree
		return new InlineSnapshot(new Serializable[0], new int[0]);
	}

}
