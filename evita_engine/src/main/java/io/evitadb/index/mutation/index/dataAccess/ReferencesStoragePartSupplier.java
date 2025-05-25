/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.index.mutation.index.dataAccess;


import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * ReferencesStoragePartSupplier is responsible for providing access to references held in {@link ReferencesStoragePart}
 * data structure for purpose of indexing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class ReferencesStoragePartSupplier implements ReferenceSupplier {
	private final ReferencesStoragePart referencesStoragePart;

	@Nonnull
	@Override
	public Stream<ReferenceKey> getReferenceKeys() {
		return Arrays.stream(this.referencesStoragePart.getReferences())
			.filter(Droppable::exists)
			.map(ReferenceContract::getReferenceKey);
	}

}
