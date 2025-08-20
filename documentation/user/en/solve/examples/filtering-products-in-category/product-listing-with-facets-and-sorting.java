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
					entityLocaleEquals(Locale.forLanguageTag("en")),
					hierarchyWithin(
						"categories",
						attributeEquals("url", "/en/smartwatches")
					),
					attributeEquals("status", "ACTIVE"),
					or(
						attributeInRangeNow("validity"),
						attributeIs("validity", NULL)
					),
					referenceHaving(
						"stocks",
						attributeGreaterThan("quantityOnStock", 0)
					),
					priceInCurrency(Currency.getInstance("EUR")),
					priceInPriceLists("basic"),
					priceValidInNow(),
					userFilter(
						facetHaving(
							"brand",
							entityPrimaryKeyInSet(66465)
						),
						priceBetween(new BigDecimal("50"), new BigDecimal("400"))
					)
				),
				orderBy(
					attributeNatural("order", ASC)
				),
				require(
					entityFetch(
						attributeContent("name"),
						referenceContentWithAttributes(
							"stocks",
							attributeContent("quantityOnStock")
						),
						priceContentRespectingFilter("reference")
					),
					facetSummaryOfReference(
						"brand",
						IMPACT,
						orderBy(
							attributeNatural("name", ASC)
						),
						entityFetch(
							attributeContent("name")
						)
					),
					facetSummaryOfReference(
						"parameterValues",
						IMPACT,
						filterGroupBy(
							attributeEquals("isVisibleInFilter", true)
						),
						orderBy(
							attributeNatural("order", ASC)
						),
						orderGroupBy(
							attributeNatural("order", ASC)
						),
						entityFetch(
							attributeContent("name")
						),
						entityGroupFetch(
							attributeContent("name")
						)
					),
					priceHistogram(10, STANDARD),
					page(1, 16)
				)
			)
		);
	}
);
