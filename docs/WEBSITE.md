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
