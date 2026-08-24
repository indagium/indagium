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
Desktop Android logcat viewer and analysis tool for crashes, ANRs, and bug reports.
```

Set exactly these topics (and remove unrelated topics):

```
android
android-logcat
log-analysis
log-viewer
android-debugging
bugreport
desktop-application
kotlin
compose-multiplatform
source-available
```

### Search Console and Bing baseline

After the site is deployed at `https://indagium.com/`, complete this baseline
once for each search service:

1. Verify the `indagium.com` property using the provider's supported DNS or
   site-ownership method.
2. Submit the exact sitemap URL `https://indagium.com/sitemap.xml`.
3. Request indexing/recrawl for the homepage and each of the five canonical
   routes listed in the sitemap. Record the submission date and any reported
   validation errors.
4. After the first crawl, confirm that the homepage and route pages resolve
   with status 200, use their canonical URL, and expose the intended title and
   description. Record the first indexed date or the service's equivalent
   coverage state.
5. When a page or sitemap changes, resubmit the sitemap, request recrawl for
   the changed canonical URLs, and compare coverage plus enhancement reports
   with the baseline rather than treating a temporary crawl delay as a site
   failure.

Keep the service-specific verification details and dates in the release or
website operations record; do not commit credentials or verification tokens to
the repository.
