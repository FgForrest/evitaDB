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

evita.defineCatalog("evita")
	.withEntitySchema(
		"Brand",
		entitySchema -> entitySchema
			.withDescription("""
				Brand is entity that represents manufacturer or
				supplier of the product.""")
			.withoutGeneratedPrimaryKey()
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
			.withAttribute(
				"name", String.class,
				whichIs -> whichIs
					.withDescription("The apt brand name.")
					.filterable()
					.sortable()
			)
	)
	.withEntitySchema(
		"Category",
		entitySchema -> entitySchema
			.withDescription("""
				Category is entity that forms a hierarchical tree and
				categorizes items on the e-commerce site into a better
				accessible form for the customer.""")
			.withoutGeneratedPrimaryKey()
			.withHierarchy()
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
			.withAttribute(
				"name", String.class,
				whichIs -> whichIs
					.withDescription("The apt category name.")
					.localized()
					.filterable()
					.sortable()
			)
	)
	.withEntitySchema(
		"Product",
		entitySchema -> entitySchema
			.withDescription("""
				Product represents an article that can be displayed and sold on e-shop.
				Product can be organized in categories or groups.
				Product can relate to groups or brands.
				Product have prices.""")
			.withGeneratedPrimaryKey()
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
			.withPriceInCurrency(
				Currency.getInstance("USD"), Currency.getInstance("EUR")
			)
			.withAttribute(
				"name", String.class,
				whichIs -> whichIs
					.withDescription("The apt product name.")
					.localized()
					.filterable()
					.sortable()
					.nullable()
			)
			.withAttribute(
				"catalogCode", String.class,
				whichIs -> whichIs
					.withDescription("Product designation in your sales catalogue.")
					.filterable()
					.sortable()
					.nullable()
			)
			.withAttribute(
				"stockQuantity", Integer.class,
				whichIs -> whichIs
					.withDescription("Number of pieces in stock.")
					.filterable()
					.sortable()
					.withDefaultValue(0)
			)
			.withAssociatedData(
				"gallery", String[].class,
				whichIs -> whichIs
					.nullable()
					.withDescription("List of links to images in the product gallery.")
			)
			.withReferenceToEntity(
				"brand", "Brand", Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.withDescription("Reference to the brand or manufacturer of the product.")
					.indexed()
					.faceted()
			)
			.withReferenceToEntity(
				"categories", "Category", Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.withDescription("Reference to one or more categories the product is listed in.")
					.indexed()
			)
	)
	.updateAndFetchViaNewSession(evita);
