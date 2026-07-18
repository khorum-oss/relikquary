<script lang="ts">
  import type { TrustStatus } from '$lib/api';

  // Advisory cosign trust badge for a container image (feature 024): verified / signed-but-unverified /
  // unsigned / unknown. Advisory only — it never affects whether an image can be pulled.
  let { trust }: { trust: TrustStatus } = $props();

  const LABELS: Record<TrustStatus, string> = {
    verified: 'verified',
    'signed-but-unverified': 'signed',
    unsigned: 'unsigned',
    unknown: 'unknown',
  };
  const TITLES: Record<TrustStatus, string> = {
    verified: 'Signature verified against the configured cosign key',
    'signed-but-unverified': 'A signature exists but did not verify against the configured key',
    unsigned: 'No cosign signature found for this image',
    unknown: 'No cosign key is configured for this repository',
  };
</script>

<span class="trust {trust}" data-testid="trust-badge" data-trust={trust} title={TITLES[trust]}>
  {LABELS[trust]}
</span>

<style>
  .trust {
    display: inline-block;
    font-family: var(--rq-serif);
    font-size: 9px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    padding: 1px 7px;
    border-radius: 999px;
    border: 1px solid currentColor;
    vertical-align: middle;
    white-space: nowrap;
  }
  .verified {
    color: var(--rq-gold);
  }
  .signed-but-unverified {
    color: var(--rq-danger);
  }
  .unsigned {
    color: var(--rq-muted);
  }
  .unknown {
    color: var(--rq-dim);
  }
</style>
