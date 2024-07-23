/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.store.spi.model.storageParts;

import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * This class marks removed {@link StoragePart} in the transactional memory.
 */
public class RemovedStoragePart implements StoragePart {
	public static final RemovedStoragePart INSTANCE = new RemovedStoragePart();
	@Serial private static final long serialVersionUID = 2485318464734970542L;

	@Override
	public Long getStoragePartPK() {
		throw new UnsupportedOperationException();
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		throw new UnsupportedOperationException();
	}
}
