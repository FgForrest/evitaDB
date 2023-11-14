ISealedEntity brand = new InitialEntityBuilder("brand", 1)
	.SetAttribute("code", "siemens")
	.SetAttribute("name", new CultureInfo("en"), "Siemens")
	.SetAttribute("logo", "https://www.siemens.com/logo.png")
	.SetAttribute("productCount", 1)
	.ToInstance();