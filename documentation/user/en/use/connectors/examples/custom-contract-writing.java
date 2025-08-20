evita.updateCatalog(
	"evita",
	session -> {

		// create new product using the editor directly
		session.createNewEntity(
			ProductEditor.class, 100
		)
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

		// fill the data
		.setCode("JP328a01a")
		.setName("Creative OUTLIER FREE PRO", Locale.ENGLISH)
		.setEAN("51EF1081AA000")
		.setReferencedFiles(new Product.ReferencedFiles(5, 7))
		.addOrUpdateMarketingBrand(
			1100,
			whichIs -> whichIs
				.setMarket("EU")
				.setBrandGroup(42)
		)
		// store the product
		.upsertVia(session);

		// get existing product
		final ProductEditor storedProduct = session.getEntity(
			ProductEditor.class, 100, entityFetchAllContent()
		).orElseThrow();

		// update the data
		storedProduct
			.setName("Creative OUTLIER FREE PRO", Locale.GERMAN)
			.addOrUpdateLicensingBrand(
				1740,
				whichIs -> whichIs
					.setMarket("Asia")
					.setBrandGroup(31)
			)
			// and store the modified product back
			.upsertVia(session);
	}
);
