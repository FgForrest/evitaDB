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

package io.evitadb.store.entity.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceDecodeCoverage;
import io.evitadb.spi.store.catalog.persistence.ReferenceDecodeCoverageContext;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.utils.Assert;

import java.util.Arrays;
import java.util.Set;

/**
 * This {@link Serializer} implementation reads/writes {@link ReferencesStoragePart} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReferencesStoragePartSerializer extends Serializer<ReferencesStoragePart> {
	/**
	 * Initial size of the target array when a decode coverage is active. A narrowed read keeps the references the
	 * coverage admits only - typically a handful - while the record itself may hold tens of thousands, so the array
	 * starts small and doubles instead of being sized by the record's reference count.
	 */
	private static final int INITIAL_FILTERED_CAPACITY = 16;

	@Override
	public void write(Kryo kryo, Output output, ReferencesStoragePart object) {
		// a part decoded under a coverage is a narrowed view, never the entity's reference set - writing it back
		// would silently drop every reference the read skipped. This is the last barrier before bytes are emitted,
		// and it covers direct writes, trapped flushes and explicit binary serialization alike.
		Assert.isPremiseValid(
			object.isComplete(),
			() -> new GenericEvitaInternalError(
				"References storage part of entity with primary key " + object.getEntityPrimaryKey() + " was decoded " +
					"only for " + object.getDecodeCoverage() + " and must not be serialized!"
			)
		);
		// then continue with serialization
		output.writeInt(object.getEntityPrimaryKey());
		output.writeVarInt(object.getLastUsedPrimaryKey(), true);

		final Reference[] references = object.getReferences();
		output.writeVarInt(references.length, true);
		for (Reference reference : references) {
			kryo.writeObject(output, reference);
		}
	}

	@Override
	public ReferencesStoragePart read(Kryo kryo, Input input, Class<? extends ReferencesStoragePart> type) {
		final long totalBefore = input.total();
		final int entityPrimaryKey = input.readInt();

		final int lastAssignedPrimaryKey = input.readVarInt(true);
		final int referenceCount = input.readVarInt(true);
		final ReferenceDecodeCoverage coverage = ReferenceDecodeCoverageContext.getDecodeCoverage();
		// when reading references from the disk we remove all dropped references
		// when duplicate references were introduced, it leads to situation where many relations are dropped and new
		// relations are added - in such case the storage part would bloat with dropped relations that are not needed
		// anymore
		// this mechanism ensures, that the fact that the reference was dropped is persisted, but this fact doesn't
		// accumulate and with next rewrite of the storage part on the disk all previously dropped references are removed
		// references the coverage rejected arrive as NULL and are discarded the same way - the target array is grown
		// on demand in that case, because the entity may carry orders of magnitude more references than the
		// projection asked for and sizing it by `referenceCount` would allocate the very array the coverage exists
		// to avoid
		Reference[] references = new Reference[
			coverage == null ? referenceCount : Math.min(referenceCount, INITIAL_FILTERED_CAPACITY)
			];
		int kept = 0;
		for (int i = 0; i < referenceCount; i++) {
			final Reference reference = kryo.readObject(input, Reference.class);
			if (reference == null) {
				// NULL is how the reference serializer reports "rejected by the coverage"; with no coverage bound
				// there is nothing that may skip a reference, so a NULL here means the stream and the decoder
				// disagree about the record layout. Thrown directly rather than through `Assert` - the supplier form
				// would allocate a capturing lambda for every skipped reference, which is the cost the coverage
				// exists to avoid, and this branch runs tens of thousands of times per narrowed record.
				if (coverage == null) {
					throw new GenericEvitaInternalError(
						"Reference #" + i + " of entity with primary key " + entityPrimaryKey +
							" was skipped although no decode coverage was bound!"
					);
				}
				continue;
			}
			if (reference.dropped()) {
				continue;
			}
			if (kept == references.length) {
				// `kept` is INITIAL_FILTERED_CAPACITY or more whenever this fires, so doubling always grows
				references = Arrays.copyOf(references, Math.min(referenceCount, kept << 1));
			}
			references[kept++] = reference;
		}

		return new ReferencesStoragePart(
			entityPrimaryKey, lastAssignedPrimaryKey,
			kept == references.length ? references : Arrays.copyOfRange(references, 0, kept),
			Math.toIntExact(input.total() - totalBefore),
			coverage
		);
	}

}
