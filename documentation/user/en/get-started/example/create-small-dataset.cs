evita.UpdateCatalog(
	"evita",
	session => {
		session
			.CreateNewEntity("Brand", 2)
			.SetAttribute("name", "Lenovo")
			.UpsertVia(session);

		session
			.CreateNewEntity("Brand", 3)
			.SetAttribute("name", "Acer")
			.UpsertVia(session);

		session
			.CreateNewEntity("Brand", 4)
			.SetAttribute("name", "ASUS")
			.UpsertVia(session);

		session
			.CreateNewEntity("Category", 1)
			.SetAttribute("name", new CultureInfo("en"), "Electronics")
			.SetAttribute("name", new CultureInfo("de"), "Elektronik")
			.UpsertVia(session);

		session
			.CreateNewEntity("Category", 2)
			.SetParent(1)
			.SetAttribute("name", new CultureInfo("en"), "Components")
			.SetAttribute("name", new CultureInfo("de"), "Komponenten")
			.UpsertVia(session);

		session
			.CreateNewEntity("Category", 3)
			.SetParent(1)
			.SetAttribute("name", new CultureInfo("en"), "Portable computer")
			.SetAttribute("name", new CultureInfo("de"), "Tragbarer Computer")
			.UpsertVia(session);

		session.CreateNewEntity("Product")
			.SetAttribute("name", new CultureInfo("en"), "Lenovo ThinkPad UltraSlim USB DVD Burner")
			.SetAttribute("name", new CultureInfo("de"), "Lenovo ThinkPad UltraSlim USB-DVD-Brenner")
			.SetAttribute("catalogCode", "4XA0E97775")
			.SetAttribute("stockQuantity", 3)
			.SetAssociatedData(
				"gallery",
				new string[] {
					"https://cdn.alza.cz/ImgW.ashx?fd=f4&cd=NT442p2i&i=1.jpg",
					"https://cdn.alza.cz/ImgW.ashx?fd=FotoAddOrig&cd=NT442p2i-02&i=1.jpg"
				}
			)
			.SetPrice(
				1, "basic", new Currency("EUR"),
				63.93m, 22m, 78m,
				true
			)
			.SetPrice(
				2, "basic", new Currency("USD"),
				68.03m, 22m, 83m,
				true
			)
			.SetReference("brand", "Brand", Cardinality.ZeroOrMore, 2)
			.SetReference("categories", "Category", Cardinality.ZeroOrMore, 2)
			.UpsertVia(session);

		session.CreateNewEntity("Product")
			.SetAttribute("name", new CultureInfo("en"), "ASUS SDRW-08U7M-U black + 2× M-Disk")
			.SetAttribute("name", new CultureInfo("de"), "ASUS SDRW-08U7M-U schwarz + 2× M-Disk")
			.SetAttribute("catalogCode", "90DD01X0-M29000")
			.SetAttribute("stockQuantity", 1)
			.SetAssociatedData(
				"gallery",
				new string[] {
					"https://cdn.alza.cz/ImgW.ashx?fd=f4&cd=GM382c8d&i=1.jpg",
					"https://cdn.alza.cz/ImgW.ashx?fd=FotoAddOrig&cd=GM382c8d-02&i=1.jpg",
					"https://cdn.alza.cz/ImgW.ashx?fd=FotoAddOrig&cd=GM382c8d-03&i=1.jpg",
					"https://cdn.alza.cz/ImgW.ashx?fd=FotoAddOrig&cd=GM382c8d-04&i=1.jpg"
				}
			)
			.SetPrice(
				1, "basic", new Currency("EUR"),
				27.87m, 22m, 34m,
				true
			)
			.SetPrice(
				2, "basic", new Currency("USD"),
				29.5m, 22m, 36m,
				true
			)
			.SetReference("brand", "Brand", Cardinality.ZeroOrMore, 4)
			.SetReference("categories", "Category", Cardinality.ZeroOrMore, 2)
			.UpsertVia(session);

		session.CreateNewEntity("Product")
			.SetAttribute("name", new CultureInfo("en"), "Lenovo Legion 5 15ITH6H Phantom Blue/Shadow Black(3 years warranty)")
			.SetAttribute("name", new CultureInfo("de"), "Lenovo Legion 5 15ITH6H Phantom Blau/Schatten Schwarz(3 Jahre Garantie)")
			.SetAttribute("catalogCode", "82JH00KYCK")
			.SetAttribute("stockQuantity", 8)
			.SetAssociatedData(
				"gallery",
				new string[] {
					"https://cdn.alza.cz/ImgW.ashx?fd=f4&cd=NT379t71j3b&i=1.jpg",
					"https://cdn.alza.cz/ImgW.ashx?fd=FotoAddOrig&cd=NT379t71j3b-01&i=1.jpg",
					"https://cdn.alza.cz/ImgW.ashx?fd=FotoAddOrig&cd=NT379t71j3b-06&i=1.jpg"
				}
			)
			.SetPrice(
				1, "basic", new Currency("EUR"),
				1040.16m, 22m, 1269m,
				true
			)
			.SetPrice(
				2, "basic", new Currency("USD"),
				1097.54m, 22m, 1339m,
				true
			)
			.SetReference("brand", "Brand", Cardinality.ZeroOrMore, 2)
			.SetReference("categories", "Category", Cardinality.ZeroOrMore, 3)
			.UpsertVia(session);

		session.CreateNewEntity("Product")
			.SetAttribute("name", new CultureInfo("en"), "Acer Nitro 5 Shale Black")
			.SetAttribute("name", new CultureInfo("de"), "Acer Nitro 5 Shale Schwarz")
			.SetAttribute("catalogCode", "NH.QEKEC.002")
			.SetAttribute("stockQuantity", 6)
			.SetAssociatedData(
				"gallery",
				new string[] {
					"https://cdn.alza.cz/ImgW.ashx?fd=f4&cd=NC108c7i05a8b&i=1.jpg",
					"https://cdn.alza.cz/ImgW.ashx?fd=FotoAddOrig&cd=NC108c7i05a8b-01&i=1.jpg"
				}
			)
			.SetPrice(
				1, "basic", new Currency("EUR"),
				627.05m, 22m, 765m,
				true
			)
			.SetPrice(
				2, "basic", new Currency("USD"),
				654.92m, 22m, 799m,
				true
			)
			.SetReference("brand", "Brand", Cardinality.ZeroOrMore, 3)
			.SetReference("categories", "Category", Cardinality.ZeroOrMore, 3)
			.UpsertVia(session);

		session.CreateNewEntity("Product")
			.SetAttribute("name", new CultureInfo("en"), "ASUS Vivobook 16 X1605EA-MB044W Indie Black")
			.SetAttribute("name", new CultureInfo("de"), "ASUS Vivobook 16 X1605EA-MB044W Indie Schwarz")
			.SetAttribute("catalogCode", "X1605EA-MB044W")
			.SetAttribute("stockQuantity", 1)
			.SetAssociatedData(
				"gallery",
				new string[] {
					"https://cdn.alza.cz/ImgW.ashx?fd=f4&cd=NA579p8e0&i=1.jpg",
					"https://cdn.alza.cz/ImgW.ashx?fd=FotoAddOrig&cd=NA579p8e0-04&i=1.jpg"
				}
			)
			.SetPrice(
				1, "basic", new Currency("EUR"),
				345.9m, 22m, 422m,
				true
			)
			.SetPrice(
				2, "basic", new Currency("USD"),
				365.57m, 22m, 446m,
				true
			)
			.SetReference("brand", "Brand", Cardinality.ZeroOrMore, 4)
			.SetReference("categories", "Category", Cardinality.ZeroOrMore, 3)
			.UpsertVia(session);
	}
);