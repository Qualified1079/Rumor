#!/usr/bin/env bash
# N100 home-appliance bootstrap. Run as a normal sudoer user, NOT as root.
# Re-runnable: each step checks for prior state and skips if already done.
#
# Defaults (edit before running if any are wrong):
#   IFACE          = auto-detected from default route
#   PIHOLE_SCOPE   = LAN + Tailscale (Pi-hole binds 0.0.0.0; trust the firewall)
#   SURICATA/ZEEK  = af-packet on $IFACE; assumes this box is the gateway OR
#                    $IFACE is a mirror port. On a normal LAN port you will
#                    only see broadcast + own traffic. Not the script's fault.
#   WIREGUARD      = netns skeleton (torrent client runs inside the ns;
#                    tunnel drop = no traffic, no leak). Keys added later.
#
set -euo pipefail
shopt -s inherit_errexit

log()  { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
warn() { printf '\033[1;33m!! %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[1;31mXX %s\033[0m\n' "$*" >&2; exit 1; }

[[ $EUID -ne 0 ]] || die "run as a normal sudoer user, not root"
sudo -v || die "need sudo"

IFACE="${IFACE:-$(ip -o -4 route show default | awk '{print $5; exit}')}"
[[ -n "$IFACE" ]] || die "could not detect default interface; set IFACE=... and re-run"
log "using interface: $IFACE"

log "apt update + base tools"
sudo apt-get update -y
sudo apt-get install -y curl gnupg lsb-release ca-certificates ufw net-tools dnsutils jq

# ---------------------------------------------------------------- 1. Tailscale
log "1/7 Tailscale"
if ! command -v tailscale >/dev/null; then
  curl -fsSL https://tailscale.com/install.sh | sh
fi
sudo systemctl enable --now tailscaled
# Enable IP forwarding (required for exit node)
if ! grep -q '^net.ipv4.ip_forward=1' /etc/sysctl.d/99-tailscale.conf 2>/dev/null; then
  printf 'net.ipv4.ip_forward=1\nnet.ipv6.conf.all.forwarding=1\n' \
    | sudo tee /etc/sysctl.d/99-tailscale.conf >/dev/null
  sudo sysctl -p /etc/sysctl.d/99-tailscale.conf
fi
if ! tailscale status >/dev/null 2>&1; then
  warn "run interactively now:  sudo tailscale up --advertise-exit-node --ssh"
  warn "then approve the exit node in the Tailscale admin console."
fi
systemctl is-active --quiet tailscaled || die "tailscaled not active"

# ---------------------------------------------------------------- 2. Pi-hole
log "2/7 Pi-hole"
# Free port 53 from systemd-resolved before installing
if systemctl is-active --quiet systemd-resolved; then
  sudo mkdir -p /etc/systemd/resolved.conf.d
  printf '[Resolve]\nDNSStubListener=no\n' \
    | sudo tee /etc/systemd/resolved.conf.d/no-stub.conf >/dev/null
  sudo systemctl restart systemd-resolved
  sudo ln -sf /run/systemd/resolve/resolv.conf /etc/resolv.conf
fi
if ! command -v pihole >/dev/null; then
  curl -fsSL https://install.pi-hole.net | sudo bash
fi
sudo systemctl enable --now pihole-FTL
systemctl is-active --quiet pihole-FTL || die "pihole-FTL not active"
dig @127.0.0.1 example.com +short +time=2 +tries=1 >/dev/null || warn "Pi-hole DNS not answering yet"

# ---------------------------------------------------------------- 3. Suricata
log "3/7 Suricata + ET Open"
sudo add-apt-repository -y ppa:oisf/suricata-stable || true
sudo apt-get update -y
sudo apt-get install -y suricata suricata-update
# Point Suricata at the right NIC (af-packet, passive)
sudo sed -i "s/^\(\s*-\s*interface:\s*\).*/\1$IFACE/" /etc/suricata/suricata.yaml
sudo suricata-update update-sources
sudo suricata-update enable-source et/open
sudo suricata-update
sudo systemctl enable --now suricata
sleep 2
systemctl is-active --quiet suricata || die "suricata not active"
test -s /var/log/suricata/eve.json || warn "no eve.json yet — expected if no traffic seen"

# ---------------------------------------------------------------- 4. Zeek
log "4/7 Zeek"
if ! command -v zeek >/dev/null; then
  . /etc/os-release
  echo "deb http://download.opensuse.org/repositories/security:/zeek/xUbuntu_${VERSION_ID}/ /" \
    | sudo tee /etc/apt/sources.list.d/zeek.list >/dev/null
  curl -fsSL "https://download.opensuse.org/repositories/security:zeek/xUbuntu_${VERSION_ID}/Release.key" \
    | gpg --dearmor | sudo tee /etc/apt/trusted.gpg.d/zeek.gpg >/dev/null
  sudo apt-get update -y
  sudo apt-get install -y zeek
fi
ZEEK_NODE=/opt/zeek/etc/node.cfg
if [[ -f $ZEEK_NODE ]]; then
  sudo sed -i "s/^interface=.*/interface=$IFACE/" "$ZEEK_NODE"
  sudo /opt/zeek/bin/zeekctl deploy
fi
sudo /opt/zeek/bin/zeekctl status || warn "zeekctl reports not running — check $ZEEK_NODE"

# ---------------------------------------------------------------- 5. Syncthing
log "5/7 Syncthing"
sudo apt-get install -y syncthing
sudo systemctl enable --now "syncthing@$USER.service"
sleep 1
systemctl is-active --quiet "syncthing@$USER.service" || die "syncthing not active"
warn "Syncthing GUI binds to 127.0.0.1:8384 by default."
warn "To reach it remotely, SSH-tunnel:  ssh -L 8384:127.0.0.1:8384 $USER@<host>"

# ---------------------------------------------------------------- 6. rsync cron scaffold
log "6/7 rsync nightly backup scaffold"
sudo install -d -m 0755 /var/backups/incoming
sudo install -d -m 0755 /etc/cron.d
sudo tee /etc/cron.d/nightly-rsync-receive >/dev/null <<'EOF'
# Nightly rsync receive — fill in SRC and adjust flags.
# Runs at 03:17 to avoid the top-of-hour stampede.
# SRC=user@source-host:/path/to/data
# DEST=/var/backups/incoming
# 17 3 * * * root /usr/bin/rsync -aAXH --delete --numeric-ids \
#   -e 'ssh -i /root/.ssh/backup_id -o StrictHostKeyChecking=accept-new' \
#   "$SRC" "$DEST" >>/var/log/nightly-rsync.log 2>&1
EOF
sudo chmod 0644 /etc/cron.d/nightly-rsync-receive
warn "Edit /etc/cron.d/nightly-rsync-receive: uncomment + set SRC/DEST/key."

# ---------------------------------------------------------------- 7. WireGuard (netns split tunnel)
log "7/7 WireGuard split-tunnel skeleton"
sudo apt-get install -y wireguard wireguard-tools
sudo install -d -m 0700 /etc/wireguard
# Skeleton config — DO NOT start until you paste a real [Interface]/[Peer].
if [[ ! -f /etc/wireguard/wg-torrent.conf ]]; then
  sudo tee /etc/wireguard/wg-torrent.conf >/dev/null <<'EOF'
# Paste Mullvad/Proton config here. Keep PrivateKey out of git.
# [Interface]
# PrivateKey = ...
# Address    = 10.x.x.x/32
# DNS        = 10.64.0.1
#
# [Peer]
# PublicKey  = ...
# Endpoint   = x.x.x.x:51820
# AllowedIPs = 0.0.0.0/0, ::/0
EOF
  sudo chmod 0600 /etc/wireguard/wg-torrent.conf
fi
# netns helper: torrent client launched inside `wgns` has no route except wg
sudo tee /usr/local/sbin/wgns-up >/dev/null <<'EOF'
#!/usr/bin/env bash
# Bring wg-torrent up inside netns 'wgns'. Torrent client run with:
#   sudo ip netns exec wgns sudo -u $USER qbittorrent-nox
set -euo pipefail
ip netns add wgns 2>/dev/null || true
ip -n wgns link set lo up
ip link add wg-torrent type wireguard
wg setconf wg-torrent <(wg-quick strip wg-torrent)
ip link set wg-torrent netns wgns
ip -n wgns addr add "$(awk -F'= *' '/^Address/{print $2;exit}' /etc/wireguard/wg-torrent.conf)" dev wg-torrent
ip -n wgns link set wg-torrent up
ip -n wgns route add default dev wg-torrent
EOF
sudo chmod 0755 /usr/local/sbin/wgns-up
warn "WireGuard not started — paste keys into /etc/wireguard/wg-torrent.conf then run /usr/local/sbin/wgns-up"

# ---------------------------------------------------------------- summary
log "summary"
echo
echo "Listening sockets:"
sudo ss -tulpnH | awk '{printf "  %-5s %-22s %s\n",$1,$5,$7}' | sort -u
echo
echo "Service status:"
for s in tailscaled pihole-FTL suricata "syncthing@$USER"; do
  printf "  %-25s %s\n" "$s" "$(systemctl is-active "$s" 2>/dev/null || echo inactive)"
done
printf "  %-25s %s\n" "zeek" "$(/opt/zeek/bin/zeekctl status 2>/dev/null | awk 'NR==2{print $4}')"
echo
cat <<'EOF'
Manual steps still required:
  1. sudo tailscale up --advertise-exit-node --ssh   (then approve in admin console)
  2. Pi-hole web admin password:  sudo pihole -a -p
     Point your LAN DHCP at this box's IP for DNS, or set per-device.
  3. Suricata/Zeek will only see traffic that physically reaches $IFACE.
     If this box is not the gateway, configure a mirror/span port on your switch.
  4. /etc/cron.d/nightly-rsync-receive — uncomment and fill SRC/DEST/key path.
  5. /etc/wireguard/wg-torrent.conf — paste Mullvad/Proton config.
     Then `sudo /usr/local/sbin/wgns-up` and run torrent client with:
        sudo ip netns exec wgns sudo -u $USER <client>
  6. ufw is installed but not enabled. Decide your rules first; enabling now
     without rules will lock you out over SSH.

Expected ports in use after full config:
  22/tcp    sshd
  53/tcp+udp  pihole-FTL
  80/tcp    pihole web admin (lighttpd)
  8384/tcp  syncthing GUI (loopback only)
  22000/tcp+udp, 21027/udp  syncthing sync + discovery
  41641/udp tailscale
  (wg-torrent has no listening port — client-only)
EOF
