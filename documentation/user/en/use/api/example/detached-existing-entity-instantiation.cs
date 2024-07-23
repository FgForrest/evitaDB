// can be wrapped into a builder and updated
ISealedEntity brand = new ExistingEntityBuilder(existingEntity)
	.SetAttribute("code", "siemens")
	.SetAttribute("name", new CultureInfo("en"), "Siemens")
	.SetAttribute("logo", "https://www.siemens.com/logo.png")
	.SetAttribute("productCount", 1)
	.ToInstance();