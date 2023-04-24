final SealedEntity brand = new ExistingEntityBuilder(existingEntity)
	.setAttribute("code", "siemens")
	.setAttribute("name", Locale.ENGLISH, "Siemens")
	.setAttribute("logo", "https://www.siemens.com/logo.png")
	.setAttribute("productCount", 1)
	.toInstance();