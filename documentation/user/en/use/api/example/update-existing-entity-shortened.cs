session.GetEntity("Product", 1, AttributeContentAll(), DataInLocales(new CultureInfo("en")))
	.OpenForWrite()
	.SetAttribute(
		"name", new CultureInfo("en"),
		"ASUS Vivobook 16 X1605EA-MB044W Indie Black"
	)
	.UpsertVia(session);