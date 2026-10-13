#!/bin/sh
set -eu

home="${HERMIT_HOME:-/var/lib/hermit}"
port="${HERMIT_CRAB_PORT:-7777}"
reef_url="${HERMIT_REEF_URL:-http://127.0.0.1:80}"
reef_url="${reef_url%/}"

case "$port" in
    ''|*[!0-9]*)
        echo "HERMIT_CRAB_PORT must be an integer" >&2
        exit 1
        ;;
esac

mkdir -p "$home"

yaml_quote() {
    # Double-quoted YAML scalar. Control characters would break the one-line value.
    if printf '%s' "$1" | grep -q '[[:cntrl:]]'; then
        return 1
    fi
    printf '"'
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
    printf '"'
}

if [ ! -f "$home/config.yml" ]; then
    if [ -z "${HERMIT_REEF_AGENT_TOKEN:-}" ] || [ -z "${HERMIT_REEF_ADMIN_USER:-}" ] || [ -z "${HERMIT_REEF_ADMIN_PASSWORD:-}" ]; then
        echo "HERMIT_REEF_AGENT_TOKEN, HERMIT_REEF_ADMIN_USER, and HERMIT_REEF_ADMIN_PASSWORD are required to create config.yml" >&2
        exit 1
    fi
    quoted_url=$(yaml_quote "$reef_url") || {
        echo "HERMIT_REEF_URL cannot be written into config.yml" >&2
        exit 1
    }
    quoted_token=$(yaml_quote "$HERMIT_REEF_AGENT_TOKEN") || {
        echo "HERMIT_REEF_AGENT_TOKEN cannot be written into config.yml" >&2
        exit 1
    }

    echo "waiting for reef login at ${reef_url}"
    token=""
    attempt=0
    while [ "$attempt" -lt 60 ]; do
        body=$(jq -n \
            --arg username "$HERMIT_REEF_ADMIN_USER" \
            --arg password "$HERMIT_REEF_ADMIN_PASSWORD" \
            '{username:$username,password:$password}')
        if response=$(curl -fsS --max-time 10 -X POST "${reef_url}/api/admin/login" \
            -H 'Content-Type: application/json' \
            -d "$body"); then
            token=$(printf '%s' "$response" | jq -r '.data.token // empty')
            if [ -n "$token" ] && [ "$token" != "null" ]; then
                break
            fi
            token=""
        fi
        attempt=$((attempt + 1))
        sleep 2
    done
    if [ -z "$token" ]; then
        echo "reef login did not succeed" >&2
        exit 1
    fi

    tmp_key=$(mktemp)
    if ! curl -fsS --max-time 10 "${reef_url}/api/admin/signing-key" \
        -H "Authorization: Bearer ${token}" \
        | jq -er '.data.publicKey' > "$tmp_key"; then
        rm -f "$tmp_key"
        echo "could not read reef signing key" >&2
        exit 1
    fi
    if ! grep -q -- '-----BEGIN' "$tmp_key"; then
        rm -f "$tmp_key"
        echo "reef signing key was not a PEM" >&2
        exit 1
    fi
    mv "$tmp_key" "$home/reef.pub"
    chmod 644 "$home/reef.pub"

    tmp_config=$(mktemp)
    cat > "$tmp_config" <<EOF
reef:
  url: ${quoted_url}
  token: ${quoted_token}
  public_key: reef.pub
server:
  host: "127.0.0.1"
  port: ${port}
sync:
  interval_seconds: 300
report:
  enabled: true
  interval_seconds: 60
EOF
    mv "$tmp_config" "$home/config.yml"
    chmod 600 "$home/config.yml"
    echo "wrote ${home}/config.yml"
fi

chown -R hermit:hermit "$home"
chmod 700 "$home"

exec runuser -u hermit -- java -cp "/app/crab.jar:/app/lib/*" dev.hermit.crab.CrabMain serve
