evita.UpdateCatalog(
	"testCatalog",
	session => {
		session.GetEntity("Product", 1, AttributeContentAll(), DataInLocalesAll())
			.OpenForWrite()
			.SetAttribute(
				"name", new CultureInfo("en"),
				"ASUS Vivobook 16 X1605EA-MB044W Indie Black"
			)
			.SetAttribute(
				"name", new CultureInfo("de"),
				"ASUS Vivobook 16 X1605EA-MB044W Indie Schwarz"
			)
			.UpsertVia(session);
	}
);