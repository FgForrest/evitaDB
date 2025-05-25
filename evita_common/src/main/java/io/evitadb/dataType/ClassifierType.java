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

package io.evitadb.dataType;

import lombok.RequiredArgsConstructor;

/**
 * The classifier type for distinguishing reserved keywords.
 */
@RequiredArgsConstructor
public enum ClassifierType {

	/**
	 * Identification of the server instance.
	 */
	SERVER_NAME("Server name"),
	/**
	 * Identification of the catalog (database) instance.
	 */
	CATALOG("Catalog"),
	/**
	 * Identification of the entity type (collection) instance.
	 */
	ENTITY("Entity"),
	/**
	 * Identification of the attribute type (field) instance.
	 */
	ATTRIBUTE("Attribute"),
	/**
	 * Identification of the association data (rich content) instance.
	 */
	ASSOCIATED_DATA("Associated data"),
	/**
	 * Identification of the reference data (foreign key) instance.
	 */
	REFERENCE("Reference"),
	/**
	 * Identification of the reference attribute data (foreign key) instance.
	 */
	REFERENCE_ATTRIBUTE("Reference attribute");

	private final String humanReadableName;

	public String humanReadableName() {
		return this.humanReadableName;
	}

}
