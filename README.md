# CN/HS Candidate Classification PoC (Kotlin + Ktor)

PoC REST service for deterministic CN/HS candidate support on industrial material master data.

The service ingests material texts, normalizes them, extracts attributes, detects product family, ranks candidate CN groups, and returns top candidates with reasons, missing information, and confidence.

## Scope

This is a PoC helper for candidate generation, not a legally binding customs classifier.

## Tech Stack

- Kotlin
- Ktor
- Gradle Kotlin DSL
- `kotlinx.serialization`
- Config-driven rules/catalog in JSON resources

## Project Structure

```text
src/main/kotlin/com/zollpilot/
  api/
    ClassificationRoutes.kt
    HealthRoutes.kt
    CatalogRoutes.kt
  domain/
    ApiModels.kt
    ClassificationModels.kt
    CatalogModels.kt
  service/
    ClassificationService.kt
    NormalizationService.kt
    ExtractionService.kt
    FamilyDetectionService.kt
    CandidateRetrievalService.kt
    ScoringService.kt
    ExplanationService.kt
  parser/
    CsvParser.kt
  config/
    CatalogLoader.kt
    AppConfig.kt
  plugins/
    Routing.kt
    Serialization.kt
    StatusPages.kt
    Logging.kt
  Application.kt

src/main/resources/
  application.conf
  materials.csv
  catalog/
    families.json
    cn-candidates.json
```

## Run

```bash
./gradlew run
```

Server default: `http://localhost:8080`

## Test

```bash
./gradlew test
```

## API Endpoints

Base path: `/api/v1`

- `POST /classify`
- `POST /classify/batch`
- `POST /classify/upload-csv`
- `GET /classify/test-resource-csv`
- `GET /classify/llm-status/{jobId}`
- `GET /health`
- `GET /catalog/families`

## Static Upload UI

- `GET /` (or `/ui`, `/ui/upload`)
- Uploads a CSV to `POST /api/v1/classify/upload-csv` and renders the returned JSON results in a table with raw JSON fallback.
- CSV/resource endpoints return local rule-based results immediately and include `llmJobId`/`llmJobStatus` while AI enrichment runs in the background.

## Example JSON Request

```json
{
  "materialNumber": "1000001",
  "shortText": "Sechskantschraube DIN 933 M12x40 verzinkt",
  "purchaseText": "Stahl 8.8 galvanisiert"
}
```

## Example `curl` Commands

### Health

```bash
curl -s http://localhost:8080/api/v1/health
```

### Families

```bash
curl -s http://localhost:8080/api/v1/catalog/families
```

### Single Classification

```bash
curl -s -X POST http://localhost:8080/api/v1/classify \
  -H "Content-Type: application/json" \
  -d '{
    "materialNumber": "1000003",
    "shortText": "O-Ring 34,00 x 3,50 mm NBR 70 Shore A",
    "purchaseText": "Dichtring fuer Pneumatikventil"
  }'
```

### Batch Classification

```bash
curl -s -X POST http://localhost:8080/api/v1/classify/batch \
  -H "Content-Type: application/json" \
  -d '[
    {
      "materialNumber": "1000001",
      "shortText": "Sechskantschraube DIN 933 M12x40 verzinkt",
      "purchaseText": "Stahl 8.8 galvanisiert"
    },
    {
      "materialNumber": "1000005",
      "shortText": "Kugellager 6205 2RS SKF",
      "purchaseText": "Rillenkugellager beidseitig abgedichtet"
    }
  ]'
```

### CSV Upload

```bash
curl -s -X POST http://localhost:8080/api/v1/classify/upload-csv \
  -F "file=@src/main/resources/materials.csv"
```

### Resource CSV Demo

```bash
curl -s http://localhost:8080/api/v1/classify/test-resource-csv
```

## CSV Format

Required columns:

- `Materialnummer`
- `Kurztext`
- `Einkaufsbestelltext`

Delimiter can be `;` or `,`.

## Config

`src/main/resources/application.conf`:

```hocon
app {
  testCsvFile = "materials.csv"
  catalog {
    familiesPath = "catalog/families.json"
    candidatesPath = "catalog/cn-candidates.json"
  }
  llm {
    enabled = true
    apiKey = ${?OPENAI_API_KEY}
    endpoint = "https://api.openai.com/v1/chat/completions"
    model = "gpt-4o-mini"
    maxItemsPerRequest = 12
    maxPromptCharsPerRequest = 14000
    parallelRequests = 6
    maxRetriesPerChunk = 1
    maxCompletionTokens = 3600
    timeoutSeconds = 150
    temperature = 0.0
  }
}
```

`OPENAI_API_KEY` can be provided as environment variable. If no key is set, local deterministic classification still works and `llm` in the response stays `null`.

### LLM Enrichment in Response

Each classification item can contain:

- `llm.headline`
- `llm.candidateHeadlines` (same style per candidate)
- `llm.selectedCnCode` (normalisiert auf volle CN: `NNNN NN NN`, falls ableitbar)
- `llm.explanation`
- `llm.confidencePercent` (0-100)
- `llmStatus` (`pending|completed|failed|skipped`)

LLM Freitext-Felder (`headline`, `candidateHeadlines`, `explanation`) werden in deutscher Sprache angefordert.

Batch endpoints group multiple clusters into one OpenAI request and chunk by `app.llm.maxItemsPerRequest` to avoid sending many requests.

For local-first endpoints (`/classify/upload-csv`, `/classify/test-resource-csv`):
- initial response: `{ results, llmJobId, llmJobStatus: "processing" }`
- poll `GET /api/v1/classify/llm-status/{llmJobId}` until `llmJobStatus` is `completed` or `failed`

## Extension Guide

### Add/adjust families

Edit `src/main/resources/catalog/families.json`:

- `id`
- `keywords`
- `patterns`
- `priority`

### Add/adjust candidate groups

Edit `src/main/resources/catalog/cn-candidates.json`:

- `code`, `label`
- `familyMatches`, `keywords`
- `materialHints`, `normHints`
- `includeTokens`, `excludeTokens`
- `dimensionRelevant`

### Adjust scoring logic

Update `ScoringService.kt` for scoring weights and penalties.

### Adjust extraction logic

Update regex/token rules in `ExtractionService.kt`.

## IntelliJ Run Configuration (Example)

1. Open **Run | Edit Configurations**.
2. Add **Gradle** configuration.
3. Set:
   - Name: `zollpilot-run`
   - Gradle project: this project root
   - Tasks: `run`
4. Run configuration to start service.

## Notes

- No DB/auth included by design.
- Rule and catalog data are loaded from resources at startup.
- Designed for readability and extension, not legal final classification.
