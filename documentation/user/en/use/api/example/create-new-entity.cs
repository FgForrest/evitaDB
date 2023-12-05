evita.UpdateCatalog(
	"testCatalog",
	session => {
		session.CreateNewEntity("Product")
			.SetAttribute(
				"name", new CultureInfo("en"),
				"ASUS Vivobook 16 X1605EA-MB044W Indie Black"
			)
			.SetAttribute(
				"name", new CultureInfo("de"),
				"ASUS Vivobook 16 X1605EA-MB044W Indie Schwarz"
			)
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
			.SetReference("brand", "Brand", Cardinality.ZeroOrMore, 3)
			.SetReference("categories", "Category", Cardinality.ZeroOrMore, 3)
			.UpsertVia(session);
	}
);