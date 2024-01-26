/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.compressor;

import io.evitadb.store.exception.CompressionKeyUnknownException;
import io.evitadb.store.service.KeyCompressor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class AggregatedKeyCompressor implements KeyCompressor {
	@Nonnull private final KeyCompressor[] compressors;

	public AggregatedKeyCompressor(@Nonnull KeyCompressor... compressors) {
		this.compressors = compressors;
	}

	@Nonnull
	@Override
	public Map<Integer, Object> getKeys() {
		throw new UnsupportedOperationException("Can be implemented only with extra performance costs!");
	}

	@Override
	public <T extends Comparable<T>> int getId(@Nonnull T key) throws CompressionKeyUnknownException {
		// Iterate over all compressors
		for (KeyCompressor compressor : compressors) {
			// Try to get the ID of the key from the current compressor
			Integer id = compressor.getIdIfExists(key);

			// If the ID is not null, return it
			if (id != null) {
				return id;
			}
		}

		// If no compressor has the key, throw an exception
		throw new CompressionKeyUnknownException("Key not found: " + key);
	}

	@Nullable
	@Override
	public <T extends Comparable<T>> Integer getIdIfExists(@Nonnull T key) {
		// Iterate over all compressors
		for (KeyCompressor compressor : compressors) {
			// Try to get the ID of the key from the current compressor
			Integer id = compressor.getIdIfExists(key);

			// If the ID is not null, return it
			if (id != null) {
				return id;
			}
		}

		// If no compressor has the key, return null
		return null;
	}

	@Nonnull
	@Override
	public <T extends Comparable<T>> T getKeyForId(int id) {
		// Iterate over all compressors
		for (KeyCompressor compressor : compressors) {
			// Try to get the key for the ID from the current compressor
			T key = compressor.getKeyForIdIfExists(id);

			// If the key is not null, return it
			if (key != null) {
				return key;
			}
		}

		// If no compressor has the ID, throw an exception
		throw new IllegalArgumentException("ID not found: " + id);
	}

	@Nullable
	@Override
	public <T extends Comparable<T>> T getKeyForIdIfExists(int id) {
		// Iterate over all compressors
		for (KeyCompressor compressor : compressors) {
			// Try to get the key for the ID from the current compressor
			T key = compressor.getKeyForIdIfExists(id);

			// If the key is not null, return it
			if (key != null) {
				return key;
			}
		}

		return null;
	}
}
