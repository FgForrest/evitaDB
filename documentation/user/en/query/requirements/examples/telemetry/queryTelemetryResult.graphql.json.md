```json
{
  "operation": "OVERALL",
  "start": 182792910774973,
  "steps": [
    {
      "operation": "PLANNING",
      "start": 182792910785703,
      "steps": [
        {
          "operation": "PLANNING_INDEX_USAGE",
          "start": 182792910786665,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "13845"
        },
        {
          "operation": "PLANNING_FILTER",
          "start": 182792910807253,
          "steps": [
            {
              "operation": "PLANNING_FILTER_ALTERNATIVE",
              "start": 182792910811050,
              "steps": [ ],
              "arguments": [
                "Index type: GLOBAL, estimated costs 585"
              ],
              "spentTime": "1081806"
            }
          ],
          "arguments": [
            "Selected index: Index type: GLOBAL, estimated costs 585"
          ],
          "spentTime": "1097987"
        },
        {
          "operation": "PLANNING_SORT",
          "start": 182792911914768,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "198009"
        },
        {
          "operation": "PLANNING_EXTRA_RESULT_FABRICATION",
          "start": 182792912113819,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "11440"
        }
      ],
      "arguments": [ ],
      "spentTime": "1343494"
    },
    {
      "operation": "EXECUTION",
      "start": 182792912130589,
      "steps": [
        {
          "operation": "EXECUTION_FILTER",
          "start": 182792912135459,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "53931"
        },
        {
          "operation": "EXECUTION_SORT_AND_SLICE",
          "start": 182792912189580,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "149889"
        }
      ],
      "arguments": [ ],
      "spentTime": "255648"
    }
  ],
  "arguments": [ ],
  "spentTime": "1611755"
}
```