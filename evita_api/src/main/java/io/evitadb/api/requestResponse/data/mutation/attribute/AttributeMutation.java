/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.requestResponse.data.mutation.attribute;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.mutation.NamedLocalMutation;
import io.evitadb.api.requestResponse.data.structure.Attributes;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.conflict.AttributeConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.dataType.ContainerType;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Attribute {@link Mutation} allows to execute mutation operations on {@link Attributes} of the {@link EntityContract}
 * object. Each attribute change increments {@link AttributeValue#version()} by one, attribute removal only sets
 * tombstone flag on an attribute value and doesn't really remove it. Possible removal will be taken care of during
 * compaction process, leaving attributes in place allows to see last assigned value to the attribute and also consult
 * last version of the attribute.
 *
 * These traits should help to manage concurrent transactional process as updates to the same entity could be executed
 * safely and concurrently as long as attribute modification doesn't overlap. Some mutations may also overcome same
 * attribute concurrent modification if it's safely additive (i.e. incrementation / decrementation and so on).
 *
 * Exact mutations also allows engine implementation to safely update only those indexes that the change really affects
 * and doesn't require additional analysis.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(exclude = "decisiveTimestamp")
public abstract class AttributeMutation implements NamedLocalMutation<AttributeValue, AttributeKey> {
	@Serial private static final long serialVersionUID = 8615227519108169551L;
	@Getter private final long decisiveTimestamp;
	/**
	 * Identification of the attribute that the mutation affects.
	 */
	@Nonnull
	@Getter protected final AttributeKey attributeKey;

	protected AttributeMutation(@Nonnull AttributeKey attributeKey) {
		this.attributeKey = attributeKey;
		this.decisiveTimestamp = System.nanoTime();
	}

	public AttributeMutation(@Nonnull AttributeKey attributeKey, long decisiveTimestamp) {
		this.attributeKey = attributeKey;
		this.decisiveTimestamp = decisiveTimestamp;
	}

	@Nonnull
	@Override
	public String containerName() {
		return this.attributeKey.attributeName();
	}

	@Nonnull
	@Override
	public ContainerType containerType() {
		return ContainerType.ATTRIBUTE;
	}

	@Nonnull
	@Override
	public AttributeKey getComparableKey() {
		return this.attributeKey;
	}

	@Nonnull
	@Override
	public Stream<ConflictKey> collectConflictKeys(
		@Nonnull ConflictGenerationContext context,
		@Nonnull Set<ConflictPolicy> conflictPolicies
	) {
		return conflictPolicies.contains(ConflictPolicy.ENTITY_ATTRIBUTE) && context.getEntityPrimaryKey() != null ?
			Stream.of(
				new AttributeConflictKey(
					context.getEntityType(),
					context.getEntityPrimaryKey(),
					this.attributeKey.attributeName()
				)
			) :
			Stream.empty();
	}

}
