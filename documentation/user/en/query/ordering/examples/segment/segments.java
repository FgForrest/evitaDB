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

final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					priceInPriceLists("basic"),
					priceInCurrency(Currency.getInstance("EUR")),
					priceBetween(new BigDecimal("100"), new BigDecimal("103"))
				),
				orderBy(
					segments(
						segment(
							orderBy(
								attributeNatural("published", DESC)
							),
							limit(2)
						),
						segment(
							entityHaving(
								priceBetween(new BigDecimal("500"), new BigDecimal("10000"))
							),
							orderBy(
								attributeNatural("orderedQuantity", DESC)
							),
							limit(1)
						),
						segment(
							entityHaving(
								priceBetween(new BigDecimal("0"), new BigDecimal("500"))
							),
							orderBy(
								attributeNatural("orderedQuantity", DESC)
							),
							limit(1)
						),
						segment(
							entityHaving(
								referenceHaving(
									"stocks",
									attributeGreaterThan("quantityOnStock", 0)
								)
							),
							orderBy(
								attributeNatural("orderedQuantity", DESC)
							),
							limit(1)
						),
						segment(
							orderBy(
								attributeNatural("orderedQuantity", DESC)
							)
						)
					)
				),
				require(
					page(1, 10),
					entityFetch(
						attributeContent("code", "published", "orderedQuantity"),
						referenceContentWithAttributes(
							"stocks",
							attributeContent("quantityOnStock")
						),
						priceContentRespectingFilter()
					)
				)
			)
		);
	}
);