# Website publishing

The public Indagium landing page lives in [`site/`](../site) and is deployed by
the **Deploy website** GitHub Actions workflow whenever that folder changes on
`master`.

## First-time GitHub Pages configuration

1. Open the [Indagium repository settings](https://github.com/indagium/indagium/settings/pages).
2. Under **Build and deployment**, set **Source** to **GitHub Actions**.
3. Under **Custom domain**, enter `indagium.com` and save it. Enable **Enforce
   HTTPS** once GitHub makes the option available.
4. At the domain registrar, create these DNS records:

   | Type | Host | Value |
   |---|---|---|
   | `A` | `@` | `185.199.108.153` |
   | `A` | `@` | `185.199.109.153` |
   | `A` | `@` | `185.199.110.153` |
   | `A` | `@` | `185.199.111.153` |
   | `CNAME` | `www` | `indagium.github.io` |

GitHub may take up to 24 hours to verify the domain and issue the HTTPS
certificate. Once it does, `www.indagium.com` will redirect to
`indagium.com`.

The page links its download buttons to the repository's latest GitHub Release,
so each new release is available immediately without a website update.

## Manual discovery handoff

The following external updates are intentionally manual. They are not performed
by the website build or by this repository change.

### GitHub repository About panel

Set the repository homepage to:

```
https://indagium.com/
```

Set the repository description to this exact text:

```
Android log viewer & logcat analyzer for Windows, macOS and Linux — capture synchronized Android logcat and screen video, analyze crashes and ANRs, open adb bugreports and DLT.
```

Set exactly these topics (and remove unrelated topics):

```
android
logcat
android-logcat
logcat-capture
logcat-viewer
log-analysis
android-debugging
wireless-logcat
wireless-debugging
adb
anr
bugreport
crash-analysis
dlt-viewer
mcp-server
desktop-application
kotlin
compose-multiplatform
source-available
developer-tools
```

### Search Console and Bing baseline

After the site is deployed at `https://indagium.com/`, complete this baseline
once for each search service:

1. Verify the `indagium.com` property using the provider's supported DNS or
   site-ownership method.
2. Submit the exact sitemap URL `https://indagium.com/sitemap.xml`.
3. Request indexing/recrawl for the homepage and each canonical
   route listed in the sitemap. Record the submission date and any reported
   validation errors.
4. After the first crawl, confirm that the homepage and route pages resolve
   with status 200, use their canonical URL, and expose the intended title and
   description. For the capture page, verify the visible video, poster, and
   VideoObject metadata point to the deployed MP4 and thumbnail. Record the first
   indexed date or the service's equivalent coverage state.
5. When a page or sitemap changes, resubmit the sitemap, request recrawl for
   the changed canonical URLs, and compare coverage plus enhancement reports
   with the baseline rather than treating a temporary crawl delay as a site
   failure.

### 28-day search-performance handoff

Before deployment, save an export from each service's Search Performance report
for the most recent **28 complete days** as the pre-change baseline. Record the
exact start/end dates, property, search type, export date, and whether the
report had data. Capture clicks, impressions, click-through rate, and average
position for the property and for each existing canonical sitemap URL; also
keep the query/keyword rows related to the viewer, analyzer, and new capture
intent. Leave unavailable values blank and label them as unavailable rather
than as zero. If no pre-deployment export exists, mark that baseline
unavailable; do not reconstruct it from a later window.

After the first crawl, record the capture URL's index/coverage status. Once it
has been indexed for 28 complete days, export the same metrics for the
post-change window and compare it with the pre-change baseline. Use weekly
aggregation where the service supports it, and save both exports plus a brief
note about indexing coverage and notable query/page changes in the website
operations record. The capture URL has no pre-launch page baseline, so report
its first indexed 28-day window on its own. If a service has not accumulated
enough data, record that and revisit after its reporting window fills; do not
infer traffic or ranking impact from missing data. Google's Performance report
supports date-range and metric selection; Bing Webmaster Tools supports
performance comparisons across time periods. See the [Google Search Console
Performance report](https://support.google.com/webmasters/answer/7576553) and
[Bing Webmaster Tools Search Performance](https://blogs.bing.com/webmaster/2025/3/Supercharge-Your-Search-Performance-with-Bing-Webmaster-Tools/).

Keep the service-specific verification details and dates in the release or
website operations record; do not commit credentials or verification tokens to
the repository. Google does not use `meta keywords` for web ranking; accurate
content and crawlable internal links are the discoverability work here. Neither
these changes nor `llms.txt` guarantee indexing, Google rankings, or Gemini
Log Viewers visibility. The download/rating explanation remains an unverified
hypothesis. No external accounts are changed by this handoff.
