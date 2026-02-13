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

package io.evitadb.api.proxy.impl;


/**
 * Discriminates between the two kinds of entities that a reference can point to.
 *
 * In evitaDB's data model, a reference (e.g., "product → category") always points to a **target** entity,
 * and may optionally point to a **group** entity that clusters several references together. For example,
 * a product's "parameterValues" reference might target individual parameter-value entities while grouping
 * them under parameter-group entities.
 *
 * This enum is used throughout the proxy infrastructure to distinguish which entity is being accessed,
 * cached, or mutated when working with references:
 *
 * - In **cache keys** ({@link AbstractEntityProxyState}) — to store separate proxy instances for target
 *   and group entities of the same reference
 * - In **getter classifiers** — to resolve whether a proxy method returns the referenced entity or its group
 * - In **setter classifiers** — to route mutations to the correct referenced entity (target vs. group)
 *
 * @see AbstractEntityProxyState
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public enum ReferencedObjectType {
	/**
	 * The primary entity that the reference points to.
	 *
	 * For example, in a "product → category" reference, the category entity is the TARGET.
	 */
	TARGET,
	/**
	 * The optional grouping entity that clusters multiple references of the same type.
	 *
	 * For example, in a "product → parameterValue" reference grouped by "parameterGroup",
	 * the parameter-group entity is the GROUP.
	 */
	GROUP
}
