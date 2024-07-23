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

package io.evitadb.api.query.require;

import io.evitadb.api.query.RequireConstraint;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.EnumSet;

/**
 * This `debug` require is targeted for internal purposes only and is not exposed in public evitaDB API.
 *
 * @see DebugMode for more information
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class Debug extends AbstractRequireConstraintLeaf {
	@Serial private static final long serialVersionUID = 5631043212500743575L;

	private Debug(Serializable[] arguments) {
		super(arguments);
	}

	public Debug(@Nonnull DebugMode... debugMode) {
		super(debugMode);
	}

	/**
	 * Returns requested debug modes.
	 */
	@Nonnull
	public EnumSet<DebugMode> getDebugMode() {
		final EnumSet<DebugMode> result = EnumSet.noneOf(DebugMode.class);
		for (Serializable argument : getArguments()) {
			if (argument instanceof DebugMode debugMode) {
				result.add(debugMode);
			}
		}
		return result;
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length >= 1;
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new Debug(newArguments);
	}

}
