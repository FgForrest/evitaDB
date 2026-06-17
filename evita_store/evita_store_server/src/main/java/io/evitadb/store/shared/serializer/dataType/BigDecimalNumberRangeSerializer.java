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

package io.evitadb.store.shared.serializer.dataType;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.NumberRange;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * This {@link Serializer} implementation reads/writes {@link NumberRange} from/to binary format.
 *
 * Open-ended ranges (a `null` lower or upper precise bound, as produced by
 * {@link BigDecimalNumberRange#from(BigDecimal)}, {@link BigDecimalNumberRange#to(BigDecimal)} or
 * {@link BigDecimalNumberRange#INFINITE}) are encoded without changing the wire format. The storage Kryo runs
 * with `setReferences(false)`, so `writeObject` emits the raw bytes with no null-flag prefix and cannot accept a
 * `null` argument — switching to `writeObjectOrNull` would prepend an extra flag byte and silently break reads of
 * every already-persisted closed range. Instead a `null` precise bound is written as a {@link BigDecimal#ZERO}
 * placeholder, and the open bound is recovered on read from the comparable-long sentinels the format already
 * carries: an open lower bound has its `from` long equal to {@link Long#MIN_VALUE}, an open upper bound has its
 * `to` long equal to {@link Long#MAX_VALUE} (guaranteed by {@link BigDecimalNumberRange}'s constructors). A closed
 * range never hits the sentinels, so its precise bounds and byte layout are exactly as before.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class BigDecimalNumberRangeSerializer extends Serializer<BigDecimalNumberRange> {

	@Override
	public void write(Kryo kryo, Output output, BigDecimalNumberRange numberRange) {
		// a null precise bound is stored as a ZERO placeholder; the sentinel comparable long marks it open on read
		final BigDecimal preciseFrom = numberRange.getPreciseFrom();
		final BigDecimal preciseTo = numberRange.getPreciseTo();
		kryo.writeObject(output, preciseFrom != null ? preciseFrom : BigDecimal.ZERO);
		kryo.writeObject(output, preciseTo != null ? preciseTo : BigDecimal.ZERO);
		kryo.writeObjectOrNull(output, numberRange.getRetainedDecimalPlaces(), Integer.class);
		output.writeLong(numberRange.getFrom());
		output.writeLong(numberRange.getTo());
	}

	@Override
	public BigDecimalNumberRange read(Kryo kryo, Input input, Class<? extends BigDecimalNumberRange> type) {
		final BigDecimal rawFrom = kryo.readObject(input, BigDecimal.class);
		final BigDecimal rawTo = kryo.readObject(input, BigDecimal.class);
		final Integer retainedDecimalPlaces = kryo.readObjectOrNull(input, Integer.class);
		final long from = input.readLong();
		final long to = input.readLong();
		// restore an open bound from its sentinel comparable long, otherwise keep the precise bound as read
		final BigDecimal preciseFrom = from == Long.MIN_VALUE ? null : rawFrom;
		final BigDecimal preciseTo = to == Long.MAX_VALUE ? null : rawTo;
		return BigDecimalNumberRange._internalBuild(preciseFrom, preciseTo, retainedDecimalPlaces, from, to);
	}

}
