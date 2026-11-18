# Daily X archive sync (free method)

The scheduled GitHub Actions workflow reads the public timeline of @nagata_shiori_ using the read-only `x` CLI by tamnd. It uses X's public syndication surface and does not require an X Developer account, Bearer Token, browser cookies, paid RSS service, or paid API plan.

## Schedule

- Daily at 06:35 Japan time (21:35 UTC)
- Can also be started manually from GitHub Actions.
- New posts are stored with original text, Japan-time timestamp, and individual status URL.
- Existing IDs are deduplicated.
- Retweets are excluded; replies are retained when the public timeline exposes them.
- The importer refuses to overwrite data if the request fails or its response cannot be parsed.

## Important limitations

This uses an unofficial, undocumented public X surface through an open-source client, not an X-supported API. X can change or block it without notice. The public syndication tier provides only a recent timeline window; the daily cadence is intended to avoid gaps, but this cannot guarantee complete capture or historical backfill. If X blocks the endpoint, the workflow fails visibly and leaves the existing archive untouched.

The GitHub Actions workflow and the public client are free to use. No paid X API is involved. GitHub Actions usage is subject to the repository/account's included minutes and usage limits.

## Source

- x-cli: https://github.com/tamnd/x-cli
- Project documentation: https://x-cli.tamnd.com/
