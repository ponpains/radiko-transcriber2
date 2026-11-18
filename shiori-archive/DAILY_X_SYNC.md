# Daily X archive sync

This workflow checks the public timeline of @nagata_shiori_ once per day and adds new posts to the archive JSON consumed by the Android app. The app already checks the published manifest every 12 hours.

## One-time requirement

Add a repository Actions secret named `X_BEARER_TOKEN` containing an X API v2 Bearer Token. X does not provide a dependable free public timeline feed; the importer deliberately uses the official API rather than brittle HTML scraping or unofficial mirrors. Without this secret the workflow stops with a clear error and does not change archive data.

## Run / verify

- Workflow: **Daily Shiori X archive sync**
- Scheduled: daily at 21:35 UTC (06:35 Japan time)
- Can also be started manually from GitHub Actions.
- New posts are stored with their original text, timestamp and individual status URL.
- Retweets are excluded; replies are included.
- If the API is unavailable, the workflow fails visibly and preserves the existing archive.

The importer only reads the public timeline for the named account. It does not post, like, follow, or access private content.
