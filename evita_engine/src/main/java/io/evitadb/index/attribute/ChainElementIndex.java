/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.attribute;

import io.evitadb.core.Transaction;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.dataType.Predecessor;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainer;
import io.evitadb.index.transactionalMemory.TransactionalObjectVersion;
import io.evitadb.index.transactionalMemory.VoidTransactionMemoryProducer;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Stream;

import static io.evitadb.core.Transaction.isTransactionAvailable;

/**
 * This is a special index for data type of {@link io.evitadb.dataType.Predecessor}.
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
public class ChainElementIndex implements VoidTransactionMemoryProducer<ChainElementIndex>, IndexDataStructure, Serializable {
	@Serial private static final long serialVersionUID = 6633952268102524794L;

	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	@Nonnull private final TransactionalBoolean dirty;
	/**
	 * Index contains information about non-interrupted chains of predecessors for an entity which is not a head entity
	 * but is part of different chain (inconsistent state).
	 */
	private final TransactionalMap<Integer, TransactionalUnorderedIntArray> chains;
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
	private final TransactionalMap<Integer, ChainElementState> elementStates;
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
	@Nullable private transient Formula memoizedAllRecordsFormula;

	public ChainElementIndex() {
		this.dirty = new TransactionalBoolean();
		this.chains = new TransactionalMap<>(new HashMap<>());
		this.elementStates = new TransactionalMap<>(new HashMap<>());
	}

	public ChainElementIndex(
		@Nonnull Map<Integer, TransactionalUnorderedIntArray> chains,
		@Nonnull Map<Integer, ChainElementState> elementStates
	) {
		this.dirty = new TransactionalBoolean();
		this.chains = new TransactionalMap<>(chains);
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
	 * Returns formula that returns a single chain representing the most consistent view of the index.
	 */
	@Nonnull
	public Formula getChainFormula() {
		// if there is transaction open, there might be changes in the histogram data, and we can't easily use cache
		if (isTransactionAvailable() && this.dirty.isTrue()) {
			return createConsistentChainFormula();
		} else {
			if (this.memoizedAllRecordsFormula == null) {
				this.memoizedAllRecordsFormula = createConsistentChainFormula();
			}
			return this.memoizedAllRecordsFormula;
		}
	}

	/**
	 * Method adds or updates existing `predecessor` information for the `primaryKey` element in the index.
	 *
	 * @param primaryKey  primary key of the element
	 * @param predecessor pointer record to a predecessor element of the `primaryKey` element
	 */
	public void upsertPredecessor(int primaryKey, @Nonnull Predecessor predecessor) {
		final ChainElementState existingState = this.elementStates.get(primaryKey);
		// if existing state is not found - we need to insert new one
		if (existingState == null) {
			insertPredecessor(primaryKey, predecessor);
		} else {
			updatePredecessor(primaryKey, predecessor, existingState);
		}
		if (!isTransactionAvailable()) {
			this.memoizedAllRecordsFormula = null;
		}
		this.dirty.setToTrue();
	}

	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.dirty.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public ChainElementIndex createCopyWithMergedTransactionalMemory(
		@Nullable Void layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer,
		@Nullable Transaction transaction
	) {
		return new ChainElementIndex(
			transactionalLayer.getStateCopyWithCommittedChanges(this.chains, transaction),
			transactionalLayer.getStateCopyWithCommittedChanges(this.elementStates, transaction)
		);
	}

	/**
	 * Method inserts new element into the index along with its predecessor information.
	 *
	 * @param primaryKey  primary key of the element
	 * @param predecessor pointer record to a predecessor element of the `primaryKey` element
	 */
	private void insertPredecessor(int primaryKey, @Nonnull Predecessor predecessor) {
		// the primary key was observed for the first time
		if (predecessor.isHead()) {
			// setup the new head of the chain
			this.chains.put(primaryKey, new TransactionalUnorderedIntArray(new int[]{primaryKey}));
			this.elementStates.put(primaryKey, new ChainElementState(primaryKey, predecessor, ElementState.HEAD));
		} else {
			final ChainElementState predecessorState = this.elementStates.get(predecessor.predecessorId());
			if (predecessorState == null) {
				// the predecessor is not part of the index yet - we need to wait for it
				introduceNewSuccessorChain(primaryKey, predecessor);
			} else {
				// the predecessor was found in the index - we can add the new element to its chain
				final int chainHeadId = predecessorState.inChainOfHeadWithPrimaryKey();
				final TransactionalUnorderedIntArray predecessorChain = this.chains.get(chainHeadId);
				// if the predecessor is the last record of the predecessor chain
				if (predecessorChain.getLastRecordId() == predecessor.predecessorId()) {
					// we may safely append the primary key to it
					predecessorChain.add(predecessor.predecessorId(), primaryKey);
					this.elementStates.put(primaryKey, new ChainElementState(chainHeadId, predecessor, ElementState.SUCCESSOR));
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
	private void introduceNewSuccessorChain(int primaryKey, @Nonnull Predecessor predecessor) {
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
	private void updatePredecessor(int primaryKey, @Nonnull Predecessor predecessor, @Nonnull ChainElementState existingState) {
		// the primary key was already present in the index - we need to relocate it
		final TransactionalUnorderedIntArray existingChain = this.chains.get(existingState.inChainOfHeadWithPrimaryKey());
		final int index = existingChain.indexOf(primaryKey);
		// sanity check - the primary key must be present in the chain according to the state information
		Assert.isPremiseValid(
			index >= 0,
			"Index damaged! The primary key `" + primaryKey + "` must be present in the chain according to the state information!"
		);
		// we need to remember original state of the chain head before changes in order to resolve possible circular dependency
		final ChainElementState existingStateHeadState = this.elementStates.get(existingState.inChainOfHeadWithPrimaryKey());
		// if newly created element is a head of its chain
		if (predecessor.isHead()) {
			updateElementToBecomeHeadOfTheChain(primaryKey, index, predecessor, existingChain, existingStateHeadState);
		} else {
			updateElementWithinExistingChain(primaryKey, index, predecessor, existingChain, existingStateHeadState, existingState);
		}
	}

	/**
	 * Method updates existing element in the index and makes it a head of its chain.
	 *
	 * @param primaryKey             primary key of the element
	 * @param index                  index of the element in the chain
	 * @param predecessor            pointer record to a predecessor element of the `primaryKey` element
	 * @param existingChain          existing chain where the element is present
	 * @param existingStateHeadState state of the head element of the existing chain where element is present
	 */
	private void updateElementToBecomeHeadOfTheChain(
		int primaryKey,
		int index, @Nonnull Predecessor predecessor,
		@Nonnull TransactionalUnorderedIntArray existingChain,
		@Nonnull ChainElementState existingStateHeadState
	) {
		// if the primary key is not located at the head of the chain
		if (index > 0) {
			// we need to promote its sub-chain as the new head of the chain
			// setup the new head of the chain
			final int[] subChain = existingChain.removeRange(index, existingChain.getLength());
			this.chains.put(primaryKey, new TransactionalUnorderedIntArray(subChain));
			reclassifyChain(primaryKey, subChain);
			// we need to re-check whether the original chain has still circular dependency when this chain was split
			verifyIfCircularDependencyExistsAndIsBroken(primaryKey, existingStateHeadState);
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
	 * @param existingStateHeadState state of the head element of the existing chain where element is present
	 * @param existingState          existing state of the primary key element
	 */
	private void updateElementWithinExistingChain(
		int primaryKey,
		int index,
		@Nonnull Predecessor predecessor,
		@Nonnull TransactionalUnorderedIntArray existingChain,
		@Nonnull ChainElementState existingStateHeadState,
		@Nonnull ChainElementState existingState
	) {
		// we need to relocate the element to the chain of its predecessor
		final boolean circularConflict = existingChain.indexOf(predecessor.predecessorId()) >= index;
		// if there is circular conflict - set up a separate chain and update state
		if (circularConflict) {
			updateElementWithCircularConflict(
				primaryKey, index, predecessor, existingChain, existingStateHeadState, existingState
			);
		} else {
			final int[] movedChain;
			final int movedChainHeadPk;
			final int movedChainTailPk;

			final ChainElementState predecessorState = this.elementStates.get(predecessor.predecessorId());
			final TransactionalUnorderedIntArray predecessorChain = this.chains.get(predecessorState.inChainOfHeadWithPrimaryKey());
			// if we append the sub-chain to after the last record of the predecessor chain
			if (predecessor.predecessorId() == predecessorChain.getLastRecordId()) {
				// we can merge both chains together
				movedChainHeadPk = predecessorState.inChainOfHeadWithPrimaryKey();
				if (index > 0) {
					// the element is in the body of the chain, we need to split the chain
					movedChain = existingChain.removeRange(index, existingChain.getLength());
					// append only the sub-chain to the predecessor chain
					predecessorChain.appendAll(movedChain);
					movedChainTailPk = movedChain[movedChain.length - 1];
				} else {
					// the element is the head of the chain, discard the chain completely
					this.chains.remove(existingState.inChainOfHeadWithPrimaryKey());
					// and fully merge with predecessor chain
					movedChain = existingChain.getArray();
					predecessorChain.appendAll(movedChain);
					movedChainTailPk = existingChain.getLastRecordId();
				}
			} else {
				// if the element is in the body of the chain and is already successor of the predecessor
				if (index > 0 && predecessor.predecessorId() == existingChain.get(index - 1)) {
					// do nothing - the update doesn't affect anything
					return;
				} else if (index > 0) {
					// the element is in the body of the chain, we need to split the chain - we have a situation with
					// multiple successors of the single predecessor
					movedChain = existingChain.removeRange(index, existingChain.getLength());
					movedChainHeadPk = primaryKey;
					this.chains.put(primaryKey, new TransactionalUnorderedIntArray(movedChain));
					movedChainTailPk = movedChain[movedChain.length - 1];
				} else if (existingState.inChainOfHeadWithPrimaryKey() == predecessorState.inChainOfHeadWithPrimaryKey()) {
					// the element is the head of the chain
					// we just need to change the state and predecessor, since the chain is already correct
					movedChainHeadPk = existingState.inChainOfHeadWithPrimaryKey();
					movedChain = null;
					movedChainTailPk = existingChain.getLastRecordId();
				} else {
					// we need to append the sub-chain to the predecessor chain which is already occupied by other chain
					// we need to promote the longer chain and register the conflicting successor chain
					final int existingChainSplitIndex = existingChain.indexOf(predecessor.predecessorId());
					final int existingChainSplitLength = existingChain.getLength() - existingChainSplitIndex;
					final int predecessorSplitIndex = predecessorChain.indexOf(predecessor.predecessorId());
					final int predecessorChainSplitLength = predecessorChain.getLength() - predecessorSplitIndex;
					// if existing chain is longer than the predecessor one
					if (existingChainSplitLength > predecessorChainSplitLength) {
						// split the predecessor chain and append the current chain to it
						final int[] splitPks = predecessorChain.removeRange(predecessorSplitIndex, predecessorChain.getLength());
						movedChain = existingChain.getArray();
						movedChainHeadPk = predecessorState.inChainOfHeadWithPrimaryKey();
						predecessorChain.appendAll(movedChain);
						movedChainTailPk = movedChain[movedChain.length - 1];
						// setup the new head of the chain for the tail part of split chain
						this.chains.put(splitPks[0], new TransactionalUnorderedIntArray(splitPks));
						reclassifyChain(splitPks[0], splitPks);
					} else {
						// leave the current chain be - we need to switch the state to SUCCESSOR only
						movedChain = null;
						movedChainHeadPk = existingState.inChainOfHeadWithPrimaryKey();
						movedChainTailPk = existingChain.getLastRecordId();
					}
				}
			}

			// we need to update specially the state for current element - set the correct chain head, predecessor and state
			this.elementStates.put(primaryKey, new ChainElementState(movedChainHeadPk, predecessor, ElementState.SUCCESSOR));
			// if there was a chain split
			if (movedChain != null) {
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

			// verify whether the head of chain was not in circular conflict and if so
			verifyIfCircularDependencyExistsAndIsBroken(primaryKey, existingStateHeadState);
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
	 * @param existingStateHeadState state of the head element of the existing chain where element is present
	 * @param existingState          existing state of the primary key element
	 */
	private void updateElementWithCircularConflict(
		int primaryKey,
		int index,
		@Nonnull Predecessor predecessor,
		@Nonnull TransactionalUnorderedIntArray existingChain,
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
			verifyIfCircularDependencyExistsAndIsBroken(primaryKey, existingStateHeadState);
		}
	}

	/**
	 * Method verifies whether the original element visible before changes in the chain was in circular dependency.
	 * If so, the method checks whether the circular dependency still exists for the current state of the chain and
	 * if not, it is resolved.
	 *
	 * @param newChainHeadPrimaryKey the chain that triggered the circular dependency check
	 * @param originalChainHeadState the state of the original chain head before the change
	 */
	private void verifyIfCircularDependencyExistsAndIsBroken(
		int newChainHeadPrimaryKey,
		@Nonnull ChainElementState originalChainHeadState
	) {
		// if original chain head is in circular dependency
		if (originalChainHeadState.state() == ElementState.CIRCULAR) {
			final TransactionalUnorderedIntArray originalHeadChain = this.chains.get(originalChainHeadState.inChainOfHeadWithPrimaryKey());
			// verify it is still in circular dependency
			if (originalHeadChain.indexOf(originalChainHeadState.predecessorPrimaryKey()) < 0) {
				// the circular dependency was broken

				// if the last record of the chain equals to predecessor, we may switch the state to successor, or append it
				if (originalHeadChain.getLastRecordId() == originalChainHeadState.predecessorPrimaryKey()) {
					// the last record of the chain is the predecessor, we may remove it entirely
					this.chains.remove(originalChainHeadState.inChainOfHeadWithPrimaryKey());
					// and append the chain to the predecessor chain
					originalHeadChain.appendAll(originalHeadChain.getArray());
					// change the circular state to successor
					this.elementStates.put(
						originalChainHeadState.inChainOfHeadWithPrimaryKey(),
						new ChainElementState(originalChainHeadState, ElementState.SUCCESSOR)
					);
					// and reclassify chain head of all moved elements
					reclassifyChain(newChainHeadPrimaryKey, originalHeadChain.getArray());
				} else {
					// the last record of the chain is not the predecessor, we have to leave the chain,
					// but we can change the state of the element
					this.elementStates.put(
						originalChainHeadState.inChainOfHeadWithPrimaryKey(),
						new ChainElementState(originalChainHeadState, ElementState.SUCCESSOR)
					);
				}
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
			final ChainElementState movedPkState = this.elementStates.get(splitPk);
			this.elementStates.put(splitPk, new ChainElementState(inChainOfHeadWithPrimaryKey, movedPkState));
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
			return findFirstSuccessorChainAndMergeToElementChain(chainHeadState);
		} else {
			return null;
		}
	}

	/**
	 * Method verifies, whether for the predecessor of the head element there is a chain whose last element is
	 * the predecessor. In such case, the chain can be merged with this chain. The chain is then merged with it and
	 * the method returns the primary key of the new head of the chain.
	 *
	 * If this primary lookup fails, the {@link #findFirstSuccessorChainAndMergeToElementChain(ChainElementState)}
	 * method is called to verify whether we can't collapse another chain with tail element of the chain.
	 *
	 * @param chainHeadState state of the element pointing to chain which head element we check for predecessor chain
	 * @return primary key of the new head of the chain or NULL if the chain can't be collapsed
	 */
	@Nullable
	private Integer mergeSuccessorChainToElementChainIfPossible(@Nonnull ChainElementState chainHeadState) {
		final ChainElementState predecessorState = this.elementStates.get(chainHeadState.predecessorPrimaryKey());
		// predecessor may not yet be present in the index
		if (predecessorState != null) {
			final TransactionalUnorderedIntArray predecessorChain = this.chains.get(predecessorState.inChainOfHeadWithPrimaryKey());
			if (chainHeadState.predecessorPrimaryKey() == predecessorChain.getLastRecordId()) {
				// we may append the chain to the predecessor chain
				final int[] movedChain = this.chains.remove(chainHeadState.inChainOfHeadWithPrimaryKey()).getArray();
				predecessorChain.appendAll(movedChain);
				reclassifyChain(predecessorState.inChainOfHeadWithPrimaryKey(), movedChain);
				return predecessorState.inChainOfHeadWithPrimaryKey();
			} else {
				return findFirstSuccessorChainAndMergeToElementChain(chainHeadState);
			}
		} else {
			return null;
		}
	}

	/**
	 * Method checks whether there is a chain that is marked as a SUCCESSOR of a last element of the chain belonging
	 * to `chainHeadState`. If so, the SUCCESSOR chain is fully merged with the chain of `chainHeadState` and the
	 * method returns the primary key of the new head of the chain.
	 *
	 * @param chainHeadState state of the element pointing to chain for which we are looking for SUCCESSOR chain
	 * @return primary key of the new head of the chain or NULL if the chain can't be collapsed
	 */
	@Nullable
	private Integer findFirstSuccessorChainAndMergeToElementChain(@Nonnull ChainElementState chainHeadState) {
		final int chainHeadElement = chainHeadState.inChainOfHeadWithPrimaryKey();
		final TransactionalUnorderedIntArray chain = this.chains.get(chainHeadElement);
		final int lastRecordId = chain.getLastRecordId();
		final Optional<Integer> collapsableChain = this.chains.keySet()
			.stream()
			.filter(pk -> {
				final ChainElementState theState = this.elementStates.get(pk);
				return theState.state() == ElementState.SUCCESSOR && theState.predecessorPrimaryKey() == lastRecordId;
			})
			.findFirst();
		if (collapsableChain.isPresent()) {
			// we may append the chain to the predecessor chain
			final int[] movedChain = this.chains.remove(collapsableChain.get()).getArray();
			chain.appendAll(movedChain);
			reclassifyChain(chainHeadElement, movedChain);
			return collapsableChain.get();
		} else {
			return null;
		}
	}

	/**
	 * Creates formula that returns a single chain representing the most consistent view of the index.
	 * It combines all known chains into a single one.
	 */
	@Nonnull
	private Formula createConsistentChainFormula() {
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
		return new ConstantFormula(new ArrayBitmap(result));
	}

	/**
	 * Enum represents the state of the element in the index.
	 */
	private enum ElementState {
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
	private record ChainElementState(
		int inChainOfHeadWithPrimaryKey,
		int predecessorPrimaryKey,
		@Nonnull ElementState state
	) {

		/**
		 * Constructor allowing to override all settings of the element.
		 */
		public ChainElementState(
			int inChainOfHeadWithPrimaryKey,
			@Nonnull Predecessor predecessor,
			@Nonnull ElementState elementState
		) {
			this(inChainOfHeadWithPrimaryKey, predecessor.predecessorId(), elementState);
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

		@Override
		public String toString() {
			switch (state) {
				case HEAD -> {
					return "HEAD \uD83D\uDD17 " + inChainOfHeadWithPrimaryKey;
				}
				case SUCCESSOR -> {
					return "SUCCESSOR of " + predecessorPrimaryKey + " \uD83D\uDD17 " + inChainOfHeadWithPrimaryKey;
				}
				case CIRCULAR -> {
					return "CIRCULAR of " + predecessorPrimaryKey + " \uD83D\uDD17 " + inChainOfHeadWithPrimaryKey;
				}
				default -> throw new IllegalStateException("Unexpected value: " + state);
			}
		}
	}

}
