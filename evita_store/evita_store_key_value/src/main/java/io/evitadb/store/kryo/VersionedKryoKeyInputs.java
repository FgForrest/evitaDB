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

package io.evitadb.store.kryo;

import io.evitadb.store.offsetIndex.OffsetIndex.FileOffsetIndexKryoPool;
import io.evitadb.store.service.KeyCompressor;

import javax.annotation.Nonnull;

/**
 * Class contains all key instances that are necessary for creating {@link VersionedKryo} object.
 *
 * @param keyCompressor Key compressor holds index ids to keys used in serialized objects.
 *                      See {@link KeyCompressor} documentation for more informations.
 * @param version       Version holds information used in {@link VersionedKryo} instance and this version
 *                      server to allow discarding obsolete Kryo instances in {@link FileOffsetIndexKryoPool#expireAllPreviouslyCreated()}
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public record VersionedKryoKeyInputs(@Nonnull KeyCompressor keyCompressor, long version) {
}
