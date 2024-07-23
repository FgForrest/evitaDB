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

package io.evitadb.core.cache.payload;

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.core.cache.model.CachedRecord;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;


/**
 * DTO class allowing to trap the cached {@link Entity} along with set of predicates that describe what was fetched
 * for the entity so far. All this information serve to quickly re-instantiate {@link SealedEntity} object. We cannot
 * keep {@link SealedEntity} objects as a payload of the {@link CachedRecord} because it may have links to other
 * {@link SealedEntity} objects in the {@link ReferenceDecorator#getReferencedEntity()}
 * or {@link ReferenceDecorator#getGroupEntity()} and this would lead to a large graph of entities trapped in the cache.
 *
 * @param localePredicate         predicate filters out non-fetched locales
 * @param hierarchyPredicate      predicate filters out parent that was not fetched in query
 * @param attributePredicate      predicate filters out attributes that were not fetched in query
 * @param associatedDataPredicate predicate filters out associated data that were not fetched in query
 * @param referencePredicate      predicate filters out references that were not fetched in query
 * @param pricePredicate          predicate filters out prices that were not fetched in query
 */
public record EntityPayload(
	@Nonnull Entity entity,
	@Nonnull LocaleSerializablePredicate localePredicate,
	@Nonnull HierarchySerializablePredicate hierarchyPredicate,
	@Nonnull AttributeValueSerializablePredicate attributePredicate,
	@Nonnull AssociatedDataValueSerializablePredicate associatedDataPredicate,
	@Nonnull ReferenceContractSerializablePredicate referencePredicate,
	@Nonnull PriceContractSerializablePredicate pricePredicate
) {

	public EntityPayload {
		Assert.isPremiseValid(localePredicate.getUnderlyingPredicate() == null, "SanityCheck");
		Assert.isPremiseValid(hierarchyPredicate.getUnderlyingPredicate() == null, "SanityCheck");
		Assert.isPremiseValid(attributePredicate.getUnderlyingPredicate() == null, "SanityCheck");
		Assert.isPremiseValid(associatedDataPredicate.getUnderlyingPredicate() == null, "SanityCheck");
		Assert.isPremiseValid(referencePredicate.getUnderlyingPredicate() == null, "SanityCheck");
		Assert.isPremiseValid(pricePredicate.getUnderlyingPredicate() == null, "SanityCheck");
	}
}
