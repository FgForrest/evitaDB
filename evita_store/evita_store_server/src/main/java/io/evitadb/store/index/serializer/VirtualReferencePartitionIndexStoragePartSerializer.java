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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceNameKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart.SortValueSlot;
import io.evitadb.store.index.serializer.PagedStreamMetadataSerializer.PagedStreamMetadata;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Objects;

import static java.util.Optional.ofNullable;

/**
 * Reads and writes the root {@link VirtualReferencePartitionIndexStoragePart} of a virtual reference partition index.
 * A `paged` flag selects the shape: the inline SINGLE shape carries the entry columns in the encoding of
 * {@link VirtualReferencePartitionIndexLeafPagePartSerializer#writeEntries}, the PAGED shape only the page-stream
 * metadata. The tuple dictionary follows in both shapes - each id as a positive varint and each tuple as its length
 * followed by its values written with their classes, as
 * {@link RepresentativeReferenceKeySerializer} writes representative attribute values.
 *
 * The sort value slots close the part, in ascending slot id order: each slot id as a positive varint, the compressed
 * id of its {@link AttributeIndexKey}, its comparator base and decimal places, and its value dictionary - each value id
 * as a positive varint followed by the value in the encoding a
 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart} uses for its distinct
 * values, so the element types of a compound travel with each element.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class VirtualReferencePartitionIndexStoragePartSerializer
	extends Serializer<VirtualReferencePartitionIndexStoragePart> {
	/**
	 * Resolves the compressed reference name the part persists instead of the name itself.
	 */
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, VirtualReferencePartitionIndexStoragePart storagePart) {
		output.writeInt(storagePart.getEntityIndexPrimaryKey());
		final long uniquePartId = ofNullable(storagePart.getStoragePartPK())
			.orElseGet(() -> storagePart.computeUniquePartIdAndSet(this.keyCompressor));
		output.writeVarLong(uniquePartId, true);
		output.writeVarInt(this.keyCompressor.getId(new ReferenceNameKey(storagePart.getReferenceName())), true);

		final boolean paged = storagePart.isPaged();
		output.writeBoolean(paged);
		if (paged) {
			PagedStreamMetadataSerializer.writeBody(
				output, storagePart.getHighWaterPageSequence(),
				Objects.requireNonNull(storagePart.getLeafPageSequences())
			);
		} else {
			VirtualReferencePartitionIndexLeafPagePartSerializer.writeEntries(
				output,
				Objects.requireNonNull(storagePart.getKeys()),
				Objects.requireNonNull(storagePart.getPayloads())
			);
		}

		final int[] tupleIds = storagePart.getTupleIds();
		final Serializable[][] tuples = storagePart.getTuples();
		output.writeVarInt(tupleIds.length, true);
		for (int i = 0; i < tupleIds.length; i++) {
			output.writeVarInt(tupleIds[i], true);
			final Serializable[] tuple = tuples[i];
			output.writeVarInt(tuple.length, true);
			for (final Serializable value : tuple) {
				kryo.writeClassAndObject(output, value);
			}
		}

		final SortValueSlot[] sortValueSlots = storagePart.getSortValueSlots();
		output.writeVarInt(sortValueSlots.length, true);
		for (final SortValueSlot slot : sortValueSlots) {
			output.writeVarInt(slot.slotId(), true);
			output.writeVarInt(this.keyCompressor.getId(slot.attributeIndexKey()), true);
			final ComparatorSource[] comparatorBase = slot.comparatorBase();
			SortIndexStoragePartSerializer.writeComparatorBase(kryo, output, comparatorBase);
			output.writeVarInt(slot.indexedDecimalPlaces(), true);
			final int[] valueIds = slot.valueIds();
			final Serializable[] values = slot.values();
			output.writeVarInt(valueIds.length, true);
			for (int i = 0; i < valueIds.length; i++) {
				output.writeVarInt(valueIds[i], true);
				SortIndexStoragePartSerializer.writeComparableValue(kryo, output, values[i], comparatorBase.length);
			}
		}
	}

	@Override
	public VirtualReferencePartitionIndexStoragePart read(
		Kryo kryo, Input input, Class<? extends VirtualReferencePartitionIndexStoragePart> type
	) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final ReferenceNameKey referenceNameKey = this.keyCompressor.getKeyForId(input.readVarInt(true));

		final boolean paged = input.readBoolean();
		int highWaterPageSequence = 0;
		int[] leafPageSequences = null;
		long[][] entries = null;
		if (paged) {
			final PagedStreamMetadata metadata = PagedStreamMetadataSerializer.readBody(input);
			highWaterPageSequence = metadata.highWaterPageSequence();
			leafPageSequences = metadata.leafPageSequences();
		} else {
			entries = VirtualReferencePartitionIndexLeafPagePartSerializer.readEntries(input);
		}

		final int tupleCount = input.readVarInt(true);
		final int[] tupleIds = new int[tupleCount];
		final Serializable[][] tuples = new Serializable[tupleCount][];
		for (int i = 0; i < tupleCount; i++) {
			tupleIds[i] = input.readVarInt(true);
			final Serializable[] tuple = new Serializable[input.readVarInt(true)];
			for (int j = 0; j < tuple.length; j++) {
				tuple[j] = (Serializable) kryo.readClassAndObject(input);
			}
			tuples[i] = tuple;
		}

		final SortValueSlot[] sortValueSlots = new SortValueSlot[input.readVarInt(true)];
		for (int i = 0; i < sortValueSlots.length; i++) {
			final int slotId = input.readVarInt(true);
			final AttributeIndexKey attributeIndexKey = this.keyCompressor.getKeyForId(input.readVarInt(true));
			final ComparatorSource[] comparatorBase = SortIndexStoragePartSerializer.readComparatorBase(kryo, input);
			final int indexedDecimalPlaces = input.readVarInt(true);
			final int valueCount = input.readVarInt(true);
			final int[] valueIds = new int[valueCount];
			final Serializable[] values = new Serializable[valueCount];
			for (int j = 0; j < valueCount; j++) {
				valueIds[j] = input.readVarInt(true);
				values[j] = SortIndexStoragePartSerializer.readComparableValue(kryo, input, comparatorBase.length);
			}
			sortValueSlots[i] = new SortValueSlot(
				slotId, attributeIndexKey, comparatorBase, indexedDecimalPlaces, valueIds, values
			);
		}

		return paged ?
			VirtualReferencePartitionIndexStoragePart.paged(
				entityIndexPrimaryKey, referenceNameKey.referenceName(),
				highWaterPageSequence, leafPageSequences, tupleIds, tuples, sortValueSlots, uniquePartId
			) :
			VirtualReferencePartitionIndexStoragePart.single(
				entityIndexPrimaryKey, referenceNameKey.referenceName(),
				entries[0], entries[1], tupleIds, tuples, sortValueSlots, uniquePartId
			);
	}

}
