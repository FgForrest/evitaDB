/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.api.query.order;

import io.evitadb.api.query.EntityConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inFilter",
	shortDescription = "The constraint sorts returned entities by ordering of the values specified `entityPrimaryKeysInSet` in filter.",
	supportedIn = { ConstraintDomain.ENTITY }
)
public class EntityPrimaryKeyInFilter extends AbstractOrderConstraintLeaf implements EntityConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = 118454118196750296L;

	private EntityPrimaryKeyInFilter(Serializable... arguments) {
		super(arguments);
	}

	@Creator(implicitClassifier = "primaryKey")
	public EntityPrimaryKeyInFilter() {
		super();
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			ArrayUtils.isEmpty(newArguments),
			"Ordering constraint EntityPrimaryInKey doesn't accept arguments."
		);
		return new EntityPrimaryKeyInFilter();
	}
}
