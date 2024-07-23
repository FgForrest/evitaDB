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

evita.queryCatalog(
	"evita",
	session -> {
		// get single product by primary key
		final Optional<Product> product = session.getEntity(
			Product.class, 1, entityFetchAllContent()
		);

		// get single product by specific query
		final Optional<Product> optionalProduct = session.queryOne(
			query(
				filterBy(
					attributeEquals("code", "macbook-pro-13")
				),
				require(
					entityFetchAll()
				)
			),
			Product.class
		);

		// get multiple products in category
		final List<Product> products = session.queryList(
			query(
				filterBy(
					referenceHaving(
						"marketingBrand",
						entityHaving(
							filterBy(
								attributeEquals("code", "sony")
							)
						)
					)
				),
				require(
					entityFetchAll()
				)
			),
			Product.class
		);

		// or finally get page of products in category
		final EvitaResponse<Product> productResponse = session.query(
			query(
				filterBy(
					referenceHaving(
						"marketingBrand",
						entityHaving(
							filterBy(
								attributeEquals("code", "sony")
							)
						)
					)
				),
				require(
					entityFetchAll()
				)
			),
			Product.class
		);
	}
);
