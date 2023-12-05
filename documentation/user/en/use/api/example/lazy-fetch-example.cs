evita.QueryCatalog(
	"evita",
	session => {
		EvitaResponse<ISealedEntity> entities = session.Query<EvitaEntityResponse, ISealedEntity>(
			Query(
				Collection("Product"),
				FilterBy(
					And(
						EntityLocaleEquals(new CultureInfo("en")),
						PriceInPriceLists("basic"),
						PriceInCurrency(new Currency("CZK"))
					)
				),
				Require(
					EntityFetch(
						AttributeContent("name")
					)
				)
			)
		);
		// we need to enrich first found entity only
		// the entity will be enriched with:
		//   - rest of the attributes
		//   - all associated data
		//   - prices (`basic` + `reference`) will be fetched
		//   - reference `brand`
		ISealedEntity enrichedEntity = session.EnrichEntity(
			entities.RecordData.First(),
			AttributeContentAll(),
			AssociatedDataContentAll(),
			PriceContentRespectingFilter("reference"),
			ReferenceContent("brand")
		);
		return entities;
	}
);