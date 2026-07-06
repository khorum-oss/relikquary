# Host config for the mac-mini (`hestia`) — bare `:80` ingress access

The cluster's ingress-nginx is exposed as a NodePort, and colima/lima forwards it on the LAN
(`http://<mini-ip>:30080`). colima **won't** forward privileged ports (`<1024`) to the host, so to reach
the ingress on a clean `:80` we use macOS `pf` to redirect inbound `:80` → `:30080`.

Everything else (routing, hostnames, TLS posture) lives in the cluster manifests:
`deploy/k8s/overlays/*/ingress.yaml` and `deploy/argocd/ingress.yaml`.

## Enable the :80 redirect

```sh
# one-time file install
sudo cp deploy/host/pf.anchors/hestia-ingress /etc/pf.anchors/hestia-ingress
sudo cp deploy/host/pf-hestia.conf            /etc/pf-hestia.conf

# turn it on now
sudo pfctl -ef /etc/pf-hestia.conf

# persist across reboots
sudo cp deploy/host/com.hestia.ingress-redirect.plist /Library/LaunchDaemons/
sudo launchctl load -w /Library/LaunchDaemons/com.hestia.ingress-redirect.plist
```

Verify: `curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: stage.hestia' http://127.0.0.1/` → `200`.

Disable / undo: `sudo pfctl -d` (off until reboot), or unload the daemon:
`sudo launchctl unload -w /Library/LaunchDaemons/com.hestia.ingress-redirect.plist`.

## MacBook `/etc/hosts`

Point the hostnames at the mini's LAN IP (`.local` mDNS can't resolve subdomains):

```
192.168.50.206  stage.hestia stage-api.hestia prod.hestia prod-api.hestia argocd.hestia
```

Then browse `http://stage.hestia`, `http://prod.hestia`, `http://argocd.hestia` (drop the `:30080`).
Without the pf redirect, append `:30080` to each URL instead.
