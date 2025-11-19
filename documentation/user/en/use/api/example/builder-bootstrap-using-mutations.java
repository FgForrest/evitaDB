final SealedEntity brand = new ExistingEntityBuilder(
	existingEntity,
	List.of(
		new UpsertAttributeMutation("code", "siemens"),
		new UpsertAttributeMutation("name", Locale.ENGLISH, "Siemens"),
		new UpsertAttributeMutation("logo", "https://www.siemens.com/logo.png"),
		new UpsertAttributeMutation("productCount", 1),
		new UpsertPriceMutation(
			new PriceKey(1, "basic", Currency.getInstance("CZK")),
			BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true
		)
	)
)
.toInstance()