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

import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.ChainableType;
import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.array.UnorderedLookup;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexStoragePart;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PrimitiveIterator.OfInt;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.dataType.ChainableType.HEAD_PK;
import static io.evitadb.utils.Assert.isPremiseValid;
import static io.evitadb.utils.Assert.isTrue;
import static java.util.Optional.ofNullable;

/**
 * This is a special index for data type of {@link io.evitadb.dataType.ChainableType}.
 * Semi-consistent orders by:
 *
 * - the longest chain of elements starting with the head element
 * - others chains of elements starting with the head element (in order of their length)
 * - the chains that are not starting with the head element (in order of their length) - i.e. the chains that start
 * with element pointing to the predecessor that is part of the chain starting with the head element
 * - the chains with circular reference (in order of their length) - i.e. where the head has predecessor that is
 * part of its chain
 *
 * The structure of semi-consistent index is defined by the order of the operations applied on the index. The head of
 * circular dependency chain is setup at the moment when the element in existing chain is ordered to be successor of
 * an element which is present in the tail of its chain.
 *
 * # Internal representation (positional model)
 *
 * All elements of the attribute live in a **single** order-statistic {@link TransactionalUnorderedIntArray}
 * ({@link #elements}) in their materialized (semi-consistent) order. A logical *chain* is a **contiguous, head-first
 * run** of that array, described by a {@link ChainDescriptor} keyed by the run's head primary key in {@link #chains}
 * (the run spans `[indexOf(headPk), indexOf(headPk) + length)`). Chain membership of an element is therefore
 * **positional** - it is not stored per element - so promoting a new head, merging or splitting a chain never has to
 * re-stamp the elements of the chain (no `O(K)` reclassification). The only per-element datum kept is its predecessor
 * primary key ({@link #predecessors}), which mirrors the entity's {@link ChainableType} attribute and is needed for
 * the head's external predecessor and for integrity verification.
 *
 * A single-element relocation (`upsertPredecessor` on an existing element) therefore *detaches* the element from the
 * tree and *re-inserts* it right after its new predecessor, **without rewriting any neighbour's predecessor** - a
 * neighbour whose stored predecessor no longer equals its positional predecessor simply becomes the head of a new
 * (transient) run, created in `O(1)`. The {@link #collapse()} pass then re-merges runs whose head predecessor is the
 * tail of another run (relocating the shorter run when the two are not already physically adjacent), which restores the
 * eventually-consistent single chain.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class ChainIndex implements
	IndexDataStructure,
	ConsistencySensitiveDataStructure,
	SortedRecordsSupplierFactory,
	TransactionalLayerProducer<ChainIndexChanges, ChainIndex>,
	Serializable
{
	@Serial private static final long serialVersionUID = 6633952268102524794L;

	/**
	 * Single global order-statistic array holding **all** elements of the attribute in their materialized
	 * (semi-consistent) order. Each logical chain is a contiguous, head-first run of this array.
	 */
	final TransactionalUnorderedIntArray elements;
	/**
	 * Per-chain descriptors keyed by the chain's head primary key. The descriptor stores the run length and the head's
	 * {@link ElementState}; the run occupies `[elements.indexOf(headPk), +length)`.
	 */
	final TransactionalMap<Integer, ChainDescriptor> chains;
	/**
	 * Per-element predecessor primary key (mirror of the entity's {@link ChainableType} attribute). A head element maps
	 * to {@link ChainableType#HEAD_PK} when it is a true head, or to the external/absent predecessor it points at.
	 */
	final TransactionalMap<Integer, Integer> predecessors;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	@Nonnull private final TransactionalBoolean dirty;
	/**
	 * Reference key (discriminator) of the {@link AbstractReducedEntityIndex} this index belongs to. Or null if
	 * this index is part of the global {@link GlobalEntityIndex}.
	 */
	@Getter @Nullable private final RepresentativeReferenceKey referenceKey;
	/**
	 * Contains key identifying the attribute.
	 */
	@Getter private final AttributeIndexKey attributeIndexKey;
	/**
	 * Temporary data structure that should be NULL and should exist only when {@link Catalog} is in
	 * bulk insertion or read only state where transaction are not used.
	 */
	@Nullable private ChainIndexChanges chainIndexChanges;

	public ChainIndex(@Nonnull AttributeIndexKey attributeIndexKey) {
		this(null, attributeIndexKey);
	}

	public ChainIndex(@Nullable RepresentativeReferenceKey referenceKey, @Nonnull AttributeIndexKey attributeIndexKey) {
		this.referenceKey = referenceKey;
		this.attributeIndexKey = attributeIndexKey;
		this.dirty = new TransactionalBoolean();
		this.elements = new TransactionalUnorderedIntArray();
		this.chains = new TransactionalMap<>(new java.util.HashMap<>(32));
		this.predecessors = new TransactionalMap<>(new java.util.HashMap<>(64));
	}

	public ChainIndex(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull int[][] chains,
		@Nonnull Map<Integer, ChainElementState> elementStates
	) {
		this(null, attributeIndexKey, chains, elementStates);
	}

	public ChainIndex(
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull int[][] chains,
		@Nonnull Map<Integer, ChainElementState> elementStates
	) {
		this.referenceKey = referenceKey;
		this.attributeIndexKey = attributeIndexKey;
		this.dirty = new TransactionalBoolean();

		// concatenate all chains into the single global array (run order = supplied array order)
		int total = 0;
		for (final int[] chain : chains) {
			total += chain.length;
		}
		final int[] flattened = new int[total];
		int offset = 0;
		final Map<Integer, ChainDescriptor> descriptors = new java.util.HashMap<>(chains.length * 2);
		for (final int[] chain : chains) {
			if (chain.length == 0) {
				continue;
			}
			System.arraycopy(chain, 0, flattened, offset, chain.length);
			offset += chain.length;
			final int headPk = chain[0];
			final ChainElementState headState = elementStates.get(headPk);
			// the head's state is preserved verbatim from the loaded data so the consistency report can validate it
			final ElementState state = headState == null ? ElementState.SUCCESSOR : headState.state();
			descriptors.put(headPk, new ChainDescriptor(chain.length, state));
		}
		this.elements = new TransactionalUnorderedIntArray(flattened);
		this.chains = new TransactionalMap<>(descriptors);

		// mirror the per-element predecessor primary keys
		final Map<Integer, Integer> predecessorMap = new java.util.HashMap<>(elementStates.size() * 2);
		for (final Entry<Integer, ChainElementState> entry : elementStates.entrySet()) {
			predecessorMap.put(entry.getKey(), entry.getValue().predecessorPrimaryKey());
		}
		this.predecessors = new TransactionalMap<>(predecessorMap);
	}

	private ChainIndex(
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull TransactionalUnorderedIntArray elements,
		@Nonnull Map<Integer, ChainDescriptor> chains,
		@Nonnull Map<Integer, Integer> predecessors
	) {
		this.referenceKey = referenceKey;
		this.attributeIndexKey = attributeIndexKey;
		this.dirty = new TransactionalBoolean();
		this.elements = elements;
		this.chains = new TransactionalMap<>(chains);
		this.predecessors = new TransactionalMap<>(predecessors);
	}

	/**
	 * Returns TRUE only if there is single consecutive chain of elements starting with the head element.
	 *
	 * @return TRUE if the index is consistent, FALSE otherwise
	 */
	public boolean isConsistent() {
		return this.chains.size() <= 1;
	}

	/**
	 * Intermediate result that combines the data from {@link #chains} into single
	 * array of primary keys. The array equals to a value of single {@link #chains} element in case the index is
	 * in consistent state, otherwise it contains record ordered as follows:
	 *
	 * - the longest chain of elements starting with the head element
	 * - others chains of elements starting with the head element (in order of their length)
	 * - the chains that are not starting with the head element (in order of their length) - i.e. the chains that start
	 * with element pointing to the predecessor that is part of the chain starting with the head element
	 * - the chains with circular reference (in order of their length) - i.e. where the head has predecessor that is
	 * part of its chain
	 */
	@Nonnull
	public UnorderedLookup getUnorderedLookup() {
		// sort the runs by element-state tier (HEAD, then SUCCESSOR, then CIRCULAR) and within the tier by descending
		// length - exactly the documented semi-consistent ordering
		final List<Entry<Integer, ChainDescriptor>> orderedChains = new ArrayList<>(this.chains.entrySet());
		orderedChains.sort((o1, o2) -> {
			final ChainDescriptor d1 = o1.getValue();
			final ChainDescriptor d2 = o2.getValue();
			if (d1.state() == d2.state()) {
				// the longest chains come first
				return Integer.compare(d2.length(), d1.length());
			} else {
				// this will sort heads first, then successors, then circulars
				return Integer.compare(d1.state().ordinal(), d2.state().ordinal());
			}
		});

		final int[] result = new int[this.elements.getLength()];
		int offset = 0;
		for (final Entry<Integer, ChainDescriptor> entry : orderedChains) {
			final int headPos = this.elements.indexOf(entry.getKey());
			final int length = entry.getValue().length();
			final int[] run = this.elements.getSubArray(headPos, headPos + length);
			System.arraycopy(run, 0, result, offset, run.length);
			offset += run.length;
		}

		return new UnorderedLookup(result);
	}

	/**
	 * Method adds or updates existing `predecessor` information for the `primaryKey` element in the index.
	 *
	 * @param predecessor pointer record to a predecessor element of the `primaryKey` element
	 * @param primaryKey  primary key of the element
	 */
	public void upsertPredecessor(@Nonnull ChainableType predecessor, int primaryKey) {
		Assert.isTrue(
			primaryKey != predecessor.predecessorPk() || predecessor instanceof ReferencedEntityPredecessor,
			"An entity that is its own predecessor doesn't have sense!"
		);
		final Integer existingPredecessor = this.predecessors.get(primaryKey);
		if (existingPredecessor == null) {
			// if existing state is not found - we need to insert new one
			insertElement(primaryKey, predecessor);
		} else if (existingPredecessor == predecessor.predecessorPk()) {
			// the predecessor is the same - nothing to do
			return;
		} else {
			// otherwise we need to perform update (single-element relocation)
			updateElement(primaryKey, predecessor);
		}
		this.dirty.setToTrue();
		getOrCreateChainIndexChanges().reset();
	}

	/**
	 * Method removes existing `predecessor` information for the `primaryKey` element in the index.
	 *
	 * @param primaryKey primary key of the element
	 */
	public void removePredecessor(int primaryKey) {
		final Integer existingPredecessor = this.predecessors.remove(primaryKey);
		isTrue(
			existingPredecessor != null,
			"Value `" + primaryKey + "` is not present in the chain element index!"
		);
		detachElement(primaryKey);
		collapse();
		this.dirty.setToTrue();
		getOrCreateChainIndexChanges().reset();
	}

	/**
	 * Returns true if {@link SortIndex} contains no data.
	 */
	public boolean isEmpty() {
		return this.predecessors.isEmpty();
	}

	@Nonnull
	@Override
	public SortedRecordsSupplier getAscendingOrderRecordsSupplier() {
		return getOrCreateChainIndexChanges().getAscendingOrderRecordsSupplier();
	}

	@Nonnull
	@Override
	public SortedRecordsSupplier getDescendingOrderRecordsSupplier() {
		return getOrCreateChainIndexChanges().getDescendingOrderRecordsSupplier();
	}

	/**
	 * This method verifies internal consistency of the data-structure. It checks whether the chains are correctly
	 * ordered and whether the element states are correctly set.
	 */
	@Nonnull
	@Override
	public ConsistencyReport getConsistencyReport() {
		final StringBuilder errors = new StringBuilder(512);

		int overallCount = 0;
		for (final Entry<Integer, ChainDescriptor> entry : this.chains.entrySet()) {
			final int headPk = entry.getKey();
			final ChainDescriptor descriptor = entry.getValue();
			final int length = descriptor.length();
			overallCount += length;

			final int headPos = this.elements.indexOf(headPk);
			if (headPos == Integer.MIN_VALUE) {
				errors.append("\nThe head of the chain `")
					.append(headPk)
					.append("` is not present in the element array!");
				continue;
			}
			if (length <= 0 || headPos + length > this.elements.getLength()) {
				errors.append("\nThe chain with head `")
					.append(headPk)
					.append("` has an invalid length `")
					.append(length)
					.append("`!");
				continue;
			}

			final int[] run = this.elements.getSubArray(headPos, headPos + length);
			int previousElementId = headPk;
			for (int i = 0; i < run.length; i++) {
				final int elementId = run[i];
				final Integer storedPredecessor = this.predecessors.get(elementId);
				if (storedPredecessor == null) {
					errors.append("\nThe element `")
						.append(elementId)
						.append("` is not present in the element states!");
				} else if (i > 0 && storedPredecessor != previousElementId) {
					errors.append("\nThe predecessor of the element `")
						.append(elementId)
						.append("` doesn't match the previous element!");
				}
				previousElementId = elementId;
			}

			// verify the head state matches the structural reality
			final ElementState expectedState = computeHeadState(headPk, length);
			if (descriptor.state() != expectedState) {
				errors.append("\nThe head `")
					.append(headPk)
					.append("` is flagged ")
					.append(descriptor.state())
					.append(" but the structure implies ")
					.append(expectedState)
					.append("!");
			}
		}

		// every known element must belong to exactly one chain (== be present in the element array)
		for (final Integer elementId : this.predecessors.keySet()) {
			if (!this.elements.contains(elementId)) {
				errors.append("\nThe element `")
					.append(elementId)
					.append("` has a recorded predecessor but is not present in any chain!");
			}
		}

		if (overallCount != this.predecessors.size() || overallCount != this.elements.getLength()) {
			errors.append("\nThe number of elements in chains doesn't match " +
				"the number of elements in element states!");
		}

		final ConsistencyState state;
		if (!errors.isEmpty()) {
			state = ConsistencyState.BROKEN;
		} else if (isConsistent()) {
			state = ConsistencyState.CONSISTENT;
		} else {
			state = ConsistencyState.INCONSISTENT;
		}

		final String chainsListing = this.chains.entrySet()
			.stream()
			.map(entry -> {
				final StringBuilder sb = new StringBuilder("\t- ");
				final int headPos = this.elements.indexOf(entry.getKey());
				final int length = entry.getValue().length();
				int counter = 0;
				while (counter < length && sb.length() < 80) {
					if (counter > 0) {
						sb.append(", ");
					}
					sb.append(this.elements.get(headPos + counter));
					counter++;
				}
				if (counter < length) {
					sb.append("... (")
						.append(length - counter)
						.append(" more)");
				}
				return sb.toString();
			})
			.collect(Collectors.joining("\n"));
		return new ConsistencyReport(
			state,
			"## Chains\n\n" + chainsListing + "\n\n" +
				(errors.isEmpty() ? "## No errors detected." : "## Errors detected\n\n" + errors)
		);
	}

	/**
	 * Method creates container for storing chain index from memory to the persistent storage.
	 */
	@Nullable
	public StoragePart createStoragePart(int entityIndexPrimaryKey) {
		if (this.dirty.isTrue()) {
			// all data are persisted to disk - we may get rid of temporary, modification only helper container
			this.chainIndexChanges = null;

			final int[][] chainArrays = new int[this.chains.size()][];
			final Map<Integer, ChainElementState> elementStates = new java.util.HashMap<>(this.predecessors.size() * 2);
			int chainIndex = 0;
			for (final Entry<Integer, ChainDescriptor> entry : this.chains.entrySet()) {
				final int headPk = entry.getKey();
				final ChainDescriptor descriptor = entry.getValue();
				final int headPos = this.elements.indexOf(headPk);
				final int[] run = this.elements.getSubArray(headPos, headPos + descriptor.length());
				chainArrays[chainIndex++] = run;
				for (int i = 0; i < run.length; i++) {
					final int elementId = run[i];
					elementStates.put(
						elementId,
						new ChainElementState(
							headPk,
							this.predecessors.get(elementId),
							i == 0 ? descriptor.state() : ElementState.SUCCESSOR
						)
					);
				}
			}

			return new ChainIndexStoragePart(
				entityIndexPrimaryKey,
				this.attributeIndexKey,
				elementStates,
				chainArrays
			);
		} else {
			return null;
		}
	}

	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	/*
		Implementation of TransactionalLayerProducer
	 */

	@Override
	public ChainIndexChanges createLayer() {
		return new ChainIndexChanges(this);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.dirty.removeLayer(transactionalLayer);
		this.elements.removeLayer(transactionalLayer);
		this.chains.removeLayer(transactionalLayer);
		this.predecessors.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public ChainIndex createCopyWithMergedTransactionalMemory(
		@Nullable ChainIndexChanges layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		return new ChainIndex(
			this.referenceKey,
			this.attributeIndexKey,
			transactionalLayer.getStateCopyWithCommittedChanges(this.elements),
			transactionalLayer.getStateCopyWithCommittedChanges(this.chains),
			transactionalLayer.getStateCopyWithCommittedChanges(this.predecessors)
		);
	}

	@Override
	public String toString() {
		final List<Entry<Integer, ChainDescriptor>> orderedChains = new ArrayList<>(this.chains.entrySet());
		orderedChains.sort(Comparator.comparingInt(o -> this.elements.indexOf(o.getKey())));
		final StringBuilder chainsDump = new StringBuilder(256);
		for (final Entry<Integer, ChainDescriptor> entry : orderedChains) {
			final int headPos = this.elements.indexOf(entry.getKey());
			final int[] run = this.elements.getSubArray(headPos, headPos + entry.getValue().length());
			chainsDump.append("\n      - ").append(java.util.Arrays.toString(run));
		}
		final List<Integer> orderedElements = new ArrayList<>(this.predecessors.keySet());
		orderedElements.sort(Comparator.naturalOrder());
		final StringBuilder statesDump = new StringBuilder(256);
		for (final Integer elementId : orderedElements) {
			statesDump.append("\n      - ").append(elementId).append(": ")
				.append(reconstructState(elementId));
		}
		return "ChainIndex" + (this.referenceKey == null ? "" : " (refKey: " + this.referenceKey + ")") + ":\n" +
			"   - chains:" + chainsDump + "\n" +
			"   - elementStates:" + statesDump;
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Retrieves or creates temporary data structure. When transaction exists, it is created in the transactional memory
	 * space so that other threads are not affected by the changes in the {@link ChainIndex}.
	 */
	@Nonnull
	private ChainIndexChanges getOrCreateChainIndexChanges() {
		final ChainIndexChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return ofNullable(this.chainIndexChanges).orElseGet(() -> {
				this.chainIndexChanges = new ChainIndexChanges(this);
				return this.chainIndexChanges;
			});
		} else {
			return layer;
		}
	}

	/**
	 * Inserts a brand-new element (one not yet present in the index) together with its predecessor pointer.
	 *
	 * @param primaryKey  primary key of the new element
	 * @param predecessor pointer record to a predecessor element of the `primaryKey` element
	 */
	private void insertElement(int primaryKey, @Nonnull ChainableType predecessor) {
		this.predecessors.put(primaryKey, predecessor.predecessorPk());
		attachElement(primaryKey, predecessor);
		collapse();
	}

	/**
	 * Updates the predecessor of an element that is already present in the index. The element is detached from its
	 * current position and re-attached right after its new predecessor (single-element relocation, no suffix drag).
	 *
	 * @param primaryKey  primary key of the element
	 * @param predecessor new predecessor pointer
	 */
	private void updateElement(int primaryKey, @Nonnull ChainableType predecessor) {
		detachElement(primaryKey);
		this.predecessors.put(primaryKey, predecessor.predecessorPk());
		attachElement(primaryKey, predecessor);
		collapse();
	}

	/**
	 * Places `primaryKey` (already absent from {@link #elements}) into the array according to its (already stored)
	 * predecessor: a head element starts a fresh run at the tail of the array, an element whose predecessor is present
	 * and is a run tail is appended right after it (extending that run), and any other element starts a fresh
	 * (possibly transient) successor run at the tail of the array.
	 *
	 * @param primaryKey  primary key of the element to place
	 * @param predecessor predecessor pointer of the element
	 */
	private void attachElement(int primaryKey, @Nonnull ChainableType predecessor) {
		if (predecessor.isHead()) {
			// brand-new head: start a fresh run at the very end of the array
			this.elements.addOnIndex(this.elements.getLength(), primaryKey);
			this.chains.put(primaryKey, new ChainDescriptor(1, ElementState.HEAD));
			return;
		}
		final int predecessorPk = predecessor.predecessorPk();
		final int predecessorPos = this.elements.indexOf(predecessorPk);
		if (predecessorPos == Integer.MIN_VALUE) {
			// the predecessor is not present yet - start a fresh orphan successor run at the end
			this.elements.addOnIndex(this.elements.getLength(), primaryKey);
			this.chains.put(primaryKey, new ChainDescriptor(1, ElementState.SUCCESSOR));
			return;
		}
		final RunRef predecessorRun = findRun(predecessorPos);
		final boolean predecessorIsTail = predecessorPos == predecessorRun.headPos() + predecessorRun.length() - 1;
		if (predecessorIsTail) {
			// append right after the predecessor, extending its run
			this.elements.addOnIndex(predecessorPos + 1, primaryKey);
			final ChainDescriptor descriptor = this.chains.get(predecessorRun.headPk());
			final int newLength = descriptor.length() + 1;
			this.chains.put(
				predecessorRun.headPk(),
				new ChainDescriptor(newLength, computeHeadState(predecessorRun.headPk(), newLength))
			);
		} else {
			// predecessor already has a successor - this element forms a separate (split) successor run
			this.elements.addOnIndex(this.elements.getLength(), primaryKey);
			this.chains.put(primaryKey, new ChainDescriptor(1, ElementState.SUCCESSOR));
		}
	}

	/**
	 * Removes `primaryKey` from {@link #elements} and repairs the descriptor of the run it belonged to: a singleton run
	 * is dropped, a removed head promotes its successor (re-keying the descriptor, no element re-stamping), a removed
	 * tail just shrinks the run, and a removed middle element splits the run into a head-side run and a (transient)
	 * orphan successor run. The element's {@link #predecessors} entry is left untouched (the caller decides whether to
	 * overwrite or drop it).
	 *
	 * @param primaryKey primary key of the element to detach from the array
	 */
	private void detachElement(int primaryKey) {
		final int position = this.elements.indexOf(primaryKey);
		isPremiseValid(
			position != Integer.MIN_VALUE,
			"Index damaged! The primary key `" + primaryKey + "` must be present in the element array!"
		);
		final RunRef run = findRun(position);
		final int headPk = run.headPk();
		final int headPos = run.headPos();
		final int length = run.length();
		final ChainDescriptor descriptor = this.chains.get(headPk);

		this.elements.remove(primaryKey);

		if (length == 1) {
			// the element was the only member of its run - the run disappears entirely
			this.chains.remove(headPk);
		} else if (position == headPos) {
			// the element was the head - its successor (now at headPos) becomes the new head
			final int newHead = this.elements.get(headPos);
			this.chains.remove(headPk);
			this.chains.put(newHead, new ChainDescriptor(length - 1, computeHeadState(newHead, length - 1)));
		} else if (position == headPos + length - 1) {
			// the element was the tail - just shrink the run (head state may stop being circular)
			this.chains.put(headPk, new ChainDescriptor(length - 1, computeHeadState(headPk, length - 1)));
		} else {
			// the element was in the middle - split the run into a head-side run and an orphan successor run
			final int prefixLength = position - headPos;
			final int suffixLength = length - prefixLength - 1;
			final int suffixHead = this.elements.get(position);
			this.chains.put(headPk, new ChainDescriptor(prefixLength, computeHeadState(headPk, prefixLength)));
			this.chains.put(suffixHead, new ChainDescriptor(suffixLength, computeHeadState(suffixHead, suffixLength)));
		}
	}

	/**
	 * Repeatedly merges any successor/orphan run whose head predecessor is the tail of another run into that run, until
	 * no more merges are possible. When the two runs to merge are not already physically adjacent, the shorter run is
	 * relocated so they become adjacent (`O(min)` and only transient - the steady state is a single chain).
	 */
	private void collapse() {
		boolean merged = true;
		while (merged) {
			merged = false;
			for (final Integer headPk : new ArrayList<>(this.chains.keySet())) {
				final ChainDescriptor descriptor = this.chains.get(headPk);
				if (descriptor == null || descriptor.state() != ElementState.SUCCESSOR) {
					// HEAD and CIRCULAR runs never follow another run
					continue;
				}
				final int headPredecessor = this.predecessors.get(headPk);
				if (headPredecessor == HEAD_PK) {
					continue;
				}
				final int predecessorPos = this.elements.indexOf(headPredecessor);
				if (predecessorPos == Integer.MIN_VALUE) {
					// predecessor not present yet - the run stays an orphan
					continue;
				}
				final RunRef predecessorRun = findRun(predecessorPos);
				if (predecessorRun.headPk() == headPk) {
					// defensive: predecessor inside the same run would mean circular - handled by state, skip
					continue;
				}
				final boolean predecessorIsTail =
					predecessorPos == predecessorRun.headPos() + predecessorRun.length() - 1;
				if (predecessorIsTail) {
					mergeRunAfter(predecessorRun.headPk(), headPk);
					merged = true;
					break;
				}
			}
		}
	}

	/**
	 * Merges the run headed by `followerHeadPk` immediately after the run headed by `targetHeadPk` (the follower's head
	 * predecessor is the target's tail). If the two runs are not already physically adjacent the shorter of them is
	 * relocated to restore contiguity. After the merge a single run headed by `targetHeadPk` remains.
	 *
	 * @param targetHeadPk   head of the run the follower is appended to
	 * @param followerHeadPk head of the run being merged in
	 */
	private void mergeRunAfter(int targetHeadPk, int followerHeadPk) {
		final int targetPos = this.elements.indexOf(targetHeadPk);
		final int targetLength = this.chains.get(targetHeadPk).length();
		final int followerPos = this.elements.indexOf(followerHeadPk);
		final int followerLength = this.chains.get(followerHeadPk).length();

		final boolean adjacent = targetPos + targetLength == followerPos;
		if (!adjacent) {
			if (followerLength <= targetLength) {
				// relocate the follower run to right after the target's tail
				final int targetTail = this.elements.get(targetPos + targetLength - 1);
				relocateBlockAfter(followerPos, followerLength, targetTail);
			} else {
				// relocate the target run to right before the follower's head
				relocateBlockBefore(targetPos, targetLength, followerHeadPk);
			}
		}

		this.chains.remove(followerHeadPk);
		final int mergedLength = targetLength + followerLength;
		this.chains.put(targetHeadPk, new ChainDescriptor(mergedLength, computeHeadState(targetHeadPk, mergedLength)));
	}

	/**
	 * Relocates the contiguous block `[blockStartPos, blockStartPos + blockLength)` so that it immediately follows the
	 * element `afterPk` (which must lie outside the block). The block elements are read positionally (`O(block·log N)`)
	 * rather than via {@code getSubArray}, which would flatten the whole array.
	 */
	private void relocateBlockAfter(int blockStartPos, int blockLength, int afterPk) {
		final int[] block = readBlock(blockStartPos, blockLength);
		for (final int recordId : block) {
			this.elements.remove(recordId);
		}
		final int insertAt = this.elements.indexOf(afterPk) + 1;
		for (int i = 0; i < block.length; i++) {
			this.elements.addOnIndex(insertAt + i, block[i]);
		}
	}

	/**
	 * Relocates the contiguous block `[blockStartPos, blockStartPos + blockLength)` so that it immediately precedes the
	 * element `beforePk` (which must lie outside the block). The block elements are read positionally
	 * (`O(block·log N)`) rather than via {@code getSubArray}, which would flatten the whole array.
	 */
	private void relocateBlockBefore(int blockStartPos, int blockLength, int beforePk) {
		final int[] block = readBlock(blockStartPos, blockLength);
		for (final int recordId : block) {
			this.elements.remove(recordId);
		}
		final int insertAt = this.elements.indexOf(beforePk);
		for (int i = 0; i < block.length; i++) {
			this.elements.addOnIndex(insertAt + i, block[i]);
		}
	}

	/**
	 * Reads the contiguous block `[startPos, startPos + length)` from {@link #elements} element-by-element. Used on the
	 * hot relocation path to avoid {@link TransactionalUnorderedIntArray#getSubArray} flattening the entire array.
	 */
	@Nonnull
	private int[] readBlock(int startPos, int length) {
		final int[] block = new int[length];
		for (int i = 0; i < length; i++) {
			block[i] = this.elements.get(startPos + i);
		}
		return block;
	}

	/**
	 * Finds the run (chain) that contains the element at the given array position.
	 *
	 * @param position position in {@link #elements}
	 * @return reference to the run containing the position
	 */
	@Nonnull
	private RunRef findRun(int position) {
		for (final Entry<Integer, ChainDescriptor> entry : this.chains.entrySet()) {
			final int headPos = this.elements.indexOf(entry.getKey());
			final int length = entry.getValue().length();
			if (position >= headPos && position < headPos + length) {
				return new RunRef(entry.getKey(), headPos, length);
			}
		}
		throw new GenericEvitaInternalError(
			"Index damaged! Position `" + position + "` is not covered by any chain!"
		);
	}

	/**
	 * Computes the {@link ElementState} of a run head from the structural reality: a head with no predecessor is
	 * {@link ElementState#HEAD}, a head whose predecessor currently sits inside its own run is
	 * {@link ElementState#CIRCULAR}, otherwise {@link ElementState#SUCCESSOR}.
	 *
	 * @param headPk head primary key of the run
	 * @param length length of the run
	 * @return the computed head state
	 */
	@Nonnull
	private ElementState computeHeadState(int headPk, int length) {
		final int headPredecessor = this.predecessors.get(headPk);
		if (headPredecessor == HEAD_PK) {
			return ElementState.HEAD;
		}
		final int predecessorPos = this.elements.indexOf(headPredecessor);
		if (predecessorPos == Integer.MIN_VALUE) {
			return ElementState.SUCCESSOR;
		}
		final int headPos = this.elements.indexOf(headPk);
		return predecessorPos >= headPos && predecessorPos < headPos + length
			? ElementState.CIRCULAR
			: ElementState.SUCCESSOR;
	}

	/**
	 * Reconstructs the public {@link ChainElementState} of an element from the positional model. Package-private
	 * test/diagnostic accessor mirroring the former per-element {@code elementStates} map (chain membership and head
	 * state are derived positionally).
	 *
	 * @param primaryKey primary key of the element
	 * @return reconstructed element state, or {@code null} when the element is not present
	 */
	@Nullable
	ChainElementState getElementState(int primaryKey) {
		return this.predecessors.containsKey(primaryKey) ? reconstructState(primaryKey) : null;
	}

	/**
	 * Reconstructs the public {@link ChainElementState} of an element from the positional model (its chain head is
	 * looked up positionally). Used for {@code toString}.
	 *
	 * @param elementId primary key of the element
	 * @return reconstructed element state
	 */
	@Nonnull
	private ChainElementState reconstructState(int elementId) {
		final int position = this.elements.indexOf(elementId);
		final RunRef run = findRun(position);
		final ElementState state = elementId == run.headPk()
			? this.chains.get(run.headPk()).state()
			: ElementState.SUCCESSOR;
		return new ChainElementState(run.headPk(), this.predecessors.get(elementId), state);
	}

	/**
	 * Reference to a run (chain) in {@link #elements}: its head primary key, the position of the head and the run
	 * length.
	 */
	private record RunRef(int headPk, int headPos, int length) {
	}

	/**
	 * Descriptor of a single chain (run) in {@link #elements}: its length and the {@link ElementState} of its head.
	 *
	 * @param length number of elements in the run
	 * @param state  state of the run's head element
	 */
	public record ChainDescriptor(int length, @Nonnull ElementState state) {
	}

	/**
	 * Enum represents the state of the element in the index.
	 */
	public enum ElementState {
		/**
		 * Element is the head of one of the chains and have no predecessor.
		 */
		HEAD,
		/**
		 * Element have defined predecessor element. Element might be the head of one of the chains in case
		 * it is in inconsistent state (i.e. there are multiple elements referring to same predecessor).
		 */
		SUCCESSOR,
		/**
		 * Element is head of the chain that and it is in circular dependency with some of the elements in its tail.
		 */
		CIRCULAR
	}

	/**
	 * Record represents a state for each element in this index. It is the persisted / boundary representation; the live
	 * index keeps chain membership positionally and reconstructs this record on demand.
	 *
	 * @param inChainOfHeadWithPrimaryKey primary key of the head of the chain this element is part of
	 * @param predecessorPrimaryKey       primary key of the predecessor of this element
	 * @param state                       state of the element
	 */
	public record ChainElementState(
		int inChainOfHeadWithPrimaryKey,
		int predecessorPrimaryKey,
		@Nonnull ElementState state
	) {

		/**
		 * Constructor allowing to override all settings of the element.
		 */
		public ChainElementState(
			int inChainOfHeadWithPrimaryKey,
			@Nonnull ChainableType predecessor,
			@Nonnull ElementState elementState
		) {
			this(inChainOfHeadWithPrimaryKey, predecessor.predecessorPk(), elementState);
		}

		@Nonnull
		@Override
		public String toString() {
			switch (this.state) {
				case HEAD -> {
					return "HEAD 🔗 " + this.inChainOfHeadWithPrimaryKey;
				}
				case SUCCESSOR -> {
					return "SUCCESSOR of " + this.predecessorPrimaryKey + " 🔗 " + this.inChainOfHeadWithPrimaryKey;
				}
				case CIRCULAR -> {
					return "CIRCULAR of " + this.predecessorPrimaryKey + " 🔗 " + this.inChainOfHeadWithPrimaryKey;
				}
				default -> throw new IllegalStateException("Unexpected value: " + this.state);
			}
		}
	}

}
