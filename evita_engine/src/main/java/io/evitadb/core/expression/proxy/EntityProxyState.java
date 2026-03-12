/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.expression.proxy;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AssociatedDataStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Immutable state record holding references to raw storage parts needed by EntityContract proxy partials during
 * expression evaluation.
 *
 * Only the fields required by the selected partials are non-null. For example, if the expression only accesses
 * `$entity.attributes['code']`, only `schema` and attribute fields will be populated.
 *
 * @param schema                  entity schema - always available
 * @param bodyPart                entity body - needed by PrimaryKeyPartial, ParentPartial,
 *                                VersionAndDroppablePartial
 * @param globalAttributesPart    global (non-localized) attributes storage part - needed by EntityAttributePartial
 * @param localeAttributesParts   locale-specific attribute parts keyed by locale for O(1) lookup - needed by
 *                                EntityAttributePartial. The key set directly serves as the attribute locales set.
 * @param associatedDataParts     associated data storage parts keyed by {@link AssociatedDataKey} for O(1) lookup -
 *                                needed by AssociatedDataPartial
 * @param referencesByName        references pre-indexed by reference name for O(1) lookup - needed by
 *                                ReferencesPartial. Built from {@link ReferencesStoragePart} via
 *                                {@link #indexReferences(ReferencesStoragePart)}.
 */
public record EntityProxyState(
	@Nonnull EntitySchemaContract schema,
	@Nullable EntityBodyStoragePart bodyPart,
	@Nullable AttributesStoragePart globalAttributesPart,
	@Nullable Map<Locale, AttributesStoragePart> localeAttributesParts,
	@Nullable Map<AssociatedDataKey, AssociatedDataStoragePart> associatedDataParts,
	@Nullable Map<String, List<ReferenceContract>> referencesByName
) implements Serializable {

	@Serial private static final long serialVersionUID = -6951184351029566L;

	/**
	 * Returns the entity body storage part or throws {@link ExpressionEvaluationException} if it is not available.
	 * This method should be used by partials that require the body part to be present (e.g., primary key, version,
	 * scope, locale partials).
	 *
	 * @return the non-null entity body storage part
	 * @throws ExpressionEvaluationException if bodyPart is null
	 */
	@Nonnull
	public EntityBodyStoragePart bodyPartOrThrowException() {
		if (this.bodyPart == null) {
			throw new ExpressionEvaluationException(
				"Entity body storage part is not available in the expression proxy state for entity type `" +
					this.schema.getName() + "`. This indicates a missing storage part recipe configuration.",
				"Entity body data is not available for expression evaluation."
			);
		}
		return this.bodyPart;
	}

	/**
	 * Returns the references-by-name map or throws {@link ExpressionEvaluationException} if it is not available.
	 * This method should be used by partials that require references to be present.
	 *
	 * @return the non-null references-by-name map
	 * @throws ExpressionEvaluationException if referencesByName is null
	 */
	@Nonnull
	public Map<String, List<ReferenceContract>> referencesByNameOrThrowException() {
		if (this.referencesByName == null) {
			throw new ExpressionEvaluationException(
				"References data is not available in the expression proxy state for entity type `" +
					this.schema.getName() + "`. This indicates a missing storage part recipe configuration.",
				"References data is not available for expression evaluation."
			);
		}
		return this.referencesByName;
	}

	/**
	 * Builds an unmodifiable map of references indexed by reference name from the given storage part. Each map value
	 * is an unmodifiable list of references sharing that name. Returns `null` if the storage part is `null`.
	 *
	 * @param referencesPart the raw references storage part (nullable)
	 * @return pre-indexed map for O(1) name lookup, or `null` if input is `null`
	 */
	@Nullable
	public static Map<String, List<ReferenceContract>> indexReferences(
		@Nullable ReferencesStoragePart referencesPart
	) {
		if (referencesPart == null) {
			return null;
		}
		final Reference[] references = referencesPart.getReferences();
		if (references.length == 0) {
			return Collections.emptyMap();
		}
		final Map<String, List<ReferenceContract>> map = createHashMap(references.length);
		for (final Reference ref : references) {
			if (ref != null && ref.exists()) {
				map.computeIfAbsent(ref.getReferenceName(), k -> new ArrayList<>(4))
					.add(ref);
			}
		}
		// wrap lists to be unmodifiable
		for (final Map.Entry<String, List<ReferenceContract>> entry : map.entrySet()) {
			entry.setValue(Collections.unmodifiableList(entry.getValue()));
		}
		return Collections.unmodifiableMap(map);
	}
}
