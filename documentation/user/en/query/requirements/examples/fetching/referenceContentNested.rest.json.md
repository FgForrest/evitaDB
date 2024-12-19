```json
{
  "data": [
    {
      "primaryKey": 108528,
      "type": "Product",
      "version": 1,
      "scope": "LIVE",
      "allLocales": [
        "en"
      ],
      "attributes": {
        "global": {
          "code": "samsung-galaxy-watch-4"
        }
      },
      "groups": [
        {
          "referencedPrimaryKey": 112769,
          "referencedEntity": {
            "primaryKey": 112769,
            "type": "Group",
            "version": 1,
            "scope": "LIVE",
            "allLocales": [
              "en"
            ],
            "attributes": {
              "global": {
                "code": "special-offer-group"
              }
            },
            "tags": [
              {
                "referencedPrimaryKey": 11,
                "referencedEntity": {
                  "primaryKey": 11,
                  "type": "Tag",
                  "version": 1,
                  "scope": "LIVE",
                  "allLocales": [
                    "cs",
                    "de",
                    "en"
                  ],
                  "attributes": {
                    "global": {
                      "code": "special-offer"
                    }
                  },
                  "categories": [
                    {
                      "referencedPrimaryKey": 8
                    }
                  ]
                },
                "groupEntity": {
                  "primaryKey": 0,
                  "type": "tagCategory"
                }
              }
            ]
          }
        }
      ]
    }
  ],
  "empty": false,
  "first": true,
  "firstPageItemNumber": 0,
  "hasNext": false,
  "hasPrevious": false,
  "last": true,
  "lastPageItemNumber": 1,
  "lastPageNumber": 1,
  "pageNumber": 1,
  "pageSize": 20,
  "singlePage": true,
  "totalRecordCount": 1,
  "type": "PAGE"
}
```