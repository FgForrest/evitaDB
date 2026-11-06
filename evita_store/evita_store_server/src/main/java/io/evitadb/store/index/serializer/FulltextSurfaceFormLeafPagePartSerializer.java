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
import io.evitadb.index.fulltext.FulltextIndex.SurfaceFormPage;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextSurfaceFormLeafPagePart;
import javax.annotation.Nonnull;

/** Serializes one surface leaf as ordered keys and positive variable-length reference counts. */
public class FulltextSurfaceFormLeafPagePartSerializer
	extends AbstractLeafPagePartSerializer<FulltextSurfaceFormLeafPagePart> {
	/** Creates the serializer registered for surface dictionary leaves. */
	public FulltextSurfaceFormLeafPagePartSerializer() {
	}

	@Override
	protected int streamId(@Nonnull FulltextSurfaceFormLeafPagePart page) {
		return page.getStreamId();
	}

	@Override
	protected int pageSequence(@Nonnull FulltextSurfaceFormLeafPagePart page) {
		return page.getPageSequence();
	}

	@Override
	protected void writePayload(@Nonnull Kryo kryo, @Nonnull Output output,
		@Nonnull FulltextSurfaceFormLeafPagePart part) {
		final SurfaceFormPage page = part.getPage();
		output.writeVarInt(page.keys().length, true);
		for (int i = 0; i < page.keys().length; i++) {
			output.writeString(page.keys()[i]);
			output.writeVarLong(page.referenceCounts()[i], true);
		}
	}

	@Nonnull
	@Override
	protected FulltextSurfaceFormLeafPagePart readPayload(@Nonnull Kryo kryo, @Nonnull Input input,
		int streamId, int pageSequence) {
		final int count = input.readVarInt(true);
		final String[] keys = new String[count];
		final long[] references = new long[count];
		for (int i = 0; i < count; i++) {
			keys[i] = input.readString();
			references[i] = input.readVarLong(true);
		}
		return new FulltextSurfaceFormLeafPagePart(streamId, new SurfaceFormPage(pageSequence, keys, references));
	}
}
