/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.store.spi.model.storageParts.index;


import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Represents a key used to uniquely identify a specific reference by its name.
 * This class is a record that ensures immutability, is serializable, and provides
 * functionality to compare two keys based on their reference names.
 *
 * Implements:
 * - {@link Serializable}: Ensures that instances of this class can be serialized.
 * - {@link Comparable}: Allows comparison between two instances of {@code ReferenceNameKey}
 * based on their {@code referenceName}.
 *
 * The comparison logic relies on lexicographical order of the {@code referenceName}.
 *
 * @param referenceName the name of the reference used as the primary key for identification
 */
public record ReferenceNameKey(@Nonnull String referenceName) implements Serializable, Comparable<ReferenceNameKey> {

	@Override
	public int compareTo(ReferenceNameKey o) {
		return this.referenceName.compareTo(o.referenceName);
	}

}
