evita.updateCatalog(
	"evita",
	session -> {
		session.getEntity(
			"Product", 1,
			attributeContentAll(),
			priceContentAll()
		)
			.orElseThrow()
			.openForWrite()
			.setAttribute("stockQuantity", 12)
			.setPrice(
				1, "basic", Currency.getInstance("EUR"),
				new BigDecimal("51.64"), new BigDecimal("22"), new BigDecimal(63),
				true
			)
			.upsertVia(session);
	}
);
