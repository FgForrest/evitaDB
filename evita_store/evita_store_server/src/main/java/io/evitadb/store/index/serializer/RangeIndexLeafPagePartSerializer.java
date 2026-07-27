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

import com.esotericsoftware.kryo.Serializer;
import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPagePart;

import javax.annotation.Nonnull;

/**
 * This {@link Serializer} implementation reads/writes a {@link RangeIndexLeafPagePart} — one leaf page of a granular
 * FilterIndex range tree — from/to binary format. The `(streamId, pageSequence)` pair fully determines the
 * storage-part primary key (via `join`), so the key is recomputed on read rather than stored; only the identifying pair
 * and the leaf's range points are written. Each point is serialized with the already-registered
 * {@link TransactionalRangePoint} serializer. The range-page frame is defined once in {@link RangeLeafPagePartSerializer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class RangeIndexLeafPagePartSerializer extends RangeLeafPagePartSerializer<RangeIndexLeafPagePart> {

	@Override
	protected int streamId(@Nonnull RangeIndexLeafPagePart page) {
		return page.getStreamId();
	}

	@Override
	protected int pageSequence(@Nonnull RangeIndexLeafPagePart page) {
		return page.getPageSequence();
	}

	@Nonnull
	@Override
	protected TransactionalRangePoint[] points(@Nonnull RangeIndexLeafPagePart page) {
		return page.getPoints();
	}

	@Nonnull
	@Override
	protected RangeIndexLeafPagePart create(
		int streamId, int pageSequence, @Nonnull TransactionalRangePoint[] points
	) {
		return new RangeIndexLeafPagePart(
			streamId, pageSequence, points, AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence)
		);
	}

}
