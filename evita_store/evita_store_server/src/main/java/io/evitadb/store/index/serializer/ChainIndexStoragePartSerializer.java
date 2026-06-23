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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.ChainIndex.ChainElementState;
import io.evitadb.index.attribute.ChainIndex.ElementState;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexStoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * This {@link Serializer} implementation reads/writes {@link ChainIndex} from/to binary format.
 *
 * The persisted format is the slim per-chain format: the pre-slimming format mirrored the
 * fat per-element {@link ChainElementState} map on disk (~3 ints + 1 enum per element) even though the chain runs
 * already encode almost everything. The slim format keeps, per chain, only the run primary keys (once), the head's
 * predecessor primary key and the head's {@link ElementState} (one byte). The remaining per-element state is fully
 * derivable on read because:
 *
 * - `inChainOfHeadWithPrimaryKey` of every element equals the run head (`run[0]`);
 * - a non-head element's predecessor equals the previous element in the run (the invariant
 *   {@link ChainIndex#getConsistencyReport()} enforces);
 * - a non-head element's state is always {@link ElementState#SUCCESSOR}.
 *
 * On read the same fat {@link ChainElementState} map that {@link ChainIndex#createStoragePart(int)} produced is
 * reconstructed, so {@link ChainIndexStoragePart} and the load path (the four-arg {@link ChainIndex} constructor and
 * the attribute index loader) are untouched. The previous fat format is read by
 * {@link ChainIndexStoragePartSerializer_2026_1}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class ChainIndexStoragePartSerializer extends Serializer<ChainIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, ChainIndexStoragePart chainIndex) {
		output.writeInt(chainIndex.getEntityIndexPrimaryKey());
		final Long uniquePartId = chainIndex.getStoragePartPK();
		Assert.notNull(uniquePartId, "Unique part id should have been computed by now!");
		output.writeVarLong(uniquePartId, true);
		output.writeVarInt(this.keyCompressor.getId(chainIndex.getAttributeIndexKey()), true);

		// the fat element-state map is still carried by the part on the heap; the slim wire derives the single
		// non-redundant datum per chain (head predecessor + head state) from the head element's state entry
		final Map<Integer, ChainElementState> elementStates = chainIndex.getElementStates();
		final int[][] chains = chainIndex.getChains();
		output.writeVarInt(chains.length, true);
		for (int[] chain : chains) {
			output.writeVarInt(chain.length, true);
			output.writeInts(chain, 0, chain.length);
			// every non-empty chain has a head; an empty chain carries no derivable head datum
			if (chain.length > 0) {
				final ChainElementState headState = elementStates.get(chain[0]);
				Assert.notNull(
					headState,
					"Index damaged! The head `" + chain[0] + "` of a persisted chain has no element state entry!"
				);
				output.writeInt(headState.predecessorPrimaryKey());
				output.writeVarInt(headState.state().ordinal(), true);
			}
		}
	}

	@Override
	public ChainIndexStoragePart read(Kryo kryo, Input input, Class<? extends ChainIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final AttributeIndexKey attributeKey = this.keyCompressor.getKeyForId(input.readVarInt(true));

		final int chainCount = input.readVarInt(true);
		final int[][] chains = new int[chainCount][];
		// the element-state map is rebuilt to be structurally identical (same content) to the one createStoragePart
		// emitted, so the load path (ChainIndexStoragePart + the four-arg ChainIndex constructor) needs no change
		final Map<Integer, ChainElementState> elementStates = CollectionUtils.createHashMap(chainCount << 1);
		for (int i = 0; i < chainCount; i++) {
			final int chainLength = input.readVarInt(true);
			final int[] run = input.readInts(chainLength);
			chains[i] = run;
			if (chainLength > 0) {
				final int headPredecessorPk = input.readInt();
				final ElementState headState = ElementState.values()[input.readVarInt(true)];
				final int headPk = run[0];
				// the head keeps its persisted predecessor + state verbatim
				elementStates.put(headPk, new ChainElementState(headPk, headPredecessorPk, headState));
				// every non-head element is, by the chain invariant, a SUCCESSOR of the previous element in the run
				for (int j = 1; j < run.length; j++) {
					elementStates.put(run[j], new ChainElementState(headPk, run[j - 1], ElementState.SUCCESSOR));
				}
			}
		}

		return new ChainIndexStoragePart(
			entityIndexPrimaryKey, attributeKey, elementStates, chains, uniquePartId
		);
	}

}
