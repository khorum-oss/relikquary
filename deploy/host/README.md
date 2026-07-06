# Host config for the cluster host — bare `:80` ingress access

The cluster's ingress-nginx is exposed as a NodePort, and colima/lima forwards it on the LAN
(`http://<host-ip>:30080`). colima **won't** forward privileged ports (`<1024`) to the host, so to reach
the ingress on a clean `:80` we use macOS `pf` to redirect inbound `:80` → `:30080`.

Everything else (routing, hostnames, TLS posture) lives in the cluster manifests:
`deploy/k8s/overlays/*/ingress.yaml` and `deploy/argocd/ingress.yaml`.

## Two things you supply — host domain & LAN IP

Neither the host's LAN IP nor the DNS suffix you browse by is committed. The manifests and the
`/etc/hosts` snippet ship with two placeholders:

| Placeholder  | Meaning                                             | Supplied via                          |
|--------------|-----------------------------------------------------|---------------------------------------|
| `lan-domain` | the host suffix for `*.<domain>` names (you choose) | `-d/--domain` or `$LAN_DOMAIN`         |
| `lan-ip`     | the host's LAN IP (for the `*.nip.io` names)        | `-i/--ip` or `$LAN_IP`, else auto-detect |

`deploy/host/render-lan-ip.sh` fills both in locally at apply time, so nothing environment-specific
lands in git. Pick any domain you like (`home`, `lan`, `local`, …).

```sh
# print your host's LAN IP (what nip.io / /etc/hosts should point at)
deploy/host/render-lan-ip.sh ip

# print the /etc/hosts line for the MacBook (below), filled in
deploy/host/render-lan-ip.sh hosts -d home

# render the ingress with both placeholders filled — to stdout, or apply it
deploy/host/render-lan-ip.sh render stage -d home          # stage | prod | argocd
deploy/host/render-lan-ip.sh apply  stage -d home          # render + kubectl apply -f -
```

`LAN_DOMAIN=home` in the environment works instead of `-d`, and `LAN_IP=…` / `-i` overrides IP
auto-detection.

## Enable the :80 redirect

```sh
# one-time file install
sudo cp deploy/host/pf.anchors/relikquary-ingress /etc/pf.anchors/relikquary-ingress
sudo cp deploy/host/pf-relikquary.conf            /etc/pf-relikquary.conf

# turn it on now
sudo pfctl -ef /etc/pf-relikquary.conf

# persist across reboots
sudo cp deploy/host/com.relikquary.ingress-redirect.plist /Library/LaunchDaemons/
sudo launchctl load -w /Library/LaunchDaemons/com.relikquary.ingress-redirect.plist
```

Verify (swap in your domain): `curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: stage.home' http://127.0.0.1/` → `200`.

Disable / undo: `sudo pfctl -d` (off until reboot), or unload the daemon:
`sudo launchctl unload -w /Library/LaunchDaemons/com.relikquary.ingress-redirect.plist`.

## MacBook `/etc/hosts`

Point the hostnames at the host's LAN IP (`.local` mDNS can't resolve subdomains). Run
`render-lan-ip.sh hosts -d <domain>` to print this line filled in, or substitute `lan-ip` / `lan-domain`
by hand:

```
lan-ip  stage.lan-domain stage-api.lan-domain prod.lan-domain prod-api.lan-domain argocd.lan-domain
```

Then browse `http://stage.<domain>`, `http://prod.<domain>`, `http://argocd.<domain>` (drop the
`:30080`). Without the pf redirect, append `:30080` to each URL instead.
