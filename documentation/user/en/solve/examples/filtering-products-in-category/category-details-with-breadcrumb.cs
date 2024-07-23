EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Category"),
        	FilterBy(
        		EntityLocaleEquals(CultureInfo.GetCultureInfo("en")),
        		AttributeEquals("url", "/en/smartwatches")
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("name", "description", "descriptionShort"),
        			AssociatedDataContent("localization"),
        			HierarchyContent(
        				EntityFetch(
        					AttributeContent("name", "level")
        				)
        			)
        		)
        	)
        )
	)
);