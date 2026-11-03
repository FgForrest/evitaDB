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
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.range.TransactionalRangePoint;

/**
 * Backward-compatible {@link Serializer} that reads the {@link TransactionalRangePoint} format shipped by every release
 * up to and including 2026.2: the threshold followed by one bitmap of starts and one of ends.
 *
 * Those releases kept the starts and the ends of a threshold as sets, so a record holding two ranges that share a
 * border stored that border once. The point is returned with occurrence level 1 only — exactly what the release stored,
 * and the right answer wherever no record could have shared a border: every per-record index (one range per record,
 * or a record's array consolidated before it reached the range index), price validity (one validity per internal
 * price id), and every range axis whose contributions happened not to share a threshold. Where two contributions to
 * one record did share one — the referenced-type, group and group-type indexes of range-typed reference attributes,
 * and the range axes of reference histograms — the lost occurrence is not recorded anywhere in these bytes, so this
 * reader does not try to guess it; the storage upgrade rebuilds those axes from their sources instead.
 *
 * Every range axis is written point by point through the one registration of {@link TransactionalRangePoint}, and each
 * point carries its own serial version UID, so this reader serves inline axes and leaf pages alike and none of their
 * containers needed a UID bump of their own.
 *
 * @deprecated only for backward compatibility purposes; can be removed once no catalog written by release 2026.2 or
 * older is still in use.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Deprecated(since = "2026.3", forRemoval = true)
public class TransactionalIntRangePointSerializer_2026_2 extends Serializer<TransactionalRangePoint> {

	@Override
	public void write(Kryo kryo, Output output, TransactionalRangePoint intRangePoint) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public TransactionalRangePoint read(Kryo kryo, Input input, Class<? extends TransactionalRangePoint> type) {
		final long threshold = input.readLong();
		return new TransactionalRangePoint(
			threshold,
			kryo.readObject(input, TransactionalBitmap.class),
			kryo.readObject(input, TransactionalBitmap.class)
		);
	}

}
