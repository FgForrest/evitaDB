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

package io.evitadb.index.mutation.storagePart;

import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;

/**
 * The storage parts of one entity that a root entity mutation has read without mutating the entity - kept, so that
 * every later read of the same part within the root mutation is answered without fetching and decoding it again (see
 * {@link InFlightEntityAccessorLookup#getForeignEntityStorageParts(String, int)}).
 *
 * Keeping them is valid because an entity the root mutation does not mutate cannot change while the root mutation
 * runs: no executor writes its storage parts into the data store buffer before the root mutation commits them all, and
 * the parts written then are those of the entities it mutates. Once the entity starts being mutated, its own executor
 * answers every read of it and the kept parts are dropped.
 *
 * Only the parts the write path reads of other entities repeatedly are kept - the body, the attributes and the
 * references. Each slot is `null` until the part is read first; a part the storage does not hold is kept as the empty
 * part the reader substitutes for it. Like the executors reading through it the holder is not thread-safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class ForeignEntityStorageParts {
	/**
	 * The body of the entity, `null` until it is read.
	 */
	@Nullable private EntityBodyStoragePart body;
	/**
	 * The global attributes of the entity, `null` until they are read.
	 */
	@Nullable private AttributesStoragePart globalAttributes;
	/**
	 * The localized attributes of the entity by their locale; allocated by the first read of a locale.
	 */
	@Nullable private Map<Locale, AttributesStoragePart> localizedAttributes;
	/**
	 * The references of the entity, `null` until they are read.
	 */
	@Nullable private ReferencesStoragePart references;

	/**
	 * Returns the body of the entity if it has been read already.
	 *
	 * @return the body, or `null` when it has not been read yet
	 */
	@Nullable
	EntityBodyStoragePart getBody() {
		return this.body;
	}

	/**
	 * Keeps the body of the entity just read.
	 *
	 * @param body the body
	 */
	void setBody(@Nonnull EntityBodyStoragePart body) {
		this.body = body;
	}

	/**
	 * Returns the global attributes of the entity if they have been read already.
	 *
	 * @return the global attributes, or `null` when they have not been read yet
	 */
	@Nullable
	AttributesStoragePart getGlobalAttributes() {
		return this.globalAttributes;
	}

	/**
	 * Keeps the global attributes of the entity just read.
	 *
	 * @param globalAttributes the global attributes
	 */
	void setGlobalAttributes(@Nonnull AttributesStoragePart globalAttributes) {
		this.globalAttributes = globalAttributes;
	}

	/**
	 * Returns the attributes of the entity localized to the locale if they have been read already.
	 *
	 * @param locale the locale
	 * @return the localized attributes, or `null` when they have not been read yet
	 */
	@Nullable
	AttributesStoragePart getLocalizedAttributes(@Nonnull Locale locale) {
		return this.localizedAttributes == null ? null : this.localizedAttributes.get(locale);
	}

	/**
	 * Keeps the attributes of the entity localized to the locale just read.
	 *
	 * @param locale              the locale
	 * @param localizedAttributes the localized attributes
	 */
	void setLocalizedAttributes(@Nonnull Locale locale, @Nonnull AttributesStoragePart localizedAttributes) {
		if (this.localizedAttributes == null) {
			this.localizedAttributes = CollectionUtils.createHashMap(4);
		}
		this.localizedAttributes.put(locale, localizedAttributes);
	}

	/**
	 * Returns the references of the entity if they have been read already.
	 *
	 * @return the references, or `null` when they have not been read yet
	 */
	@Nullable
	ReferencesStoragePart getReferences() {
		return this.references;
	}

	/**
	 * Keeps the references of the entity just read.
	 *
	 * @param references the references
	 */
	void setReferences(@Nonnull ReferencesStoragePart references) {
		this.references = references;
	}

}
