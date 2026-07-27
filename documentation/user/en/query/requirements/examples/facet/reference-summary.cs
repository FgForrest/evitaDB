EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
		Query(
			Collection("Product"),
			FilterBy(
				HierarchyWithin(
					"categories",
					AttributeEquals("code", "e-readers")
				),
				EntityLocaleEquals("en")
			),
			Require(
				Page(1, 5),
				EntityFetch(AttributeContent("code")),
				ReferenceSummary(
					FacetStatisticsDepth.Counts,
					EntityFetch(AttributeContent("code")),
					EntityGroupFetch(AttributeContent("code"))
				)
			)
		)
	)
);
