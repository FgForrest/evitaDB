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

package io.evitadb.index.mutation.local;

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Identifies a reference scheduled for a final histogram add by name, target and internal primary key. Unlike
 * `ReferenceKey.equals`, it keeps positive, negative temporary and unknown internal primary keys distinct.
 *
 * @param referenceName the reference name
 * @param primaryKey the referenced entity primary key
 * @param internalPrimaryKey the stored or temporary internal primary key
 */
record HistogramReferenceIdentity(@Nonnull String referenceName, int primaryKey, int internalPrimaryKey) {

	/**
	 * Creates the identity of the reference the key names, keeping its internal primary key exactly as it is.
	 *
	 * @param referenceKey the key of the reference
	 * @return the identity including the internal primary key
	 */
	@Nonnull
	static HistogramReferenceIdentity of(@Nonnull ReferenceKey referenceKey) {
		return new HistogramReferenceIdentity(
			referenceKey.referenceName(), referenceKey.primaryKey(), referenceKey.internalPrimaryKey()
		);
	}

	/**
	 * Finds only this exact reference instance, including a dropped one. Unknown keys are retained only for inserts
	 * that storage has not assigned an internal primary key yet; they must never resolve to an older instance.
	 *
	 * @param storagePart the current references
	 * @return the exact reference, or null if it is absent
	 */
	@Nullable
	ReferenceContract findReference(@Nonnull ReferencesStoragePart storagePart) {
		if (this.internalPrimaryKey == 0) {
			for (final ReferenceContract reference : storagePart.getReferences()) {
				final ReferenceKey key = reference.getReferenceKey();
				if (key.internalPrimaryKey() == 0 && key.primaryKey() == this.primaryKey &&
					key.referenceName().equals(this.referenceName)) {
					return reference;
				}
			}
			return null;
		}
		final ReferenceContract reference = storagePart.findReferenceIncludingDropped(
			new ReferenceKey(this.referenceName, this.primaryKey, this.internalPrimaryKey)
		);
		return reference != null && reference.getReferenceKey().internalPrimaryKey() == this.internalPrimaryKey
			? reference : null;
	}
}
