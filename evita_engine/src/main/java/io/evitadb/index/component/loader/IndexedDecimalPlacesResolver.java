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

package io.evitadb.index.component.loader;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Resolves the `indexedDecimalPlaces` scale of an indexed attribute from the owning {@link EntitySchema} at index
 * load time. The scale is no longer persisted in the attribute index storage parts; instead the loaders re-derive it
 * from the schema context — the single source of truth — mirroring the schema-context resolution philosophy used by
 * the catalog schema storage part. A missing attribute (or reference) means a corrupt catalog and fails loudly.
 */
public final class IndexedDecimalPlacesResolver {

	private IndexedDecimalPlacesResolver() {
	}

	/**
	 * Resolves the `indexedDecimalPlaces` scale for the attribute identified by `referenceName` + `attributeName` from
	 * the supplied entity schema. The result is `0` for every non-`BigDecimal` attribute type.
	 *
	 * The `referenceName` is the scope of the OWNING entity index (see
	 * {@link io.evitadb.index.EntityIndexKey#referenceName()}), not the per-attribute storage key — a legacy-rehydrated
	 * `AttributeIndexKey` carries a `null` reference name, so the scope must be taken from the owning index. A
	 * reference-scoped entity index (`REFERENCED_ENTITY_TYPE` / `REFERENCED_ENTITY` / `…_GROUP_…`) holds BOTH the
	 * reference's own attributes AND copies of the source entity's entity-level attributes; the two are
	 * indistinguishable once the per-attribute key has lost its reference name. The lookup therefore tries the
	 * **reference scope first** and **falls back to the entity level** when the attribute is not a reference attribute
	 * (the entity-attribute-copy case). For an entity-level (`GLOBAL`) index `referenceName` is `null` and only the
	 * entity-level lookup is attempted.
	 *
	 * A reflected reference whose target is not wired up yet at load time cannot answer attribute queries (its inherited
	 * attributes come from the unavailable target); the reference-scope lookup is skipped for such a reference and the
	 * resolution falls back to the entity level.
	 *
	 * @param entitySchema  the collection's entity schema
	 * @param referenceName name of the reference owning this index's attributes, or `null` for an entity-level index
	 * @param attributeName name of the indexed attribute
	 * @return the schema's `indexedDecimalPlaces` for that attribute
	 * @throws GenericEvitaInternalError when the attribute is missing from both the reference and the entity schema
	 */
	public static int resolveIndexedDecimalPlaces(
		@Nonnull EntitySchema entitySchema,
		@Nullable String referenceName,
		@Nonnull String attributeName
	) {
		// reference-scoped index: prefer the reference's own attribute, then fall back to the entity-level attribute
		// (the reference index also holds copies of the source entity's entity-level attributes)
		if (referenceName != null) {
			final ReferenceSchemaContract referenceSchema = entitySchema.getReference(referenceName)
				.orElseThrow(() -> new GenericEvitaInternalError(
					"Reference `" + referenceName + "` referenced by attribute index `" + attributeName +
						"` is missing from entity schema `" + entitySchema.getName() + "`!"
				));
			// a reflected reference whose target is not yet available throws on getAttribute (its attributes are
			// inherited from the unavailable target); skip the reference-scope lookup and resolve at entity level
			final boolean referenceAttributesAvailable =
				!(referenceSchema instanceof ReflectedReferenceSchemaContract reflected)
					|| reflected.isReflectedReferenceAvailable();
			if (referenceAttributesAvailable) {
				final Optional<? extends AttributeSchemaContract> referenceAttribute =
					referenceSchema.getAttribute(attributeName);
				if (referenceAttribute.isPresent()) {
					return referenceAttribute.get().getIndexedDecimalPlaces();
				}
			}
		}
		// entity-level attribute (a GLOBAL index, or an entity-attribute copy held by a reference index)
		return entitySchema.getAttribute(attributeName)
			.orElseThrow(() -> new GenericEvitaInternalError(
				"Attribute `" + attributeName + "`" +
					(referenceName == null ? "" : " of reference `" + referenceName + "` (nor at entity level)") +
					" is missing from entity schema `" + entitySchema.getName() + "`!"
			))
			.getIndexedDecimalPlaces();
	}

}
