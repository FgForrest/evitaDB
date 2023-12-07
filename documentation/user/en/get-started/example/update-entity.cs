evita.UpdateCatalog(
	"testCatalog",
	session => {
		session.GetEntity(
			"Product", 1,
			AttributeContentAll(),
			PriceContentRespectingFilter()
		)
			.OpenForWrite()
			.SetAttribute("stockQuantity", 12)
			.SetPrice(
				1, "basic", new Currency("EUR"),
				51.64m, 22m, 63m,
				true
			)
			.UpsertVia(session);
	}
);