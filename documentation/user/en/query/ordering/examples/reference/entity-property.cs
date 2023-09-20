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
        					EntityProperty(
        						AttributeNatural("name", Desc)
        					)
        				),
        				EntityFetch(
        					AttributeContent("name")
        				)
        			)
        		)
        	)
        )
	)
);