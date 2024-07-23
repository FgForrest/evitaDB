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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.FilterConstraint;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Represents base query leaf accepting only filtering constraints and having first argument attribute name.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
abstract class AbstractAttributeFilterConstraintLeaf extends AbstractFilterConstraintLeaf
	implements AttributeConstraint<FilterConstraint>, IndexUsingConstraint {
	@Serial private static final long serialVersionUID = 3153809771456358624L;

	protected AbstractAttributeFilterConstraintLeaf(Serializable... arguments) {
		super(arguments);
	}

	/**
	 * Returns attribute name that needs to be examined.
	 */
	@Override
	@Nonnull
	public String getAttributeName() {
		return (String) getArguments()[0];
	}

}
