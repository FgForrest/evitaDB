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

package io.evitadb.spi.store.catalog.persistence.storageParts;

import javax.annotation.Nonnull;

/**
 * A flush-time instruction that REMOVES a storage part whose primary key can only be resolved store-side. It differs
 * from a plain pre-resolved removal in that its target primary key depends on the writable
 * {@link KeyCompressor} (e.g. a granular FilterIndex leaf page whose `streamId` is a compressor dictionary id), which
 * the engine emitting the removal cannot reach. The store-side flush therefore resolves the primary key by calling
 * {@link #computeUniquePartIdAndSet(KeyCompressor)} and then removes the part of {@link #removedContainerType()}.
 *
 * Such a part is never written: it carries no payload and has no Kryo serializer — it is consumed by the flush drain and
 * discarded.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface DeferredRemovalStoragePart extends StoragePart {

	/**
	 * The container type of the storage part to remove (resolved with the primary key produced by
	 * {@link #computeUniquePartIdAndSet(KeyCompressor)}).
	 *
	 * @return the removed part's container type
	 */
	@Nonnull
	Class<? extends StoragePart> removedContainerType();

}
