evita.updateCatalog(
	"evita",
	session -> {

		/* first create stubs of the entity schemas that the product will reference */
		session.defineEntitySchema("Brand");
		session.defineEntitySchema("Category");

		session.defineEntitySchema("Product")
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

			/* all is strictly verified but associated data
			   and references can be added on the fly */
			.verifySchemaButAllow(
				EvolutionMode.ADDING_ASSOCIATED_DATA,
				EvolutionMode.ADDING_REFERENCES
			)
			/* products are not organized in the tree */
			.withoutHierarchy()
			/* prices are referencing another entity stored in Evita */
			.withPrice()
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, new Locale("cs", "CZ"))
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute(
				"code", String.class,
				whichIs -> whichIs.unique()
			)
			.withAttribute(
				"url", String.class,
				whichIs -> whichIs.unique().localized()
			)
			.withAttribute(
				"oldEntityUrls", String[].class,
				whichIs -> whichIs.filterable().localized()
			)
			.withAttribute(
				"name", String.class,
				whichIs -> whichIs.filterable().sortable()
			)
			.withAttribute(
				"ean", String.class,
				whichIs -> whichIs.filterable()
			)
			.withAttribute(
				"priority", Long.class,
				whichIs -> whichIs.sortable()
			)
			.withAttribute(
				"validity", DateTimeRange.class,
				whichIs -> whichIs.filterable()
			)
			.withAttribute(
				"quantity", BigDecimal.class,
				whichIs -> whichIs.filterable().indexDecimalPlaces(2)
			)
			.withAttribute(
				"alias", Boolean.class,
				whichIs -> whichIs.filterable()
			)
			/* here we define set of associated data,
			   that can be stored along with entity */
			.withAssociatedData("referencedFiles", ReferencedFileSet.class)
			.withAssociatedData(
				"labels", Labels.class,
				whichIs -> whichIs.localized()
			)
			/* here we define references that relate to
			   another entities stored in Evita */
			.withReferenceToEntity(
				"categories",
				"Category",
				Cardinality.ZERO_OR_MORE,
				whichIs ->
					/* we can specify special attributes on relation */
					whichIs.indexed().withAttribute(
						"categoryPriority", Long.class,
						thatIs -> thatIs.sortable()
					)
			)
			/* for faceted references we can compute "counts" */
			.withReferenceToEntity(
				"brand",
				"Brand",
				Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.faceted())
			/* references may be also represented be
			   entities unknown to Evita */
			.withReferenceTo(
				"stock",
				"stock",
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.faceted()
			)
			/* finally apply schema changes */
			.updateVia(session);
	}
);
