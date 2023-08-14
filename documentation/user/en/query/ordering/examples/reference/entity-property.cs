EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeEquals("code", "garmin-vivoactive-4")
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code"),
        			ReferenceContent(
        				"parameterValues",
        				OrderBy(
        					EntityProperty(
        						AttributeNatural("code", Desc)
        					)
        				),
        				EntityFetch(
        					AttributeContent("code")
        				)
        			)
        		)
        	)
        )
	)
);