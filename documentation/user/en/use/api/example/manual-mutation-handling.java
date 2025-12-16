evita.updateCatalog(
	"evita",
	session -> {
		session.applyMutation(
			new EntityUpsertMutation(
				"Product",
				EntityExistence.MUST_NOT_EXIST,
				List.of(
					new UpsertAttributeMutation("code", "siemens"),
					new UpsertAttributeMutation("name", Locale.ENGLISH, "Siemens"),
					new UpsertAttributeMutation("catalogCode", "1E23SIEMENS"),
					new UpsertAttributeMutation("stockQuantity", 10),
					new UpsertPriceMutation(
						new PriceKey(1, "basic", Currency.getInstance("EUR")),
						BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true
					)
				)
			)
		);
	}
);