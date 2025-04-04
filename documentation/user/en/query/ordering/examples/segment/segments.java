final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					priceInPriceLists("basic"),
					priceInCurrency(Currency.getInstance("EUR")),
					priceBetween(new BigDecimal("100"), new BigDecimal("103"))
				),
				orderBy(
					segments(
						segment(
							orderBy(
								attributeNatural("published", DESC)
							),
							limit(2)
						),
						segment(
							entityHaving(
								priceBetween(new BigDecimal("500"), new BigDecimal("10000"))
							),
							orderBy(
								attributeNatural("orderedQuantity", DESC)
							),
							limit(1)
						),
						segment(
							entityHaving(
								priceBetween(new BigDecimal("0"), new BigDecimal("500"))
							),
							orderBy(
								attributeNatural("orderedQuantity", DESC)
							),
							limit(1)
						),
						segment(
							entityHaving(
								referenceHaving(
									"stocks",
									attributeGreaterThan("quantityOnStock", 0)
								)
							),
							orderBy(
								attributeNatural("orderedQuantity", DESC)
							),
							limit(1)
						),
						segment(
							orderBy(
								attributeNatural("orderedQuantity", DESC)
							)
						)
					)
				),
				require(
					page(1, 10),
					entityFetch(
						attributeContent("code", "published", "orderedQuantity"),
						referenceContentWithAttributes(
							"stocks",
							attributeContent("quantityOnStock")
						),
						priceContentRespectingFilter()
					)
				)
			)
		);
	}
);
