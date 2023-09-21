EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeEquals("code", "garmin-vivoactive-4"),
        		EntityLocaleEquals(CultureInfo.GetCultureInfo("en"))
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code"),
        			ReferenceContent(
        				"parameterValues",
        				OrderBy(
        					EntityGroupProperty(
        						AttributeNatural("name", Asc)
        					),
        					EntityProperty(
        						AttributeNatural("name", Asc)
        					)
        				),
        				EntityFetch(
        					AttributeContent("name")
        				),
        				EntityGroupFetch(
        					AttributeContent("name")
        				)
        			)
        		)
        	)
        )
	)
);