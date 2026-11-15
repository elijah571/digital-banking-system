# CDN and Cloudflare Setup

Cloudflare requires control of the production DNS zone and account, which are not available in this repository. This document is an operator checklist; it does not mean the domain is already protected.

1. Proxy the public app/API DNS records through Cloudflare and use Full (strict) TLS to the origin.
2. Prefer Cloudflare Tunnel or firewall the origin to current Cloudflare IP ranges so clients cannot bypass edge WAF/rate rules.
3. Enable managed WAF rules and DDoS protections. Start in log mode, review false positives, then block.
4. Add path-specific rate limits for login, registration, reset, name enquiry, money movement, and webhooks. Preserve provider retry behavior for signed webhook paths.
5. Cache only static frontend assets. Bypass cache for all `/api/**`, `/docs/**`, authenticated content, and webhook paths.
6. Restrict accepted hostnames and configure trusted proxy handling for client IPs. Never trust arbitrary `X-Forwarded-For` from direct clients.
7. Test legitimate login, refresh, payment, and Fireblocks webhook traffic in staging before production enforcement.
8. Alert on origin bypass, WAF blocks, rate-limit spikes, and elevated 4xx/5xx responses.

Keep an emergency bypass procedure and named on-call owner. Do not publish a direct service port from production Compose.
