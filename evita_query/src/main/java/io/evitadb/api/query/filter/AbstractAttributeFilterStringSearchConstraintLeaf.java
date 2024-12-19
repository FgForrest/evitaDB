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

package io.evitadb.api.query.filter;


import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The AbstractAttributeFilterStringSearchConstraintLeaf class is an abstract base class that represents a specific type
 * of attribute filter constraint focusing on string search operations. It inherits from the
 * AbstractAttributeFilterConstraintLeaf class and provides a method to retrieve the string that needs to be searched
 * within attribute values.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public abstract class AbstractAttributeFilterStringSearchConstraintLeaf
	extends AbstractAttributeFilterConstraintLeaf {
	@Serial private static final long serialVersionUID = 219317868969717309L;

	protected AbstractAttributeFilterStringSearchConstraintLeaf(Serializable... arguments) {
		super(arguments);
	}

	/**
	 * Returns part of attribute value that needs to be looked up for.
	 * @return part of attribute value that needs to be looked up for
	 */
	@Nonnull
	public abstract String getTextToSearch();

}
