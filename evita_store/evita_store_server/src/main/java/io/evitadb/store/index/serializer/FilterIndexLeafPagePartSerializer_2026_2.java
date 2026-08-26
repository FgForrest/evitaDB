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
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;

import javax.annotation.Nonnull;

/**
 * This {@link Serializer} implementation reads a {@link FilterIndexLeafPagePart} from the binary format shipped by
 * release 2026.2 — the shape that predates the parallel value id column. That format ends with the last bucket and
 * carries no id section at all.
 *
 * The substitution for the absent section is `null`, which is exactly right: a leaf page written before value ids
 * existed belongs to a shared value tree that carried none. The tree it is loaded into will likewise carry none until
 * some subsystem registers as a consumer of its ids, at which point the whole tree is back-filled at once.
 *
 * Reading is inherited verbatim from {@link BucketLeafPagePartSerializer} — the pre-value-id payload IS the shared
 * bucket-page frame, unchanged — so this class only pins the frame to the old page shape. Writing is refused;
 * writes always go through the current {@link FilterIndexLeafPagePartSerializer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @deprecated kept for backward compatibility; can be removed once no catalog written before value ids were
 *             introduced is still in use.
 */
@Deprecated(since = "2026.3", forRemoval = true)
public class FilterIndexLeafPagePartSerializer_2026_2 extends BucketLeafPagePartSerializer<FilterIndexLeafPagePart> {

	@Override
	protected int streamId(@Nonnull FilterIndexLeafPagePart page) {
		return page.getStreamId();
	}

	@Override
	protected int pageSequence(@Nonnull FilterIndexLeafPagePart page) {
		return page.getPageSequence();
	}

	@Nonnull
	@Override
	protected ValueToRecord[] buckets(@Nonnull FilterIndexLeafPagePart page) {
		return page.getBuckets();
	}

	/**
	 * Refuses to write. The enclosing `write` of the shared frame is final, so the refusal is placed here — the one
	 * overridable point every write must pass through.
	 */
	@Override
	protected void writePayload(
		@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull FilterIndexLeafPagePart page
	) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used for writing.");
	}

	@Nonnull
	@Override
	protected FilterIndexLeafPagePart create(
		int streamId, int pageSequence, @Nonnull ValueToRecord[] buckets
	) {
		return new FilterIndexLeafPagePart(
			streamId, pageSequence, buckets, null, AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence)
		);
	}

}
