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

final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Category"),
				filterBy(
					hierarchyWithinRootSelf(
						having(
							attributeEquals("status", "ACTIVE")
						),
						anyHaving(
							and(
								referenceHaving(
									"tags",
									entityHaving(
										attributeEquals("code", "HP")
									)
								),
								referenceHaving(
									"products",
									entityHaving(
										attributeEquals("status", "ACTIVE")
									)
								)
							)
						)
					)
				),
				require(
					page(1, 20),
					entityFetch(
						attributeContent("code")
					)
				)
			)
		);
	}
);