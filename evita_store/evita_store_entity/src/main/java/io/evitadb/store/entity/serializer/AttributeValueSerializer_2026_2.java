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

package io.evitadb.store.entity.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * This {@link Serializer} reads {@link AttributeValue} written by release 2026.2 and earlier. The binary layout is
 * identical to {@link AttributeValueSerializer}; the only difference is that an {@link OffsetDateTime} or
 * {@link LocalDateTime} value (scalar or array) lying beyond the window of moments evitaDB can index is clamped to the
 * window edge as it is read.
 *
 * Those releases accepted any moment, notably the JDK sentinels `OffsetDateTime.MAX` / `MIN`, while the temporal
 * indexes of the current release are built on whole milliseconds since the epoch in a `long` and the write path clamps
 * the same values. The migration brings the persisted indexes into the window; this reader does the same for the
 * entity and reference bodies, so a later removal, deletion or schema change presents the indexes with the very key
 * they hold. Values inside the window are returned untouched, and nothing is truncated.
 *
 * The reader cannot be retired as long as a body written by those releases may exist: compaction copies records
 * that were not updated byte for byte, so such a body keeps its old version until its entity is next written.
 *
 * @deprecated kept for backward compatibility; can be removed once no catalog holds an entity body written before
 *             2026.3 - which upgrading to protocol 7 does NOT establish. `Migration_2026_3` rewrites no entity body,
 *             so every body 2026.2 wrote keeps this vintage's `serialVersionUID` and stays readable only through this
 *             class until its entity is next written. This reader therefore outlives the migration rather than
 *             leaving with it. It only reads: `SerialVersionBasedSerializer` writes every value with
 *             {@link AttributeValueSerializer}.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Deprecated(since = "2026.3", forRemoval = true)
@RequiredArgsConstructor
public class AttributeValueSerializer_2026_2 extends Serializer<AttributeValue> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, AttributeValue attributeValue) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public AttributeValue read(Kryo kryo, Input input, Class<? extends AttributeValue> type) {
		final int version = input.readVarInt(true);
		final int keyId = input.readVarInt(true);
		final AttributeKey key = this.keyCompressor.getKeyForId(keyId);
		final Serializable value = clampToSupportedWindow((Serializable) kryo.readClassAndObject(input));
		final boolean dropped = input.readBoolean();
		return new AttributeValue(version, key, value, dropped);
	}

	/**
	 * Clamps an out-of-window temporal value, scalar or array, and returns anything else as it came.
	 *
	 * The dispatch deliberately does not reuse `LegacyOwnerSortIndexCorrection#clampToSupportedWindow`: that class
	 * leaves with `Migration_2026_3`, while this reader cannot be retired (see the class documentation).
	 *
	 * @param value the value read from the stream, possibly `null`
	 * @return the same instance when nothing needed clamping, otherwise the clamped value (a copy in case of an array)
	 */
	@Nullable
	private static Serializable clampToSupportedWindow(@Nullable Serializable value) {
		if (value instanceof OffsetDateTime offsetDateTime) {
			return EvitaDataTypes.clampToSupportedWindow(offsetDateTime);
		} else if (value instanceof LocalDateTime localDateTime) {
			return EvitaDataTypes.clampToSupportedWindow(localDateTime);
		} else if (value instanceof OffsetDateTime[] offsetDateTimes) {
			return clampArray(offsetDateTimes);
		} else if (value instanceof LocalDateTime[] localDateTimes) {
			return clampArray(localDateTimes);
		} else {
			return value;
		}
	}

	/**
	 * Clamps the elements of the array, copying it only when an element lies outside the window. A `null` element
	 * is kept as it is: the array conversion of 2026.2 stored one, and so does this version's.
	 */
	@Nonnull
	private static OffsetDateTime[] clampArray(@Nonnull OffsetDateTime[] values) {
		OffsetDateTime[] result = values;
		for (int i = 0; i < values.length; i++) {
			final OffsetDateTime value = values[i];
			final OffsetDateTime clamped = value == null ? null : EvitaDataTypes.clampToSupportedWindow(value);
			if (clamped != value) {
				if (result == values) {
					result = values.clone();
				}
				result[i] = clamped;
			}
		}
		return result;
	}

	/**
	 * Clamps the elements of the array, copying it only when an element lies outside the window. A `null` element
	 * is kept as it is: the array conversion of 2026.2 stored one, and so does this version's.
	 */
	@Nonnull
	private static LocalDateTime[] clampArray(@Nonnull LocalDateTime[] values) {
		LocalDateTime[] result = values;
		for (int i = 0; i < values.length; i++) {
			final LocalDateTime value = values[i];
			final LocalDateTime clamped = value == null ? null : EvitaDataTypes.clampToSupportedWindow(value);
			if (clamped != value) {
				if (result == values) {
					result = values.clone();
				}
				result[i] = clamped;
			}
		}
		return result;
	}

}
