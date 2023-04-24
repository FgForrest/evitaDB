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

package io.evitadb.api.query.require;

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.Value;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * TOBEDONE JNO: docs
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "distance",
	shortDescription = "The constraint limits the traversing in stop at container at specified distance (number of nodes in path).",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyDistance extends AbstractRequireConstraintLeaf implements HierarchyStopAtRequireConstraint {
	@Serial private static final long serialVersionUID = 2412732472259053834L;
	private static final String CONSTRAINT_NAME = "distance";

	private HierarchyDistance(Serializable... arguments) {
		// because this query can be used only within some other hierarchy query, it would be
		// unnecessary to duplicate the hierarchy prefix
		super(CONSTRAINT_NAME, arguments);
	}

	@Creator
	public HierarchyDistance(@Value int distance) {
		// because this query can be used only within some other hierarchy query, it would be
		// unnecessary to duplicate the hierarchy prefix
		super(CONSTRAINT_NAME, distance);
		Assert.isTrue(distance > 0, () -> new EvitaInvalidUsageException("Distance must be greater than zero."));
	}

	/**
	 * Returns distance from the source node that should be traversed.
	 */
	public int getDistance() {
		return (Integer) getArguments()[0];
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 1;
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			newArguments.length == 1 && newArguments[0] instanceof Integer,
			"HierarchyDistance container accepts only single integer argument!"
		);
		return new HierarchyDistance(newArguments);
	}

}
