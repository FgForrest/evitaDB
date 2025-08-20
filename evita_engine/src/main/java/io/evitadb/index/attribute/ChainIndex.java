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

package io.evitadb.index.attribute;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.core.Catalog;
import io.evitadb.core.Transaction;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.ChainableType;
import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.array.UnorderedLookup;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.ChainIndexStoragePart;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.PrimitiveIterator.OfInt;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
	 * Index contains tuples of entity primary key and its predecessor primary key. The conflicting primary key is
	 * a value and the predecessor primary key is a key.
	 *
	 * Conflicting keys are keys that:
	 *
	 * - refer to the same predecessor multiple times
	 * - refer to the predecessor that is transiently referring to them (circular reference)
	 *
	 * The key is the conflicting primary key and the value is the predecessor primary key.
	 */
	final TransactionalMap<Integer, ChainElementState> elementStates;
	/**
	 * Index contains information about non-interrupted chains of predecessors for an entity which is not a head entity
	 * but is part of different chain (inconsistent state).
	 */
	final TransactionalMap<Integer, TransactionalUnorderedIntArray> chains;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	@Nonnull private final TransactionalBoolean dirty;
	/**
	 * Reference key (discriminator) of the {@link ReducedEntityIndex} this index belongs to. Or null if this index
	 * is part of the global {@link GlobalEntityIndex}.
	 */
	@Getter @Nullable private final ReferenceKey referenceKey;
	/**
	 * Contains key identifying the attribute.
	 */
	@Getter private final AttributeKey attributeKey;
	/**
	 * Temporary data structure that should be NULL and should exist only when {@link Catalog} is in
	 * bulk insertion or read only state where transaction are not used.
	 */
	@Nullable private ChainIndexChanges chainIndexChanges;

	public ChainIndex(@Nonnull AttributeKey attributeKey) {
		this(null, attributeKey);
	}

	public ChainIndex(@Nullable ReferenceKey referenceKey, @Nonnull AttributeKey attributeKey) {
		this.referenceKey = referenceKey;
		this.attributeKey = attributeKey;
		this.dirty = new TransactionalBoolean();
		this.chains = new TransactionalMap<>(new HashMap<>(), TransactionalUnorderedIntArray.class, TransactionalUnorderedIntArray::new);
		this.elementStates = new TransactionalMap<>(new HashMap<>());
	}

	public ChainIndex(
		@Nonnull AttributeKey attributeKey,
		@Nonnull int[][] chains,
		@Nonnull Map<Integer, ChainElementState> elementStates
	) {
		this(null, attributeKey, chains, elementStates);
	}

	public ChainIndex(
		@Nullable ReferenceKey referenceKey,
		@Nonnull AttributeKey attributeKey,
		@Nonnull int[][] chains,
		@Nonnull Map<Integer, ChainElementState> elementStates
	) {
		this.referenceKey = referenceKey;
		this.attributeKey = attributeKey;
		this.dirty = new TransactionalBoolean();
		this.chains = new TransactionalMap<>(
			Arrays.stream(chains)
				.map(TransactionalUnorderedIntArray::new)
				.collect(
					Collectors.toMap(
						it -> it.get(0),
						Function.identity()
					)
				),
			TransactionalUnorderedIntArray.class,
			TransactionalUnorderedIntArray::new
		);
		this.elementStates = new TransactionalMap<>(elementStates);
	}

	private ChainIndex(
		@Nullable ReferenceKey referenceKey,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Map<Integer, TransactionalUnorderedIntArray> chains,
		@Nonnull Map<Integer, ChainElementState> elementStates
	) {
		this.referenceKey = referenceKey;
		this.attributeKey = attributeKey;
		this.dirty = new TransactionalBoolean();
		this.chains = new TransactionalMap<>(chains, TransactionalUnorderedIntArray.class, TransactionalUnorderedIntArray::new);
		this.elementStates = new TransactionalMap<>(elementStates);
	}

	/**
	 * Returns TRUE only if there is single consecutive chain of elements starting with the head element.
	 *
	 * @return TRUE if the index is consistent, FALSE otherwise
	 */
	public boolean isConsistent() {
		final int numberOfChains = this.chains.size();
		return numberOfChains <= 1;
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
		final int[][] orderedChains = this.chains
			.entrySet()
			.stream()
			.sorted((o1, o2) -> {
				final ChainElementState state1 = this.elementStates.get(o1.getKey());
				final ChainElementState state2 = this.elementStates.get(o2.getKey());
				if (state1.state() == state2.state()) {
					// the longest chains come first
					return Integer.compare(o2.getValue().getLength(), o1.getValue().getLength());
				} else {
					// this will sort heads first, then successors, then circulars
					return Integer.compare(state1.state().ordinal(), state2.state().ordinal());
				}
			})
			.map(Entry::getValue)
			.map(TransactionalUnorderedIntArray::getArray)
			.toArray(int[][]::new);

		final int[] result = new int[Stream.of(orderedChains).mapToInt(arr -> arr.length).sum()];
		int offset = 0;
		for (int[] chain : orderedChains) {
			System.arraycopy(chain, 0, result, offset, chain.length);
			offset += chain.length;
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
		final ChainElementState existingState = this.elementStates.get(primaryKey);
		if (existingState == null) {
			// if existing state is not found - we need to insert new one
			insertPredecessor(primaryKey, predecessor);
		} else if (existingState.predecessorPrimaryKey() == predecessor.predecessorPk()) {
			// the predecessor is the same - nothing to do
			return;
		} else {
			// otherwise we need to perform update
			updatePredecessor(primaryKey, predecessor, existingState);
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
		final ChainElementState existingState = this.elementStates.remove(primaryKey);
		isTrue(
			existingState != null,
			"Value `" + primaryKey + "` is not present in the chain element index!"
		);
		// if existing state is not found - we need to insert new one
		removePredecessorFromChain(primaryKey, existingState);
		this.dirty.setToTrue();
		getOrCreateChainIndexChanges().reset();
	}

	/**
	 * Returns true if {@link SortIndex} contains no data.
	 */
	public boolean isEmpty() {
		return this.elementStates.isEmpty();
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
		for (Entry<Integer, TransactionalUnorderedIntArray> entry : this.chains.entrySet()) {
			overallCount += entry.getValue().getLength();
			final int[] unorderedList = entry.getValue().getArray();
			if (unorderedList.length <= 0) {
				errors.append("\nThe chain with head `")
					.append(entry.getKey())
					.append("` is empty!");
			}
			int headElementId = unorderedList[0];
			if (headElementId != entry.getKey()) {
				errors.append("\nThe head of the chain `")
					.append(headElementId).append("` doesn't match the chain head `")
					.append(entry.getKey())
					.append("`!");
			}

			int previousElementId = headElementId;
			for (int i = 0; i < unorderedList.length; i++) {
				int elementId = unorderedList[i];
				final ChainElementState state = this.elementStates.get(elementId);
				if (state == null) {
					errors.append("\nThe element `")
						.append(elementId)
						.append("` is not present in the element states!");
				} else if (i > 0) {
					if (state.state() == ElementState.HEAD) {
						errors.append("\nThe element `")
							.append(elementId)
							.append("` must not be a head of the chain!");
					}
					if (state.inChainOfHeadWithPrimaryKey() != headElementId) {
						errors.append("\nThe element `")
							.append(elementId)
							.append("` is not in the chain with head `")
							.append(headElementId)
							.append("`!");
					}
					if (state.predecessorPrimaryKey() != previousElementId) {
						errors.append("\nThe predecessor of the element `")
							.append(elementId)
							.append("` doesn't match the previous element!");
					}
				}
				previousElementId = elementId;
			}

			final Integer headId = entry.getKey();
			final ChainElementState headState = this.elementStates.get(headId);
			if (headState.state() == ElementState.CIRCULAR) {
				// the chain must contain element the head refers to
				if (Arrays.stream(entry.getValue().getArray()).noneMatch(it -> it == headState.predecessorPrimaryKey())) {
					errors.append("\nThe chain with CIRCULAR head `")
						.append(headId)
						.append("` doesn't contain element `")
						.append(headState.predecessorPrimaryKey())
						.append("` the head refers to!");
				}
			} else {
				// the chain must not contain element the head refers to
				if (Arrays.stream(entry.getValue().getArray()).anyMatch(it -> it == headState.predecessorPrimaryKey())) {
					errors.append("\nThe chain with head `")
						.append(headId)
						.append("` contain element `")
						.append(headState.predecessorPrimaryKey())
						.append("` the head refers to and is not marked as CIRCULAR!");
				}
			}
		}

		for (Entry<Integer, ChainElementState> entry : this.elementStates.entrySet()) {
			final int chainHeadPk = entry.getValue().inChainOfHeadWithPrimaryKey();
			if (entry.getValue().state() == ElementState.SUCCESSOR) {
				final TransactionalUnorderedIntArray chain = this.chains.get(chainHeadPk);
				if (chain == null) {
					errors.append("\nThe referenced chain with head `")
						.append(chainHeadPk)
						.append("` referenced by ")
						.append(entry.getValue().state())
						.append("` element `")
						.append(entry.getKey())
						.append("` doesn't exist!");
				} else if (chain.indexOf(entry.getKey()) < 0) {
					errors.append("\nThe `")
						.append(entry.getValue().state())
						.append("` element `")
						.append(entry.getKey())
						.append("` is not in the chain with head `")
						.append(chainHeadPk)
						.append("`!");
				}
			} else if (chainHeadPk != entry.getKey()) {
				errors.append("\nThe `")
					.append(entry.getValue().state())
					.append("` element `")
					.append(entry.getKey())
					.append("` is not in the chain with head `")
					.append(chainHeadPk)
					.append("`!");
			}
		}

		if (overallCount != this.elementStates.size()) {
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

		final String chainsListing = this.chains.values()
			.stream()
			.map(it -> {
				final StringBuilder sb = new StringBuilder("\t- ");
				final OfInt pkIt = it.iterator();
				int counter = 0;
				while (pkIt.hasNext() && sb.length() < 80) {
					if (counter > 0) {
						sb.append(", ");
					}
					sb.append(pkIt.nextInt());
					counter++;
				}
				if (counter < it.getLength()) {
					sb.append("... (")
						.append(it.getLength() - counter)
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
			return new ChainIndexStoragePart(
				entityIndexPrimaryKey,
				this.attributeKey,
				this.elementStates,
				this.chains.values()
					.stream()
					.map(TransactionalUnorderedIntArray::getArray)
					.toArray(int[][]::new)
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
		this.elementStates.removeLayer(transactionalLayer);
		this.chains.removeLayer(transactionalLayer);
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
			this.attributeKey,
			transactionalLayer.getStateCopyWithCommittedChanges(this.chains),
			transactionalLayer.getStateCopyWithCommittedChanges(this.elementStates)
		);
	}

	@Override
	public String toString() {
		return "ChainIndex" + (this.referenceKey == null ? "" : " (refKey: " + this.referenceKey + ")") + ":\n" +
			"   - chains:\n" + this.chains.values().stream().map(it -> "      - " + it.toString()).collect(Collectors.joining("\n")) + "\n" +
			"   - elementStates:\n" + this.elementStates.entrySet().stream().map(it -> "      - " + it.getKey() + ": " + it.getValue()).collect(Collectors.joining("\n"));
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Retrieves or creates temporary data structure. When transaction exists it's created in the transactional memory
	 * space so that other threads are not affected by the changes in the {@link SortIndex}.
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
	 * Method removes existing `predecessor` information for the `primaryKey` element in the index.
	 *
	 * @param primaryKey    primary key of the element
	 * @param existingState existing state of the primary key element
	 */
	private void removePredecessorFromChain(int primaryKey, @Nonnull ChainElementState existingState) {
		// and then remove it from the chain
		switch (existingState.state()) {
			case HEAD -> removeHeadElement(primaryKey)
				.ifPresent(this::collapseChainsIfPossible);
			case SUCCESSOR, CIRCULAR -> removeSuccessorElement(primaryKey, existingState.inChainOfHeadWithPrimaryKey())
				.ifPresent(this::collapseChainsIfPossible);
			default -> throw new IllegalStateException("Unexpected value: " + existingState.state());
		}
	}

	/**
	 * Method removes head element of the existing chain. The chain is removed completely if the head is the only
	 * element in the chain.
	 *
	 * @param primaryKey primary key of the element
	 */
	@Nonnull
	private OptionalInt removeHeadElement(int primaryKey) {
		// if the primary key is head of its chain
		final TransactionalUnorderedIntArray removedChain = this.chains.remove(primaryKey);
		final int[] head = removedChain.removeRange(0, 1);
		isPremiseValid(
			head.length == 1 && head[0] == primaryKey,
			"The head of the chain is expected to be single element with primary key `" + primaryKey + "`!"
		);
		// if the chain is not empty
		final OptionalInt newHead;
		if (removedChain.getLength() > 0) {
			newHead = OptionalInt.of(removedChain.get(0));
			final int[] removedChainArray = removedChain.getArray();
			this.chains.put(newHead.getAsInt(), new TransactionalUnorderedIntArray(removedChainArray));
			// we need to reclassify the chain
			reclassifyChain(newHead.getAsInt(), removedChainArray);
		} else {
			newHead = OptionalInt.empty();
		}
		// if there was transactional memory associated with the chain, discard it
		removedChain.removeLayer();
		// return the primary key of a new head
		return newHead;
	}

	/**
	 * Method removes successor element of the existing chain. The chain is removed completely if the successor is the
	 * only element in the chain. If the element is in the middle of the chain, the chain is split into two chains.
	 *
	 * @param primaryKey  primary key of the element
	 * @param chainHeadPk primary key of the chain head to which the element belongs
	 */
	@Nonnull
	private OptionalInt removeSuccessorElement(int primaryKey, int chainHeadPk) {
		// if the primary key is successor of its chain
		final TransactionalUnorderedIntArray chain = ofNullable(this.chains.get(chainHeadPk))
			.orElseThrow(() -> new EvitaInvalidUsageException("Chain with head `" + chainHeadPk + "` is not present in the index!"));
		final ChainElementState existingStateHeadState = this.elementStates.get(chainHeadPk);

		final int index = chain.indexOf(primaryKey);
		// sanity check - the primary key must be present in the chain according to the state information
		Assert.isPremiseValid(
			index >= 0,
			"Index damaged! The primary key `" + primaryKey + "` must be present in the chain according to the state information!"
		);
		// if the primary key is not located at the head of the chain
		if (index > 0) {
			// and there are more elements in the chain tail after it
			if (index < chain.getLength() - 1) {
				// we need to split the chain to two chains
				final int[] subChain = chain.removeRange(index, chain.getLength());
				final int[] subChainWithoutRemovedElement = Arrays.copyOfRange(subChain, 1, subChain.length);
				this.chains.put(subChainWithoutRemovedElement[0], new TransactionalUnorderedIntArray(subChainWithoutRemovedElement));
				reclassifyChain(subChainWithoutRemovedElement[0], subChainWithoutRemovedElement);

				// verify whether the head of chain was not in circular conflict and if so
				verifyIfCircularDependencyExistsAndIsBroken(chainHeadPk, existingStateHeadState);
			} else {
				// just remove it from the chain
				chain.removeRange(index, chain.getLength());
				// if the primary key was the perpetrator of the circular dependency, remove the status
				if (existingStateHeadState.state() == ElementState.CIRCULAR && existingStateHeadState.predecessorPrimaryKey() == primaryKey) {
					this.elementStates.put(
						chainHeadPk,
						new ChainElementState(existingStateHeadState, ElementState.SUCCESSOR)
					);
				}
			}
			return OptionalInt.of(chainHeadPk);
		} else {
			// remove the head element of the chain
			return removeHeadElement(primaryKey);
		}
	}

	/**
	 * Method inserts new element into the index along with its predecessor information.
	 *
	 * @param primaryKey  primary key of the element
	 * @param predecessor pointer record to a predecessor element of the `primaryKey` element
	 */
	private void insertPredecessor(int primaryKey, @Nonnull ChainableType predecessor) {
		// the primary key was observed for the first time
		if (predecessor.isHead()) {
			// setup the new head of the chain
			this.chains.put(primaryKey, new TransactionalUnorderedIntArray(new int[]{primaryKey}));
			this.elementStates.put(primaryKey, new ChainElementState(primaryKey, predecessor, ElementState.HEAD));
		} else {
			final ChainElementState predecessorState = this.elementStates.get(predecessor.predecessorPk());
			if (predecessorState == null) {
				// the predecessor is not part of the index yet - we need to wait for it
				introduceNewSuccessorChain(primaryKey, predecessor);
			} else {
				// the predecessor was found in the index - we can add the new element to its chain
				final int chainHeadId = predecessorState.inChainOfHeadWithPrimaryKey();
				final TransactionalUnorderedIntArray predecessorChain = this.chains.get(chainHeadId);
				// if the predecessor is the last record of the predecessor chain
				if (predecessorChain.getLastRecordId() == predecessor.predecessorPk()) {
					// we may safely append the primary key to it
					predecessorChain.add(predecessor.predecessorPk(), primaryKey);
					this.elementStates.put(primaryKey, new ChainElementState(chainHeadId, predecessor, ElementState.SUCCESSOR));
					// if the head of the chain refers to the appended primary key - we have circular dependency
					final ChainElementState chainHeadState = this.elementStates.get(chainHeadId);
					if (chainHeadState.predecessorPrimaryKey() == primaryKey) {
						this.elementStates.put(
							chainHeadId,
							new ChainElementState(chainHeadState, ElementState.CIRCULAR)
						);
					}
				} else {
					// we have to create new "split" chain for the primary key
					introduceNewSuccessorChain(primaryKey, predecessor);
				}
			}
		}
		// collapse the chains that refer to the last element of created chain
		collapseChainsIfPossible(primaryKey);
	}

	/**
	 * Method creates new "split" chain - i.e. when a single predecessor has two successor elements.
	 *
	 * @param primaryKey  primary key of the element
	 * @param predecessor pointer record to a predecessor element of the `primaryKey` element
	 */
	private void introduceNewSuccessorChain(int primaryKey, @Nonnull ChainableType predecessor) {
		this.chains.put(primaryKey, new TransactionalUnorderedIntArray(new int[]{primaryKey}));
		this.elementStates.put(primaryKey, new ChainElementState(primaryKey, predecessor, ElementState.SUCCESSOR));
	}

	/**
	 * Method updates existing element in the index along with its predecessor information.
	 *
	 * @param primaryKey    primary key of the element
	 * @param predecessor   pointer record to a predecessor element of the `primaryKey` element
	 * @param existingState existing state of the primary key element
	 */
	private void updatePredecessor(int primaryKey, @Nonnull ChainableType predecessor, @Nonnull ChainElementState existingState) {
		// the primary key was already present in the index - we need to relocate it
		final int primaryKeyOfExistingHeadState = existingState.inChainOfHeadWithPrimaryKey();
		final TransactionalUnorderedIntArray existingChain = this.chains.get(primaryKeyOfExistingHeadState);
		final int index = existingChain.indexOf(primaryKey);
		// sanity check - the primary key must be present in the chain according to the state information
		Assert.isPremiseValid(
			index >= 0,
			"Index damaged! The primary key `" + primaryKey + "` must be present in the chain according to the state information!"
		);
		// we need to remember original state of the chain head before changes in order to resolve possible circular dependency
		final ChainElementState existingStateHeadState = this.elementStates.get(primaryKeyOfExistingHeadState);
		// if newly created element is a head of its chain
		if (predecessor.isHead()) {
			updateElementToBecomeHeadOfTheChain(primaryKey, index, predecessor, existingChain, primaryKeyOfExistingHeadState, existingStateHeadState);
		} else {
			updateElementWithinExistingChain(primaryKey, index, predecessor, existingChain, primaryKeyOfExistingHeadState, existingStateHeadState, existingState);
		}
	}

	/**
	 * Method updates existing element in the index and makes it a head of its chain.
	 *
	 * @param primaryKey             primary key of the element
	 * @param index                  index of the element in the chain
	 * @param predecessor            pointer record to a predecessor element of the `primaryKey` element
	 * @param existingChain          existing chain where the element is present
	 * @param primaryKeyOfExistingHeadState primary key the `existingHeadState` is registered in `this.elementStates`
	 * @param existingHeadState state of the head element of the existing chain where element is present
	 */
	private void updateElementToBecomeHeadOfTheChain(
		int primaryKey,
		int index, @Nonnull ChainableType predecessor,
		@Nonnull TransactionalUnorderedIntArray existingChain,
		int primaryKeyOfExistingHeadState,
		@Nonnull ChainElementState existingHeadState
	) {
		// if the primary key is not located at the head of the chain
		if (index > 0) {
			// we need to promote its sub-chain as the new head of the chain
			// setup the new head of the chain
			final int[] subChain = existingChain.removeRange(index, existingChain.getLength());
			this.chains.put(primaryKey, new TransactionalUnorderedIntArray(subChain));
			reclassifyChain(primaryKey, subChain);
			// we need to re-check whether the original chain has still circular dependency when this chain was split
			verifyIfCircularDependencyExistsAndIsBroken(primaryKeyOfExistingHeadState, existingHeadState);
		} else {
			// we just need to change the state, since the chain is already present
		}
		this.elementStates.put(primaryKey, new ChainElementState(primaryKey, predecessor, ElementState.HEAD));
		// collapse the chains that refer to the last element of any other chain
		collapseChainsIfPossible(primaryKey);
	}

	/**
	 * Method updates existing element in the index and makes it a successor of its predecessor.
	 * If the predecessor is not present in the index, the method creates new chain for the element.
	 * If the predecessor is present in the index, the method relocates the element to the chain of its predecessor or
	 * creates a new split chain if the predecessor has already its successor present.
	 *
	 * @param primaryKey             primary key of the element
	 * @param index                  index of the element in the chain
	 * @param predecessor            pointer record to a predecessor element of the `primaryKey` element
	 * @param existingChain          existing chain where the element is present
	 * @param primaryKeyOfExistingHeadState primary key the `existingHeadState` is registered in `this.elementStates`
	 * @param existingStateHeadState state of the head element of the existing chain where element is present
	 * @param existingState          existing state of the primary key element
	 */
	private void updateElementWithinExistingChain(
		int primaryKey,
		int index,
		@Nonnull ChainableType predecessor,
		@Nonnull TransactionalUnorderedIntArray existingChain,
		int primaryKeyOfExistingHeadState,
		@Nonnull ChainElementState existingStateHeadState,
		@Nonnull ChainElementState existingState
	) {
		// we need to relocate the element to the chain of its predecessor
		final boolean circularConflict = existingChain.indexOf(predecessor.predecessorPk()) >= index;
		// if there is circular conflict - set up a separate chain and update state
		if (circularConflict) {
			updateElementWithCircularConflict(
				primaryKey, index, predecessor, existingChain, primaryKeyOfExistingHeadState, existingStateHeadState, existingState
			);
		} else {
			final int[] movedChain;
			final int movedChainHeadPk;

			final ChainElementState predecessorState = this.elementStates.get(predecessor.predecessorPk());
			if (predecessorState == null) {
				// the predecessor doesn't exist in current index
				if (index > 0) {
					// we need to split the chain and make new successor chain
					movedChain = existingChain.removeRange(index, existingChain.getLength());
					movedChainHeadPk = primaryKey;
					this.chains.put(primaryKey, new TransactionalUnorderedIntArray(movedChain));
				} else {
					// but we tackle the head of the chain - so we don't have to do anything
					movedChainHeadPk = primaryKey;
					movedChain = null;
				}
			} else {
				final TransactionalUnorderedIntArray predecessorChain = this.chains.get(predecessorState.inChainOfHeadWithPrimaryKey());
				// if we append the sub-chain to after the last record of the predecessor chain
				if (predecessor.predecessorPk() == predecessorChain.getLastRecordId()) {
					// we can merge both chains together
					movedChainHeadPk = predecessorState.inChainOfHeadWithPrimaryKey();
					if (index > 0) {
						// the element is in the body of the chain, we need to split the chain
						movedChain = existingChain.removeRange(index, existingChain.getLength());
						// append only the sub-chain to the predecessor chain
						predecessorChain.appendAll(movedChain);
						// if the appended chain contains primary key the head of predecessor chain refers to - we have circular dependency
						examineCircularConflictInNewlyAppendedChain(predecessorState.inChainOfHeadWithPrimaryKey(), movedChain);
					} else {
						// the element is the head of the chain, discard the chain completely
						final TransactionalUnorderedIntArray removedChain = this.chains.remove(existingState.inChainOfHeadWithPrimaryKey());
						// and fully merge with predecessor chain
						movedChain = existingChain.getArray();
						predecessorChain.appendAll(movedChain);
						// if there was transactional memory associated with the chain, discard it
						removedChain.removeLayer();
					}
				} else {
					// if the element is in the body of the chain and is already successor of the predecessor
					if (index > 0 && predecessor.predecessorPk() == existingChain.get(index - 1)) {
						// do nothing - the update doesn't affect anything
						return;
					} else if (index > 0) {
						// the element is in the body of the chain, we need to split the chain - we have a situation with
						// multiple successors of the single predecessor
						movedChain = existingChain.removeRange(index, existingChain.getLength());
						movedChainHeadPk = primaryKey;
						this.chains.put(primaryKey, new TransactionalUnorderedIntArray(movedChain));
					} else {
						// leave the current chain be - we need to switch the state to SUCCESSOR only
						movedChain = null;
						movedChainHeadPk = existingState.inChainOfHeadWithPrimaryKey();
					}
				}
			}

			// we need to update specially the state for current element - set the correct chain head, predecessor and state
			this.elementStates.put(primaryKey, new ChainElementState(movedChainHeadPk, predecessor, ElementState.SUCCESSOR));
			// if there was a chain split
			if (movedChain != null) {
				// check whether the new head doesn't introduce circular dependency with new chain
				examineCircularConflictInNewlyAppendedChain(movedChainHeadPk, movedChain);

				// then we need to update the chain head for all other elements in the split chain
				// (except the element itself which was already updated by previous statement)
				for (int i = 1; i < movedChain.length; i++) {
					int movedPk = movedChain[i];
					this.elementStates.put(
						movedPk,
						new ChainElementState(movedChainHeadPk, this.elementStates.get(movedPk))
					);
				}
			}

			// collapse the chains that refer to the last element of any other chain
			collapseChainsIfPossible(movedChainHeadPk);
			if (movedChainHeadPk != existingState.inChainOfHeadWithPrimaryKey()) {
				collapseChainsIfPossible(existingState.inChainOfHeadWithPrimaryKey());
			}

			// verify whether the head of chain was not in circular conflict and if so
			verifyIfCircularDependencyExistsAndIsBroken(primaryKeyOfExistingHeadState, existingStateHeadState);
		}
	}

	/**
	 * Method check whether there is circular dependency between chain head predecessor and the contents of the appended
	 * chain. If so, the chain head is marked as circular.
	 *
	 * @param chainHeadPrimaryKey primary key of the chain head
	 * @param appendedChain       chain that was appended to the chain head
	 */
	private void examineCircularConflictInNewlyAppendedChain(int chainHeadPrimaryKey, int[] appendedChain) {
		final ChainElementState predecessorChainHeadState = this.elementStates.get(chainHeadPrimaryKey);
		final boolean newCircularConflict = ArrayUtils.indexOf(predecessorChainHeadState.predecessorPrimaryKey(), appendedChain) >= 0;
		if (newCircularConflict) {
			this.elementStates.put(
				chainHeadPrimaryKey,
				new ChainElementState(predecessorChainHeadState, ElementState.CIRCULAR)
			);
		}
	}

	/**
	 * Method updates existing element that is known to introduce circular dependency - i.e. it depends on one of its
	 * successors.
	 *
	 * @param primaryKey             primary key of the element
	 * @param index                  index of the element in the chain
	 * @param predecessor            pointer record to a predecessor element of the `primaryKey` element
	 * @param existingChain          existing chain where the element is present
	 * @param primaryKeyOfExistingHeadState primary key the `existingHeadState` is registered in `this.elementStates`
	 * @param existingStateHeadState state of the head element of the existing chain where element is present
	 * @param existingState          existing state of the primary key element
	 */
	private void updateElementWithCircularConflict(
		int primaryKey,
		int index,
		@Nonnull ChainableType predecessor,
		@Nonnull TransactionalUnorderedIntArray existingChain,
		int primaryKeyOfExistingHeadState,
		@Nonnull ChainElementState existingStateHeadState,
		@Nonnull ChainElementState existingState
	) {
		// mark element as circular
		this.elementStates.put(primaryKey, new ChainElementState(primaryKey, predecessor, ElementState.CIRCULAR));
		// if the primary key with circular dependency is not already a head of its chain
		if (existingState.inChainOfHeadWithPrimaryKey() != primaryKey) {
			// we need to set up special chain for it
			Assert.isPremiseValid(index > 0, "When the primary key is not a head of its chain, the index must be greater than zero!");
			final int[] subChain = existingChain.removeRange(index, existingChain.getLength());
			this.chains.put(primaryKey, new TransactionalUnorderedIntArray(subChain));
			reclassifyChain(primaryKey, subChain);
			// we need to re-check whether the original chain has still circular dependency when this chain was split
			verifyIfCircularDependencyExistsAndIsBroken(primaryKeyOfExistingHeadState, existingStateHeadState);
		}
	}

	/**
	 * Method verifies whether the original element visible before changes in the chain was in circular dependency.
	 * If so, the method checks whether the circular dependency still exists for the current state of the chain and
	 * if not, it is resolved.
	 *
	 * @param primaryKeyOfHeadState primary key that was used to get the state from `this.elementStates` for verification
	 * @param originalChainHeadState the state of the original chain head before the change
	 */
	private void verifyIfCircularDependencyExistsAndIsBroken(
		int primaryKeyOfHeadState,
		@Nonnull ChainElementState originalChainHeadState
	) {
		// if original chain head is in circular dependency
		if (originalChainHeadState.state() == ElementState.CIRCULAR) {
			Assert.isPremiseValid(
				primaryKeyOfHeadState == originalChainHeadState.inChainOfHeadWithPrimaryKey(),
				"Primary key of the state must match the primary key of the state chain head!"
			);

			final TransactionalUnorderedIntArray originalHeadChain = this.chains.get(originalChainHeadState.inChainOfHeadWithPrimaryKey());
			if (originalHeadChain != null) {
				// verify it is still in circular dependency
				if (originalHeadChain.indexOf(originalChainHeadState.predecessorPrimaryKey()) < 0) {
					// the circular dependency was broken
					this.elementStates.put(
						primaryKeyOfHeadState,
						new ChainElementState(originalChainHeadState, ElementState.SUCCESSOR)
					);
				}
			} else {
				// the circular dependency was broken - the chain is now part of another chain
				this.elementStates.compute(
					primaryKeyOfHeadState,
					(k, existingState) -> new ChainElementState(
						Objects.requireNonNull(existingState),
						ElementState.SUCCESSOR
					)
				);
			}
		}
	}

	/**
	 * Method updates all {@link #elementStates} for all passed `primaryKeys` to point to the new head of the chain
	 * specified by `inChainOfHeadWithPrimaryKey`. Only the {@link ChainElementState#inChainOfHeadWithPrimaryKey} is
	 * modified - the state and predecessor are left intact.
	 */
	private void reclassifyChain(int inChainOfHeadWithPrimaryKey, @Nonnull int[] primaryKeys) {
		for (int splitPk : primaryKeys) {
			this.elementStates.compute(
				splitPk,
				(key, movedPkState) -> new ChainElementState(
					inChainOfHeadWithPrimaryKey,
					Objects.requireNonNull(movedPkState)
				)
			);
		}
	}

	/**
	 * Method verifies whether the `element` chain can be collapsed into its predecessor chain, or
	 * whether any other chain can be collapsed to its chain. If so, both chains are merged and the method continues
	 * with checking the newly merged chain for collapsing until no more chains can be collapsed.
	 *
	 * @param element primary key of any element of the chain that is to be collapsed
	 */
	private void collapseChainsIfPossible(int element) {
		Integer movingHeadOfTheChainToVerify = element;
		do {
			movingHeadOfTheChainToVerify = attemptToCollapseChain(movingHeadOfTheChainToVerify);
		} while (movingHeadOfTheChainToVerify != null);
	}

	/**
	 * Method verifies whether the `element` chain can be collapsed into its predecessor chain, or
	 * whether any other chain can be collapsed to its chain. If so, both chains are merged and the method returns
	 * the primary key of the new head of the chain. If not, the method returns NULL.
	 *
	 * @param element primary key of any element of the chain that is to be collapsed
	 * @return primary key of the new head of the chain or NULL if the chain can't be collapsed
	 */
	@Nullable
	private Integer attemptToCollapseChain(int element) {
		final ChainElementState chainHeadState = this.elementStates.get(element);
		if (chainHeadState.state() == ElementState.SUCCESSOR) {
			return mergeSuccessorChainToElementChainIfPossible(chainHeadState);
		} else if (chainHeadState.state == ElementState.HEAD) {
			return findFirstSuccessorChainAndMergeToElementChain(chainHeadState.inChainOfHeadWithPrimaryKey());
		} else {
			return null;
		}
	}

	/**
	 * Method verifies, whether for the predecessor of the head element there is a chain whose last element is
	 * the predecessor. In such case, the chain can be merged with this chain. The chain is then merged with it and
	 * the method returns the primary key of the new head of the chain.
	 *
	 * If this primary lookup fails, the {@link #findFirstSuccessorChainAndMergeToElementChain(int)}
	 * method is called to verify whether we can't collapse another chain with tail element of the chain.
	 *
	 * @param chainHeadState state of the element pointing to chain which head element we check for predecessor chain
	 * @return primary key of the new head of the chain or NULL if the chain can't be collapsed
	 */
	@Nullable
	private Integer mergeSuccessorChainToElementChainIfPossible(
		@Nonnull ChainElementState chainHeadState
	) {
		final ChainElementState predecessorState = this.elementStates.get(chainHeadState.predecessorPrimaryKey());
		// predecessor may not yet be present in the index
		if (predecessorState != null) {
			final TransactionalUnorderedIntArray predecessorChain = this.chains.get(predecessorState.inChainOfHeadWithPrimaryKey());
			if (chainHeadState.predecessorPrimaryKey() == predecessorChain.getLastRecordId()) {
				// we may append the chain to the predecessor chain
				final TransactionalUnorderedIntArray removedChain = this.chains.remove(chainHeadState.inChainOfHeadWithPrimaryKey());
				final int[] movedChain = removedChain.getArray();
				// if there was transactional memory associated with the chain, discard it
				removedChain.removeLayer();
				predecessorChain.appendAll(movedChain);
				reclassifyChain(predecessorState.inChainOfHeadWithPrimaryKey(), movedChain);
				// if the moved chain contains primary key the new head refers to - we have circular dependency
				examineCircularConflictInNewlyAppendedChain(predecessorState.inChainOfHeadWithPrimaryKey(), movedChain);
				return predecessorState.inChainOfHeadWithPrimaryKey();
			}
		}
		return findFirstSuccessorChainAndMergeToElementChain(chainHeadState.inChainOfHeadWithPrimaryKey());
	}

	/**
	 * Method checks whether there is a chain that is marked as a SUCCESSOR of a last element of the chain belonging
	 * to `chainHeadState`. If so, the SUCCESSOR chain is fully merged with the chain of `chainHeadState` and the
	 * method returns the primary key of the new head of the chain.
	 *
	 * @param chainHeadElement the primary key of head of the chain to which we check for successor chain
	 * @return primary key of the new head of the chain or NULL if the chain can't be collapsed
	 */
	@Nullable
	private Integer findFirstSuccessorChainAndMergeToElementChain(int chainHeadElement) {
		final TransactionalUnorderedIntArray chain = this.chains.get(chainHeadElement);
		final int lastRecordId = chain.getLastRecordId();
		final Optional<Integer> collapsableChain = this.chains.keySet()
			.stream()
			.filter(pk -> this.elementStates.get(pk).predecessorPrimaryKey() == lastRecordId)
			.findFirst();
		if (collapsableChain.isPresent()) {
			final Integer collapsableHeadPk = collapsableChain.get();
			final ChainElementState collapsableHeadChainState = this.elementStates.get(collapsableHeadPk);
			final TransactionalUnorderedIntArray chainToBeCollapsed = this.chains.get(collapsableHeadPk);
			if (chainToBeCollapsed.indexOf(collapsableHeadChainState.predecessorPrimaryKey()) >= 0) {
				// we have circular dependency - we can't collapse the chain
				this.elementStates.put(
					collapsableHeadPk,
					new ChainElementState(collapsableHeadChainState, ElementState.CIRCULAR)
				);
				return null;
			} else {
				// we may append the chain to the predecessor chain
				final TransactionalUnorderedIntArray removedChain = this.chains.remove(collapsableHeadPk);
				final int[] movedChain = removedChain.getArray();
				// if there was transactional memory associated with the chain, discard it
				removedChain.removeLayer();
				chain.appendAll(movedChain);
				reclassifyChain(chainHeadElement, movedChain);
				// this collapse introduced new circular dependency
				final ChainElementState chainHeadElementState = this.elementStates.get(chainHeadElement);
				if (ArrayUtils.indexOf(chainHeadElementState.predecessorPrimaryKey(), movedChain) >= 0) {
					this.elementStates.put(
						chainHeadElement,
						new ChainElementState(
							chainHeadElement,
							chainHeadElementState.predecessorPrimaryKey(),
							ElementState.CIRCULAR
						)
					);
				} else if (collapsableHeadChainState.state() == ElementState.CIRCULAR) {
					this.elementStates.put(
						collapsableHeadPk,
						new ChainElementState(
							chainHeadElement,
							collapsableHeadChainState.predecessorPrimaryKey(),
							ElementState.SUCCESSOR
						)
					);
				}
				return collapsableHeadPk;
			}
		}
		return null;
	}

	/**
	 * Enum represents the state of the element in the index.
	 */
	public enum ElementState {
		/**
		 * Element is the head of one of the {@link #chains} and have no predecessor.
		 */
		HEAD,
		/**
		 * Element have defined predecessor element. Element might be the head of one of the {@link #chains} in case
		 * it is in inconsistent state (i.e. there are multiple elements referring to same predecessor).
		 */
		SUCCESSOR,
		/**
		 * Element is head of the chain that and it is in circular dependency with some of the elements in its tail.
		 */
		CIRCULAR
	}

	/**
	 * Record represents a state for each element in this index.
	 *
	 * @param inChainOfHeadWithPrimaryKey primary key of the head of the {@link #chains} this element is part of
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

		/**
		 * Constructor, which allows to override only the chain placement, leaving predecessor and state be.
		 */
		public ChainElementState(
			int inChainOfHeadWithPrimaryKey,
			@Nonnull ChainElementState previousState
		) {
			this(inChainOfHeadWithPrimaryKey, previousState.predecessorPrimaryKey, previousState.state);
		}

		/**
		 * Constructor, which allows to override only the state, leaving the chain placement and predecessor be.
		 */
		public ChainElementState(
			@Nonnull ChainElementState previousState,
			@Nonnull ElementState newState
		) {
			this(previousState.inChainOfHeadWithPrimaryKey, previousState.predecessorPrimaryKey, newState);
		}

		@Nonnull
		@Override
		public String toString() {
			switch (this.state) {
				case HEAD -> {
					return "HEAD \uD83D\uDD17 " + this.inChainOfHeadWithPrimaryKey;
				}
				case SUCCESSOR -> {
					return "SUCCESSOR of " + this.predecessorPrimaryKey + " \uD83D\uDD17 " + this.inChainOfHeadWithPrimaryKey;
				}
				case CIRCULAR -> {
					return "CIRCULAR of " + this.predecessorPrimaryKey + " \uD83D\uDD17 " + this.inChainOfHeadWithPrimaryKey;
				}
				default -> throw new IllegalStateException("Unexpected value: " + this.state);
			}
		}
	}

}
