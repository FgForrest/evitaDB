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

evita.updateCatalog(
	"evita",
	session -> {
		session.createNewEntity("Product")
			.setAttribute(
				"name", Locale.ENGLISH,
				"ASUS Vivobook 16 X1605EA-MB044W Indie Black"
			)
			.setAttribute(
				"name", Locale.GERMAN,
				"ASUS Vivobook 16 X1605EA-MB044W Indie Schwarz"
			)
			.setAttribute("catalogCode", "X1605EA-MB044W")
			.setAttribute("stockQuantity", 1)
			.setAssociatedData(
				"gallery",
				new String[] {
					"https://cdn.alza.cz/ImgW.ashx?fd=f4&cd=NA579p8e0&i=1.jpg",
					"https://cdn.alza.cz/ImgW.ashx?fd=FotoAddOrig&cd=NA579p8e0-04&i=1.jpg"
				}
			)
			.setPrice(
				1, "basic", Currency.getInstance("EUR"),
				new BigDecimal("345.9"), new BigDecimal("22"), new BigDecimal(422),
				true
			)
			.setPrice(
				2, "basic", Currency.getInstance("USD"),
				new BigDecimal("365.57"), new BigDecimal("22"), new BigDecimal(446),
				true
			)
			.setReference("brand", "Brand", Cardinality.ZERO_OR_ONE, 3)
			.setReference("categories", "Category", Cardinality.ZERO_OR_MORE, 3)
			.upsertVia(session);
	}
);
