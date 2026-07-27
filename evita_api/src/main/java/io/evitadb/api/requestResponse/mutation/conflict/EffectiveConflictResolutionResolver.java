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

package io.evitadb.api.requestResponse.mutation.conflict;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Resolves the effective entity-level {@link ConflictResolution} for a given entity type by walking the
 * schema precedence chain:
 *
 * entity-schema resolution → catalog-schema resolution → engine default.
 *
 * The walk is a **whole-record override** with no field merging: the most specific non-null
 * {@link ConflictResolution} wins entirely. Per-item {@link ConflictResolutionOverride} refinements are
 * applied later, at key-emit time, by {@link ConflictGenerationContext} — this resolver only produces the
 * entity-level baseline the item overrides refine.
 *
 * Because the result is derived purely from the schema (never from the session), two concurrent
 * transactions touching the same entity type always resolve to the same {@link ConflictResolution}, which
 * is what makes the incoming (write-time) and historical (recompute) key-generation sites agree.
 *
 * The resolver is stateless; any caching of the per-type result is the caller's responsibility (the
 * conflict generation context caches it for the lifetime of a single mutation pass).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public final class EffectiveConflictResolutionResolver {

	/**
	 * The resolver holds no state; instantiation is forbidden.
	 */
	private EffectiveConflictResolutionResolver() {
		throw new UnsupportedOperationException("EffectiveConflictResolutionResolver is a static utility class.");
	}

	/**
	 * Resolves the effective entity-level {@link ConflictResolution} for the given entity type by returning
	 * the most specific non-null resolution in the order entity schema → catalog schema → engine default.
	 *
	 * @param catalogSchema the catalog schema whose (nullable) resolution overrides the engine default,
	 *                      must not be null
	 * @param entitySchema  the entity schema whose (nullable) resolution overrides the catalog resolution;
	 *                      may be null when the entity type has no schema yet (e.g. during creation with
	 *                      automatic schema evolution), in which case the catalog/engine levels apply
	 * @param engineDefault the engine-wide default resolution used when neither schema declares one, must
	 *                      not be null
	 * @return the resolved entity-level {@link ConflictResolution}, never null
	 */
	@Nonnull
	public static ConflictResolution resolve(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema,
		@Nonnull ConflictResolution engineDefault
	) {
		// most specific level first: the entity schema's own resolution wins entirely when present
		if (entitySchema != null) {
			final Optional<ConflictResolution> entityLevel = entitySchema.getConflictResolution();
			if (entityLevel.isPresent()) {
				return entityLevel.get();
			}
		}
		// then the catalog schema's resolution, finally the engine default
		return catalogSchema.getConflictResolution().orElse(engineDefault);
	}

	/**
	 * Diagnostic counterpart of {@link #resolve} that, in addition to the effective resolution, reports the
	 * {@link ConflictResolutionLayer schema layer} the resolution was taken from. Intended for the cold
	 * conflict-reporting path only — it walks the same precedence but allocates a small result record and is
	 * therefore not used on the hot key-generation path.
	 *
	 * @param catalogSchema the catalog schema whose (nullable) resolution overrides the engine default,
	 *                      must not be null
	 * @param entitySchema  the entity schema whose (nullable) resolution overrides the catalog resolution;
	 *                      may be null when the conflicting key carries no entity type (a catalog-wide key)
	 *                      or the entity type has no schema, in which case the catalog/engine levels apply
	 * @param engineDefault the engine-wide default resolution used when neither schema declares one, must
	 *                      not be null
	 * @return the resolved resolution paired with the layer it came from, never null
	 */
	@Nonnull
	public static ResolvedConflictResolution resolveWithSource(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema,
		@Nonnull ConflictResolution engineDefault
	) {
		// most specific level first: the entity schema's own resolution wins entirely when present
		if (entitySchema != null) {
			final Optional<ConflictResolution> entityLevel = entitySchema.getConflictResolution();
			if (entityLevel.isPresent()) {
				return new ResolvedConflictResolution(entityLevel.get(), ConflictResolutionLayer.ENTITY_SCHEMA);
			}
		}
		// then the catalog schema's resolution, finally the engine default
		final Optional<ConflictResolution> catalogLevel = catalogSchema.getConflictResolution();
		return catalogLevel
			.map(cr -> new ResolvedConflictResolution(cr, ConflictResolutionLayer.CATALOG_SCHEMA))
			.orElseGet(() -> new ResolvedConflictResolution(engineDefault, ConflictResolutionLayer.ENGINE_DEFAULT));
	}

}
