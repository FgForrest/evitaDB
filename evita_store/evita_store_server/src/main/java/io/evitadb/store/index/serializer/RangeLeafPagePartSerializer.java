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
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.range.TransactionalRangePoint;

import javax.annotation.Nonnull;

/**
 * Shared base for the Kryo serializers of `TransactionalRangePoint`-backed leaf pages. On top of the `(streamId,
 * pageSequence)` frame owned by {@link AbstractLeafPagePartSerializer}, a range-shaped leaf page carries a
 * length-prefixed run of `TransactionalRangePoint` points serialized through their already-registered Kryo serializer —
 * framed identically across the indexes that use it.
 *
 * Concrete subclasses supply only the hooks that vary by page type: the two identity accessors (inherited abstract), the
 * range-points accessor, and the factory that rehydrates a page of the concrete type from the wire data. This keeps a
 * single authoritative definition of the range payload instead of copy-pasting it per index.
 *
 * @param <T> the concrete range-backed leaf-page storage-part type
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public abstract class RangeLeafPagePartSerializer<T> extends AbstractLeafPagePartSerializer<T> {

	@Override
	protected void writePayload(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull T page) {
		final TransactionalRangePoint[] points = points(page);
		output.writeVarInt(points.length, true);
		for (final TransactionalRangePoint point : points) {
			kryo.writeObject(output, point);
		}
	}

	@Nonnull
	@Override
	protected T readPayload(@Nonnull Kryo kryo, @Nonnull Input input, int streamId, int pageSequence) {
		final int pointCount = input.readVarInt(true);
		final TransactionalRangePoint[] points = new TransactionalRangePoint[pointCount];
		for (int i = 0; i < pointCount; i++) {
			points[i] = kryo.readObject(input, TransactionalRangePoint.class);
		}
		return create(streamId, pageSequence, points);
	}

	/**
	 * @param page the live page being written
	 * @return the page's range points, in the order they must be persisted
	 */
	@Nonnull
	protected abstract TransactionalRangePoint[] points(@Nonnull T page);

	/**
	 * Rehydrates a leaf page of the concrete type from its wire data. The implementation is expected to recompute the
	 * unique part id from `(streamId, pageSequence)` rather than expect it on the wire.
	 *
	 * @param streamId     the stream id read from the wire
	 * @param pageSequence the page sequence read from the wire
	 * @param points       the range points read from the wire
	 * @return the rehydrated leaf page
	 */
	@Nonnull
	protected abstract T create(int streamId, int pageSequence, @Nonnull TransactionalRangePoint[] points);

}
