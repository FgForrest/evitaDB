/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.spi.store.catalog.persistence.storageParts.index;

import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.index.attribute.ChainIndex.ChainElementState;
import io.evitadb.spi.store.catalog.persistence.storageParts.RecordWithCompressedId;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.util.Map;
import java.util.Objects;

/**
 * Chain index container stores index for single {@link AttributeSchema} of the single
 * {@link EntitySchema}. This container object serves only as a storage carrier for
 * {@link io.evitadb.index.attribute.ChainIndex} which is a live memory representation of the data stored in this
 * container. {@link #isPaged()} discriminates the two persisted shapes:
 *
 * - SINGLE: the whole chain state is carried inline — the {@link #chains} runs plus the fat {@link #elementStates}
 *   map. This is the original shape a small chain index (or a legacy catalog) persists as one monolithic record.
 * - PAGED: the root carries only the page-stream metadata ({@link #highWaterPageSequence} plus the ordered live
 *   {@link #pageSequences}); the element data lives in separate {@link ChainIndexLeafPagePart} records and the chain
 *   state ({@link #chains} / {@link #elementStates}) is reconstructed from the reloaded value tree on load. PAGED
 *   roots therefore carry EMPTY {@link #chains} / {@link #elementStates}.
 *
 * The PAGED root reuses the SAME {@link #computeUniquePartId} (the byte-24 storage-part type packs the owning entity
 * index id and the compressor id of {@code AttributeKeyWithIndexType(attribute, CHAIN)}), so the first paged flush of
 * an upgraded catalog supersedes the old monolithic record naturally.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@ToString(of = "attributeIndexKey")
public class ChainIndexStoragePart implements AttributeIndexStoragePart, RecordWithCompressedId<AttributeIndexKey> {
	@Serial private static final long serialVersionUID = 4729183650274619038L;

	/**
	 * Shared empty element-state map carried by a PAGED root (whose element data lives in the leaf pages).
	 */
	private static final Map<Integer, ChainElementState> EMPTY_ELEMENT_STATES = Map.of();
	/**
	 * Shared empty chain-run array carried by a PAGED root (whose element data lives in the leaf pages).
	 */
	private static final int[][] EMPTY_CHAINS = new int[0][];

	/**
	 * Unique id that identifies {@link io.evitadb.index.EntityIndex}.
	 */
	@Getter private final Integer entityIndexPrimaryKey;
	/**
	 * Contains name and locale of the indexed attribute.
	 */
	@Getter private final AttributeIndexKey attributeIndexKey;
	/**
	 * Index contains information about non-interrupted chains of predecessors for an entity which is not a head entity
	 * but is part of different chain (inconsistent state). EMPTY on a PAGED root — it is reconstructed from the reloaded
	 * value tree on load.
	 */
	@Getter private final Map<Integer, ChainElementState> elementStates;
	/**
	 * Index contains tuples of entity primary key and its predecessor primary key. The conflicting primary key is
	 * a value and the predecessor primary key is a key.
	 *
	 * Conflicting keys are keys that:
	 *
	 * - refer to the same predecessor multiple times
	 * - refer to the predecessor that is transiently referring to them (circular reference)
	 *
	 * The key is the conflicting primary key and the value is the predecessor primary key. EMPTY on a PAGED root — it is
	 * reconstructed from the reloaded value tree on load.
	 */
	@Getter private final int[][] chains;
	/**
	 * Whether this chain index is paged out as individual {@link ChainIndexLeafPagePart} leaf-page records (`true`) or
	 * carried inline on this root (`false`). A small (single-leaf) chain index and a legacy catalog are both `false`;
	 * the discriminator only flips to `true` for a multi-leaf index whose value tree is persisted granularly. PAGED
	 * roots carry EMPTY {@link #chains} / {@link #elementStates} — the element data is read back from the leaf pages and
	 * the chain state is reconstructed from the reloaded tree on load.
	 */
	@Getter private final boolean paged;
	/**
	 * PAGED shape only: the maximum page sequence ever allocated for the chain's value-tree page stream; `0` for a
	 * SINGLE root.
	 */
	@Getter private final int highWaterPageSequence;
	/**
	 * PAGED shape only: every live leaf's page sequence in ascending key order (the PAGED root's leaf list); `null` for
	 * a SINGLE root.
	 */
	@Nullable @Getter private final int[] pageSequences;
	/**
	 * Id used for lookups in persistent data storage for this particular container.
	 */
	@Nullable @Getter @Setter private Long storagePartPK;

	/**
	 * Creates a fresh SINGLE chain index part whose storage part PK is not yet assigned (computed before persistence).
	 * Used by the live index flush path ({@link io.evitadb.index.attribute.ChainIndex#appendStorageParts}).
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param attributeIndexKey     identifies the indexed attribute
	 * @param elementStates         the per-element chain state map
	 * @param chains                the chain runs
	 */
	public ChainIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Map<Integer, ChainElementState> elementStates,
		@Nonnull int[][] chains
	) {
		this(entityIndexPrimaryKey, attributeIndexKey, elementStates, chains, false, 0, null, null);
	}

	/**
	 * Creates a SINGLE chain index part carrying the already-assigned storage part PK. Used by the serializer read path
	 * and the load / migration paths.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param attributeIndexKey     identifies the indexed attribute
	 * @param elementStates         the per-element chain state map
	 * @param chains                the chain runs
	 * @param storagePartPK         the precomputed primary key
	 */
	public ChainIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Map<Integer, ChainElementState> elementStates,
		@Nonnull int[][] chains,
		@Nullable Long storagePartPK
	) {
		this(entityIndexPrimaryKey, attributeIndexKey, elementStates, chains, false, 0, null, storagePartPK);
	}

	/**
	 * Private all-fields canonical constructor carrying the PAGED/SINGLE discriminator. Every public constructor and the
	 * {@link #paged(Integer, AttributeIndexKey, int, int[])} factories funnel through here. A SINGLE part passes
	 * `paged == false` (with the inline chain state and the empty page metadata); a PAGED part passes `paged == true`
	 * with empty inline chain state and the page-stream metadata.
	 */
	private ChainIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Map<Integer, ChainElementState> elementStates,
		@Nonnull int[][] chains,
		boolean paged,
		int highWaterPageSequence,
		@Nullable int[] pageSequences,
		@Nullable Long storagePartPK
	) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.attributeIndexKey = attributeIndexKey;
		this.elementStates = elementStates;
		this.chains = chains;
		this.paged = paged;
		this.highWaterPageSequence = highWaterPageSequence;
		this.pageSequences = pageSequences;
		this.storagePartPK = storagePartPK;
	}

	/**
	 * Creates a write-path PAGED root carrying only the chain value tree's page-stream metadata (the element data lives
	 * in separate {@link ChainIndexLeafPagePart} records); the storage part PK is assigned before persistence.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param attributeIndexKey     identifies the indexed attribute
	 * @param highWaterPageSequence the maximum page sequence ever allocated for the value-tree page stream
	 * @param pageSequences         every live leaf's page sequence in ascending key order
	 * @return the write-path PAGED root storage part
	 */
	@Nonnull
	public static ChainIndexStoragePart paged(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		int highWaterPageSequence,
		@Nonnull int[] pageSequences
	) {
		return new ChainIndexStoragePart(
			entityIndexPrimaryKey, attributeIndexKey, EMPTY_ELEMENT_STATES, EMPTY_CHAINS,
			true, highWaterPageSequence, pageSequences, null
		);
	}

	/**
	 * Creates a read-path PAGED root with an already-known primary key (used when rehydrating from storage); carries
	 * only the page-stream metadata, the inline chain state being empty.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param attributeIndexKey     identifies the indexed attribute
	 * @param highWaterPageSequence the maximum page sequence ever allocated for the value-tree page stream
	 * @param pageSequences         every live leaf's page sequence in ascending key order
	 * @param storagePartPK         the precomputed primary key
	 * @return the read-path PAGED root storage part
	 */
	@Nonnull
	public static ChainIndexStoragePart paged(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		int highWaterPageSequence,
		@Nonnull int[] pageSequences,
		@Nonnull Long storagePartPK
	) {
		return new ChainIndexStoragePart(
			entityIndexPrimaryKey, attributeIndexKey, EMPTY_ELEMENT_STATES, EMPTY_CHAINS,
			true, highWaterPageSequence, pageSequences, storagePartPK
		);
	}

	/**
	 * Returns {@link #getPageSequences()}, throwing when this part carries none (a SINGLE root, i.e. {@link #isPaged()}
	 * is `false`). PAGED-path callers use this instead of dereferencing the `@Nullable` getter directly, so the non-null
	 * invariant is asserted once here rather than relied upon silently at every call site.
	 *
	 * @return the ordered live leaf page sequences
	 */
	@Nonnull
	public int[] getPageSequencesOrThrowException() {
		return Objects.requireNonNull(
			this.pageSequences, "Paged chain index part must carry its leaf page sequences!"
		);
	}

	@Nonnull
	@Override
	public AttributeIndexType getIndexType() {
		return AttributeIndexType.CHAIN;
	}

	@Override
	public AttributeIndexKey getStoragePartSourceKey() {
		return this.attributeIndexKey;
	}


}
