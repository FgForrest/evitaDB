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

package io.evitadb.index.component.loader;

import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Function;

/**
 * Immutable per-call bundle of everything a {@link ComponentLoader} needs to reload one sub-index
 * from persistent storage. A fresh `LoadContext` is built once by the dispatcher in
 * `DefaultEntityCollectionPersistenceService` per `readEntityIndex` invocation and shared across
 * every loader that participates in the matching {@link IndexReloadPlan}.
 *
 * The context is intentionally a record — the reload path is **not** on the hot query path
 * (catalog boot / restart only), so allocation discipline can be traded for clarity here.
 *
 * @param catalogVersion         the catalog version to read storage parts from
 * @param entityIndexId          the primary key of the entity index being reloaded
 * @param entitySchema           the resolved entity schema for the owning collection
 * @param entityIndexKey         the key carried by the previously-persisted
 *                               {@link EntityIndexStoragePart}
 * @param entityIndexStoragePart the previously-persisted manifest; loaders consult it for the
 *                               set of sub-index keys they should fetch
 * @param storagePartService     the storage-part persistence service used to fetch raw parts
 * @param attributeTypeFetcher   resolver for legacy filter-index storage parts whose
 *                               `attributeType` field is null — see `TOBEDONE #538`. Resolves
 *                               the runtime `Class` for an attribute by walking the entity /
 *                               reference schema and is captured by the dispatcher once per call
 * @param referenceKey           the discriminator for `REFERENCED_ENTITY` /
 *                               `REFERENCED_GROUP_ENTITY` indexes; `null` for `GLOBAL` and
 *                               `REFERENCED_*_TYPE` indexes
 */
public record LoadContext(
	long catalogVersion,
	int entityIndexId,
	@Nonnull EntitySchema entitySchema,
	@Nonnull EntityIndexKey entityIndexKey,
	@Nonnull EntityIndexStoragePart entityIndexStoragePart,
	@Nonnull StoragePartPersistenceService<?> storagePartService,
	@Nonnull @SuppressWarnings("rawtypes") Function<AttributeIndexKey, Class> attributeTypeFetcher,
	@Nullable RepresentativeReferenceKey referenceKey
) {
}
