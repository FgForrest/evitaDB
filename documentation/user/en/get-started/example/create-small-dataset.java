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
		session
			.createNewEntity("Brand", 2)
			.setAttribute("name", "Lenovo")
			.upsertVia(session);

		session
			.createNewEntity("Brand", 3)
			.setAttribute("name", "Acer")
			.upsertVia(session);

		session
			.createNewEntity("Brand", 4)
			.setAttribute("name", "ASUS")
			.upsertVia(session);

		session
			.createNewEntity("Category", 1)
			.setAttribute("name", Locale.ENGLISH, "Electronics")
			.setAttribute("name", Locale.GERMAN, "Elektronik")
			.upsertVia(session);

		session
			.createNewEntity("Category", 2)
			.setParent(1)
			.setAttribute("name", Locale.ENGLISH, "Components")
			.setAttribute("name", Locale.GERMAN, "Komponenten")
			.upsertVia(session);

		session
			.createNewEntity("Category", 3)
			.setParent(1)
			.setAttribute("name", Locale.ENGLISH, "Portable computer")
			.setAttribute("name", Locale.GERMAN, "Tragbarer Computer")
			.upsertVia(session);

		session.createNewEntity("Product")
			.setAttribute("name", Locale.ENGLISH, "Lenovo ThinkPad UltraSlim USB DVD Burner")
			.setAttribute("name", Locale.GERMAN, "Lenovo ThinkPad UltraSlim USB-DVD-Brenner")
			.setAttribute("catalogCode", "4XA0E97775")
			.setAttribute("stockQuantity", 3)
			.setAssociatedData(
				"gallery",
				new String[] {
					"https://cdn.alza.cz/ImgW.ashx?fd=f4&cd=NT442p2i&i=1.jpg",
					"https://cdn.alza.cz/ImgW.ashx?fd=FotoAddOrig&cd=NT442p2i-02&i=1.jpg"
				}
			)
			.setPrice(
				1, "basic", Currency.getInstance("EUR"),
				new BigDecimal("63.93"), new BigDecimal("22"), new BigDecimal(78),
				true
			)
			.setPrice(
				2, "basic", Currency.getInstance("USD"),
				new BigDecimal("68.03"), new BigDecimal("22"), new BigDecimal(83),
				true
			)
			.setReference("brand", "Brand", Cardinality.ZERO_OR_ONE, 2)
			.setReference("categories", "Category", Cardinality.ZERO_OR_MORE, 2)
			.upsertVia(session);

		session.createNewEntity("Product")
			.setAttribute("name", Locale.ENGLISH, "ASUS SDRW-08U7M-U black + 2× M-Disk")
			.setAttribute("name", Locale.GERMAN, "ASUS SDRW-08U7M-U schwarz + 2× M-Disk")
			.setAttribute("catalogCode", "90DD01X0-M29000")
			.setAttribute("stockQuantity", 1)
			.setAssociatedData(
				"gallery",
				new String[] {
					"https://cdn.alza.cz/ImgW.ashx?fd=f4&cd=GM382c8d&i=1.jpg",
					"https://cdn.alza.cz/ImgW.ashx?fd=FotoAddOrig&cd=GM382c8d-02&i=1.jpg",
					"https://cdn.alza.cz/ImgW.ashx?fd=FotoAddOrig&cd=GM382c8d-03&i=1.jpg",
					"https://cdn.alza.cz/ImgW.ashx?fd=FotoAddOrig&cd=GM382c8d-04&i=1.jpg"
				}
			)
			.setPrice(
				1, "basic", Currency.getInstance("EUR"),
				new BigDecimal("27.87"), new BigDecimal("22"), new BigDecimal(34),
				true
			)
			.setPrice(
				2, "basic", Currency.getInstance("USD"),
				new BigDecimal("29.5"), new BigDecimal("22"), new BigDecimal(36),
				true
			)
			.setReference("brand", "Brand", Cardinality.ZERO_OR_ONE, 4)
			.setReference("categories", "Category", Cardinality.ZERO_OR_MORE, 2)
			.upsertVia(session);

		session.createNewEntity("Product")
			.setAttribute("name", Locale.ENGLISH, "Lenovo Legion 5 15ITH6H Phantom Blue/Shadow Black(3 years warranty)")
			.setAttribute("name", Locale.GERMAN, "Lenovo Legion 5 15ITH6H Phantom Blau/Schatten Schwarz(3 Jahre Garantie)")
			.setAttribute("catalogCode", "82JH00KYCK")
			.setAttribute("stockQuantity", 8)
			.setAssociatedData(
				"gallery",
				new String[] {
					"https://cdn.alza.cz/ImgW.ashx?fd=f4&cd=NT379t71j3b&i=1.jpg",
					"https://cdn.alza.cz/ImgW.ashx?fd=FotoAddOrig&cd=NT379t71j3b-01&i=1.jpg",
					"https://cdn.alza.cz/ImgW.ashx?fd=FotoAddOrig&cd=NT379t71j3b-06&i=1.jpg"
				}
			)
			.setPrice(
				1, "basic", Currency.getInstance("EUR"),
				new BigDecimal("1040.16"), new BigDecimal("22"), new BigDecimal(1269),
				true
			)
			.setPrice(
				2, "basic", Currency.getInstance("USD"),
				new BigDecimal("1097.54"), new BigDecimal("22"), new BigDecimal(1339),
				true
			)
			.setReference("brand", "Brand", Cardinality.ZERO_OR_ONE, 2)
			.setReference("categories", "Category", Cardinality.ZERO_OR_MORE, 3)
			.upsertVia(session);

		session.createNewEntity("Product")
			.setAttribute("name", Locale.ENGLISH, "Acer Nitro 5 Shale Black")
			.setAttribute("name", Locale.GERMAN, "Acer Nitro 5 Shale Schwarz")
			.setAttribute("catalogCode", "NH.QEKEC.002")
			.setAttribute("stockQuantity", 6)
			.setAssociatedData(
				"gallery",
				new String[] {
					"https://cdn.alza.cz/ImgW.ashx?fd=f4&cd=NC108c7i05a8b&i=1.jpg",
					"https://cdn.alza.cz/ImgW.ashx?fd=FotoAddOrig&cd=NC108c7i05a8b-01&i=1.jpg"
				}
			)
			.setPrice(
				1, "basic", Currency.getInstance("EUR"),
				new BigDecimal("627.05"), new BigDecimal("22"), new BigDecimal(765),
				true
			)
			.setPrice(
				2, "basic", Currency.getInstance("USD"),
				new BigDecimal("654.92"), new BigDecimal("22"), new BigDecimal(799),
				true
			)
			.setReference("brand", "Brand", Cardinality.ZERO_OR_ONE, 3)
			.setReference("categories", "Category", Cardinality.ZERO_OR_MORE, 3)
			.upsertVia(session);

		session.createNewEntity("Product")
			.setAttribute("name", Locale.ENGLISH, "ASUS Vivobook 16 X1605EA-MB044W Indie Black")
			.setAttribute("name", Locale.GERMAN, "ASUS Vivobook 16 X1605EA-MB044W Indie Schwarz")
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
			.setReference("brand", "Brand", Cardinality.ZERO_OR_ONE, 4)
			.setReference("categories", "Category", Cardinality.ZERO_OR_MORE, 3)
			.upsertVia(session);
	}
);
