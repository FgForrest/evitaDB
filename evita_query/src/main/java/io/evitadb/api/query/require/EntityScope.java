/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.query.require;


import io.evitadb.api.query.RequireConstraint;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.EnumSet;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class EntityScope extends AbstractRequireConstraintLeaf {
	@Serial private static final long serialVersionUID = -7172389493449298316L;
	public static final String CONSTRAINT_NAME = "scope";
	private final EnumSet<Scope> theScope;

	private EntityScope(Serializable[] arguments) {
		super(arguments);
		this.theScope = getScope();
	}

	public EntityScope(@Nonnull Scope... scope) {
		super(CONSTRAINT_NAME, scope);
		this.theScope = getScope();
	}

	/**
	 * Returns requested scopes.
	 */
	@Nonnull
	public EnumSet<Scope> getScope() {
		if (this.theScope == null) {
			final EnumSet<Scope> result = EnumSet.noneOf(Scope.class);
			for (Serializable argument : getArguments()) {
				if (argument instanceof Scope scope) {
					result.add(scope);
				}
			}
			return result;
		} else {
			return this.theScope;
		}
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length >= 1;
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new EntityScope(newArguments);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		EntityScope that = (EntityScope) o;
		return getScope().equals(that.getScope());
	}

	@Override
	public int hashCode() {
		return getScope().hashCode();
	}
}