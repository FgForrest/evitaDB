evita.updateCatalog(
	"testCatalog",
	session -> {
		session
			.createNewEntity("brand", 1)
			.setAttribute("name", "Lenovo")
			.upsertVia(session);

		session
			.createNewEntity("brand", 2)
			.setAttribute("name", "Acer")
			.upsertVia(session);

		session
			.createNewEntity("brand", 3)
			.setAttribute("name", "ASUS")
			.upsertVia(session);

		session
			.createNewEntity("category", 1)
			.setHierarchicalPlacement(1)
			.setAttribute("name", Locale.ENGLISH, "Electronics")
			.setAttribute("name", Locale.GERMAN, "Elektronik")
			.upsertVia(session);

		session
			.createNewEntity("category", 2)
			.setHierarchicalPlacement(1, 1)
			.setAttribute("name", Locale.ENGLISH, "Components")
			.setAttribute("name", Locale.GERMAN, "Komponenten")
			.upsertVia(session);

		session
			.createNewEntity("category", 3)
			.setHierarchicalPlacement(1, 2)
			.setAttribute("name", Locale.ENGLISH, "Portable computer")
			.setAttribute("name", Locale.GERMAN, "Tragbarer Computer")
			.upsertVia(session);

		session.createNewEntity(ENTITY_PRODUCT)
			.setAttribute("name", Locale.ENGLISH, "Lenovo ThinkPad UltraSlim USB DVD Burner")
			.setAttribute("name", Locale.GERMAN, "Lenovo ThinkPad UltraSlim USB-DVD-Brenner")
			.setAttribute("catalogCode", "4XA0E97775")
			.setAttribute("stockQuantity", 3)
			.setAssociatedData(
				ASSOCIATED_DATA_GALLERY,
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
			.setReference("brand", 1)
			.setReference("categories", 2)
			.upsertVia(session);

		session.createNewEntity(ENTITY_PRODUCT)
			.setAttribute("name", Locale.ENGLISH, "ASUS SDRW-08U7M-U black + 2× M-Disk")
			.setAttribute("name", Locale.GERMAN, "ASUS SDRW-08U7M-U schwarz + 2× M-Disk")
			.setAttribute("catalogCode", "90DD01X0-M29000")
			.setAttribute("stockQuantity", 1)
			.setAssociatedData(
				ASSOCIATED_DATA_GALLERY,
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
			.setReference("brand", 3)
			.setReference("categories", 2)
			.upsertVia(session);

		session.createNewEntity(ENTITY_PRODUCT)
			.setAttribute("name", Locale.ENGLISH, "Lenovo Legion 5 15ITH6H Phantom Blue/Shadow Black(3 years warranty)")
			.setAttribute("name", Locale.GERMAN, "Lenovo Legion 5 15ITH6H Phantom Blau/Schatten Schwarz(3 Jahre Garantie)")
			.setAttribute("catalogCode", "82JH00KYCK")
			.setAttribute("stockQuantity", 8)
			.setAssociatedData(
				ASSOCIATED_DATA_GALLERY,
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
			.setReference("brand", 1)
			.setReference("categories", 3)
			.upsertVia(session);

		session.createNewEntity(ENTITY_PRODUCT)
			.setAttribute("name", Locale.ENGLISH, "Acer Nitro 5 Shale Black")
			.setAttribute("name", Locale.GERMAN, "Acer Nitro 5 Shale Schwarz")
			.setAttribute("catalogCode", "NH.QEKEC.002")
			.setAttribute("stockQuantity", 6)
			.setAssociatedData(
				ASSOCIATED_DATA_GALLERY,
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
			.setReference("brand", 2)
			.setReference("categories", 3)
			.upsertVia(session);

		session.createNewEntity(ENTITY_PRODUCT)
			.setAttribute("name", Locale.ENGLISH, "ASUS Vivobook 16 X1605EA-MB044W Indie Black")
			.setAttribute("name", Locale.GERMAN, "ASUS Vivobook 16 X1605EA-MB044W Indie Schwarz")
			.setAttribute("catalogCode", "X1605EA-MB044W")
			.setAttribute("stockQuantity", 1)
			.setAssociatedData(
				ASSOCIATED_DATA_GALLERY,
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
			.setReference("brand", 3)
			.setReference("categories", 3)
			.upsertVia(session);
	}
);